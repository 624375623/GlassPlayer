package com.glassplayer.cast

import android.content.Context
import android.net.wifi.WifiManager
import com.glassplayer.data.DebugLog
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * SSDP 发现 + 设备描述解析。
 *
 * M-SEARCH 是发往组播地址的，但设备的标准回复是**单播**回给发送方的 socket，
 * 所以普通 DatagramSocket 就能收到；仍然申请 MulticastLock，个别设备（尤其老盒子）
 * 会回组播，不加锁系统会把包丢掉。
 */
object SsdpDiscovery {

    private const val SSDP_HOST = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1"

    suspend fun discover(context: Context, timeoutMs: Long = 6000): List<CastDevice> =
        withContext(Dispatchers.IO) {
            DebugLog.record("cast", "开始搜索 · ${CastNet.summary(context)}")
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifi?.createMulticastLock("glassplayer-ssdp")?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
            DebugLog.record(
                "cast",
                "多播锁=${if (lock?.isHeld == true) "已获取" else "未获取（部分老设备可能收不到）"}",
            )
            try {
                val locations = LinkedHashMap<String, String>() // usn -> location
                var packetCount = 0
                DatagramSocket().use { socket ->
                    socket.soTimeout = 1200
                    val group = InetAddress.getByName(SSDP_HOST)
                    val payload = buildString {
                        append("M-SEARCH * HTTP/1.1\r\n")
                        append("HOST: $SSDP_HOST:$SSDP_PORT\r\n")
                        append("MAN: \"ssdp:discover\"\r\n")
                        append("MX: 3\r\n")
                        append("ST: $SEARCH_TARGET\r\n")
                        append("\r\n")
                    }
                    val bytes = payload.toByteArray()
                    // 发三遍：UDP 不可靠，丢一次就少一台设备
                    repeat(3) { round ->
                        val sent = runCatching {
                            socket.send(DatagramPacket(bytes, bytes.size, group, SSDP_PORT))
                        }.isSuccess
                        DebugLog.record(
                            "cast",
                            "M-SEARCH 第${round + 1}次 ${if (sent) "已发送" else "发送失败"}",
                        )
                        Thread.sleep(400)
                    }
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        val buf = ByteArray(2048)
                        val packet = DatagramPacket(buf, buf.size)
                        try {
                            socket.receive(packet)
                        } catch (_: Exception) {
                            continue
                        }
                        packetCount++
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val headers = parseHeaders(text)
                        val location = headers["location"]
                        // 前 12 个包逐条记：能看出搜到了什么类型的东西、为什么被过滤
                        if (packetCount <= 12) {
                            DebugLog.record(
                                "cast",
                                "包#$packetCount st=${headers["st"]?.take(52) ?: "?"} " +
                                    "loc=${location?.take(56) ?: "无 Location"}",
                            )
                        }
                        if (location == null) continue
                        val usn = headers["usn"] ?: location
                        locations[usn] = location
                    }
                }
                DebugLog.record("cast", "收包 $packetCount 个 · 候选 ${locations.size} 个")
                if (packetCount == 0) {
                    DebugLog.record(
                        "cast",
                        "一个响应都没收到 → 依次确认：手机和电视在同一 Wi-Fi、" +
                            "电视的投屏/DLNA 已开启、路由器没开 AP 隔离",
                    )
                }
                loadDevices(locations.values.toList())
            } finally {
                runCatching { lock?.release() }
            }
        }

    private fun parseHeaders(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        text.lineSequence().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                if (key.isNotEmpty()) out[key] = value
            }
        }
        return out
    }

    /** 拉描述文档拿 friendlyName 和控制地址；失败或不是渲染设备就丢掉。 */
    private suspend fun loadDevices(locations: List<String>): List<CastDevice> =
        coroutineScope {
            // 并行拉取：几台设备不必串行等，最坏耗时从「每台超时累加」降到一次超时
            val results = locations.map { location ->
                async { location to runCatching { fetchDescription(location) } }
            }.awaitAll()

            val out = ArrayList<CastDevice>()
            val seen = HashSet<String>()
            for ((location, result) in results) {
                val device = result.getOrNull()
                if (device == null) {
                    DebugLog.record(
                        "cast",
                        "描述 ${location.take(56)} 不可用 → " +
                            (result.exceptionOrNull()?.message ?: "没有 AVTransport 服务"),
                    )
                    continue
                }
                val key = device.uuid.ifBlank { device.location }
                if (!seen.add(key)) continue
                out.add(device)
                DebugLog.record(
                    "cast",
                    "设备 ${device.name} · 型号=${device.model.ifBlank { "未知" }} · " +
                        "音量控制=${if (device.canControlVolume) "支持" else "不支持"}",
                )
            }
            DebugLog.record("cast", "可用设备 ${out.size} 台")
            out.sortedBy { it.name }
        }

    private fun fetchDescription(location: String): CastDevice? {
        val xml = CastHttp.get(location, timeoutMs = 2500)
            ?: throw IOException("描述文档拉取失败（设备可能已离线或被防火墙拦）")
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(java.io.StringReader(xml))
        }

        var friendlyName = ""
        var modelName = ""
        var udn = ""
        var avControl: String? = null
        var rcControl: String? = null
        // 当前 service 块的类型，遇到 controlURL 时归到对应服务上
        var currentServiceType = ""
        // 设备实际声明的服务类型，默认标准值，遇到非标准的覆盖
        var avServiceType = "urn:schemas-upnp-org:service:AVTransport:1"
        var rcServiceType = "urn:schemas-upnp-org:service:RenderingControl:1"

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name.lowercase()) {
                    "friendlyname" -> if (friendlyName.isBlank()) {
                        friendlyName = readTagText(parser)
                    }
                    "modelname" -> if (modelName.isBlank()) {
                        modelName = readTagText(parser)
                    }
                    "udn" -> if (udn.isBlank()) {
                        udn = readTagText(parser)
                    }
                    "servicetype" -> currentServiceType = readTagText(parser)
                    "controlurl" -> {
                        val raw = readTagText(parser)
                        if (raw.isNotBlank()) {
                            val absolute = absolutize(location, raw)
                            when {
                                currentServiceType.contains("AVTransport", true) && avControl == null -> {
                                    avControl = absolute
                                    if (currentServiceType.isNotBlank()) avServiceType = currentServiceType
                                }
                                currentServiceType.contains("RenderingControl", true) && rcControl == null -> {
                                    rcControl = absolute
                                    if (currentServiceType.isNotBlank()) rcServiceType = currentServiceType
                                }
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }

        val av = avControl ?: throw IOException("这台设备没有 AVTransport（不是投屏设备）")
        return CastDevice(
            name = friendlyName.ifBlank { "未知设备" },
            location = location,
            avControlUrl = av,
            rcControlUrl = rcControl,
            uuid = udn,
            model = modelName,
            avServiceType = avServiceType,
            rcServiceType = rcServiceType,
        )
    }

    /**
     * 读标签文本。
     *
     * 设备描述里 `<friendlyName/>`、`<serviceType/>` 这类自闭合空标签很常见，
     * XmlPullParser.nextText() 在自闭合标签上会抛「END_TAG expected」，
     * 一旦抛在 forEach 里就会整台设备被丢弃（外层是 runCatching）。
     */
    private fun readTagText(parser: XmlPullParser): String {
        val selfClosing = runCatching { parser.isEmptyElementTag }.getOrDefault(false)
        if (selfClosing) return ""
        return runCatching { parser.nextText().trim() }.getOrDefault("")
    }

    /** controlURL 常写成相对路径（/upnp/control/AVTransport1），要按 LOCATION 的 origin 补全。 */
    private fun absolutize(base: String, path: String): String {
        if (path.startsWith("http://", true) || path.startsWith("https://", true)) return path
        return runCatching {
            val uri = URI(base)
            val port = if (uri.port > 0) ":${uri.port}" else ""
            val prefix = if (path.startsWith("/")) path else "/$path"
            "${uri.scheme}://${uri.host}$port$prefix"
        }.getOrDefault(path)
    }
}

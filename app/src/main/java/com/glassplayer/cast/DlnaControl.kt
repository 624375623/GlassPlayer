package com.glassplayer.cast

import com.glassplayer.data.DebugLog

/**
 * 用 AVTransport / RenderingControl 两个 UPnP 服务控制电视。
 *
 * 都是 SOAP over HTTP，纯手写就能跑，不需要额外依赖。参数值必须做 XML 转义，
 * 否则 URL 里的 & 会把设备端解析搞崩（这是投屏「点了没反应」最常见的原因）。
 */
object DlnaControl {

    fun setUri(device: CastDevice, url: String, title: String, mime: String): Boolean {
        val metadata = buildMetadata(url, title, mime)
        val body = envelope(
            action = "SetAVTransportURI",
            service = device.avServiceType,
            inner = """
                <InstanceID>0</InstanceID>
                <CurrentURI>${escape(url)}</CurrentURI>
                <CurrentURIMetaData>${escape(metadata)}</CurrentURIMetaData>
            """.trimIndent(),
        )
        val response = CastHttp.soap(device.avControlUrl, "SetAVTransportURI", device.avServiceType, body)
        val ok = response != null && !response.contains("<s:Fault>")
        if (!ok) {
            DebugLog.record("cast", "SetAVTransportURI 失败 · ${describeFault(response)}")
        }
        return ok
    }

    fun play(device: CastDevice, speed: Double = 1.0): Boolean =
        invoke(device, "Play", device.avServiceType, """
            <InstanceID>0</InstanceID>
            <Speed>${formatSpeed(speed)}</Speed>
        """.trimIndent())

    fun pause(device: CastDevice): Boolean =
        invoke(device, "Pause", device.avServiceType, "<InstanceID>0</InstanceID>")

    fun stop(device: CastDevice): Boolean =
        invoke(device, "Stop", device.avServiceType, "<InstanceID>0</InstanceID>")

    fun seek(device: CastDevice, positionMs: Long): Boolean {
        val target = formatTime(positionMs)
        return invoke(device, "Seek", device.avServiceType, """
            <InstanceID>0</InstanceID>
            <Unit>REL_TIME</Unit>
            <Target>${escape(target)}</Target>
        """.trimIndent())
    }

    /** 电视当前是 PLAYING / PAUSED / STOPPED 还是 TRANSITIONING。 */
    fun transportState(device: CastDevice): String? =
        query(device, "GetTransportInfo", device.avServiceType, "<InstanceID>0</InstanceID>", "CurrentTransportState")

    /** 电视端进度；同时能看到 Duration，用来校准手机上的总时长。 */
    fun positionInfo(device: CastDevice): Pair<Long, Long> {
        // 一次 GetPositionInfo 就能同时拿到 RelTime / TrackDuration。
        // 原来是两次独立 SOAP，每 2 秒轮询就翻倍了请求数和失败概率
        val body = envelope("GetPositionInfo", device.avServiceType, "<InstanceID>0</InstanceID>")
        val response = CastHttp.soap(
            device.avControlUrl,
            "GetPositionInfo",
            device.avServiceType,
            body,
        ) ?: return 0L to 0L
        return parseTime(tagValue(response, "RelTime")) to
            parseTime(tagValue(response, "TrackDuration"))
    }

    fun setVolume(device: CastDevice, volume: Int): Boolean {
        val rc = device.rcControlUrl ?: return false
        val body = envelope(
            "SetVolume",
            device.rcServiceType,
            """
                <InstanceID>0</InstanceID>
                <Channel>Master</Channel>
                <DesiredVolume>${volume.coerceIn(0, 100)}</DesiredVolume>
            """.trimIndent(),
        )
        return CastHttp.soap(rc, "SetVolume", device.rcServiceType, body) != null
    }

    fun volume(device: CastDevice): Int? {
        val raw = query(
            device, "GetVolume", device.rcServiceType, """
            <InstanceID>0</InstanceID>
            <Channel>Master</Channel>
        """.trimIndent(), "CurrentVolume", device.rcControlUrl,
        )
        return raw?.toIntOrNull()
    }

    private fun invoke(device: CastDevice, action: String, service: String, inner: String): Boolean {
        val body = envelope(action, service, inner)
        val response = CastHttp.soap(device.avControlUrl, action, service, body)
        val ok = response != null && !response.contains("<s:Fault>")
        if (!ok) {
            DebugLog.record("cast", "$action 失败 · ${describeFault(response)}")
        }
        return ok
    }

    private fun query(
        device: CastDevice,
        action: String,
        service: String,
        inner: String,
        field: String,
        url: String? = null,
    ): String? {
        val target = url ?: device.avControlUrl
        val body = envelope(action, service, inner)
        val response = CastHttp.soap(target, action, service, body) ?: return null
        return tagValue(response, field)
    }

    /** 取 SOAP 响应里的标签值；兼容带命名空间前缀的写法（部分设备返回 `<ns:RelTime>`）。 */
    private fun tagValue(response: String, field: String): String? =
        Regex("<(?:[a-zA-Z0-9]+:)?$field>([^<]*)</(?:[a-zA-Z0-9]+:)?$field>")
            .find(response)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun envelope(action: String, service: String, inner: String): String =
        // 不能用 trimIndent：inner 已经是 trimIndent 的顶格多行，插进来后让本模板
        // 出现缩进 0 的行，外层 trimIndent 会算出公共缩进 0，导致 <?xml 前残留缩进、
        // 被严格的设备拒绝（XML declaration not at start of entity）。trim 只清首尾空白，
        // 保证 <?xml 在最开头；内部缩进不统一不影响 XML 合法性。
        """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/" xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <u:$action xmlns:u="$service">
                  $inner
                </u:$action>
              </s:Body>
            </s:Envelope>
        """.trim()

    /**
     * DIDL-Lite 元数据。很多电视不带这段也能播，但带了才能显示标题、
     * 也才肯认 `res` 里的 mime，对 mkv 之类的兼容性影响明显。
     */
    private fun buildMetadata(url: String, title: String, mime: String): String =
        """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
              <item id="0" parentID="-1" restricted="1">
                <dc:title>${escape(title)}</dc:title>
                <upnp:class>object.item.videoItem</upnp:class>
                <res protocolInfo="http-get:*:$mime:*">${escape(url)}</res>
              </item>
            </DIDL-Lite>
        """.trimIndent()

    /** 从 SOAP Fault 里提取关键信息：faultstring + UPnP 错误码/描述，比整段 XML 好定位。 */
    private fun describeFault(response: String?): String {
        if (response.isNullOrBlank()) return "无响应（请求没到设备）"
        fun tag(name: String): String? =
            Regex("<(?:[a-zA-Z]+:)?$name>([^<]*)</(?:[a-zA-Z]+:)?$name>")
                .find(response)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        val fault = tag("faultstring")
        val code = tag("errorCode")
        val desc = tag("errorDescription")
        return buildString {
            append("fault=").append(fault ?: "未知")
            if (code != null) append(" · code=").append(code)
            if (desc != null) append(" · ").append(desc)
        }
    }

    private fun escape(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun formatSpeed(speed: Double): String =
        if (speed == speed.toLong().toDouble()) "${speed.toLong()}" else speed.toString()

    /** 毫秒 → UPnP 的 H:MM:SS。 */
    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return "%d:%02d:%02d".format(h, m, s)
    }

    private fun parseTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val parts = raw.trim().split(":").mapNotNull { it.toDoubleOrNull() }
        return when (parts.size) {
            3 -> ((parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000).toLong()
            2 -> ((parts[0] * 60 + parts[1]) * 1000).toLong()
            1 -> (parts[0] * 1000).toLong()
            else -> 0L
        }
    }
}

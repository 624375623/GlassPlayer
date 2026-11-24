package com.glassplayer.cast

import android.content.Context
import android.net.Uri
import com.glassplayer.data.DebugLog
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一个只投给电视用的极简 HTTP 服务。
 *
 * 为什么要它：电视播不了手机里的 `content://`，必须给一个它自己能取的 http 地址。
 * 三种素材统一在这里出口：
 * - 本地文件 / 相册视频：直接从本机读，按 Range 分段吐；
 * - 网盘视频：夸克直链带 Referer / Cookie，电视拿不到，这里做一次带鉴权头的代理；
 *
 * 必须支持 Range：电视拖进度、断点续播全靠它，只回 200 全量的话很多设备会直接卡住。
 */
class LocalMediaServer(private val context: Context) {

    private val token = java.util.UUID.randomUUID().toString().replace("-", "")
    private var serverSocket: ServerSocket? = null
    // 电视通常只开 2~3 个连接；有界池避免反复重连时线程无限堆积，多余线程 60s 回收。
    // 每次 serve() 都新建一个池、stop() 时关掉：这个类的生命周期就是「一次投屏」，
    // 常驻池在 stop() 后既不会被回收，也会在换集时把旧连接一直挂着
    @Volatile
    private var pool: java.util.concurrent.ThreadPoolExecutor? = null
    private fun newPool(): java.util.concurrent.ThreadPoolExecutor = java.util.concurrent.ThreadPoolExecutor(
        2,
        8,
        60L,
        java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(),
        java.util.concurrent.ThreadFactory { r ->
            Thread(r, "cast-http").apply { isDaemon = true }
        },
    )
    private val running = AtomicBoolean(false)
    @Volatile private var source: Source? = null

    /** 投屏时手机多半会锁屏：不掐住 Wi-Fi 和 CPU，电视那边几秒就卡住。 */
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    /** 手机在局域网里的地址；取不到就是没连 Wi-Fi。 */
    fun localAddress(): String? {
        return runCatching {
            val candidates = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { ni ->
                    val name = ni.name.lowercase()
                    ni.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .filter { it.isSiteLocalAddress }
                        .map { name to it.hostAddress }
                }
            // 优先 wlan / ap：手机同时挂着 VPN、USB 共享或热点时，
            // 直接取第一个网卡很可能给电视一个它根本访问不到的地址
            val wifi = candidates.firstOrNull { (name, _) ->
                name.startsWith("wlan") || name.startsWith("ap") || name.contains("wifi")
            }
            (wifi ?: candidates.firstOrNull())?.second
        }.getOrNull()
    }

    /** 启动并返回给电视用的 http 地址。 */
    fun serve(source: Source): String {
        stop()
        this.source = source
        val ip = localAddress() ?: throw IllegalStateException("手机没连 Wi-Fi，电视访问不到")
        val socket = ServerSocket(0).apply { reuseAddress = true }
        serverSocket = socket
        val executor = newPool()
        pool = executor
        running.set(true)
        acquireLocks()
        executor.execute { acceptLoop(socket) }
        val url = "http://$ip:${socket.localPort}/s/$token/media"
        DebugLog.record("cast", "本地服务 $url · ${source.describe()}")
        return url
    }

    private fun acquireLocks() {
        runCatching {
            val wifi = context.getSystemService(android.content.Context.WIFI_SERVICE)
                as? android.net.wifi.WifiManager
            wifiLock = wifi?.createWifiLock(
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "glassplayer-cast",
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        runCatching {
            val power = context.getSystemService(android.content.Context.POWER_SERVICE)
                as? android.os.PowerManager
            wakeLock = power
                ?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "glassplayer:cast")
                ?.apply {
                    setReferenceCounted(false)
                    acquire(30 * 60 * 1000L)
                }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        source = null
        // 关掉线程池，否则 stop() 之后 2~8 个 cast-http 线程还会挂 60 秒；
        // shutdownNow 会打断正在取流的任务，电视端表现为立即停止 —— 断开投屏就该这样
        pool?.shutdownNow()
        pool = null
        releaseLocks()
    }

    private fun acceptLoop(socket: ServerSocket) {
        val executor = pool ?: return
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break
            }
            runCatching { executor.execute { runCatching { handle(client) } } }
                .onFailure { runCatching { client.close() } }
        }
    }

    private fun handle(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 15_000
            // 局域网直传：关掉 Nagle 减少小包延迟叠加，加大发送缓冲让吞吐更稳
            runCatching { sock.tcpNoDelay = true }
            runCatching { sock.sendBufferSize = 1024 * 1024 }
            val input = BufferedInputStream(sock.getInputStream())
            val output = java.io.BufferedOutputStream(sock.getOutputStream(), 256 * 1024)

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1]

            var rangeHeader: String? = null
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase()
                    if (key == "range") rangeHeader = line.substring(colon + 1).trim()
                }
            }

            val src = source
            // 记录电视每次来取流：这是区分「SOAP 被拒」和「电视取流失败」的关键证据
            DebugLog.record(
                "cast",
                "电视来取流 $method ${path.take(42)} · range=${rangeHeader ?: "无"}",
            )
            if (src == null || !path.startsWith("/s/$token/")) {
                DebugLog.record("cast", "取流响应 404（token 不匹配或源已清空）")
                writeStatus(output, 404, "Not Found")
                return
            }

            val total = src.length
            val headOnly = method == "HEAD"

            // 长度未知（直链不给 Content-Length）：不带 Content-Length，靠关连接收尾，
            // 至少能播，只是电视端拖不了进度
            if (total <= 0) {
                DebugLog.record("cast", "取流响应 200（源长度未知，不支持拖动）")
                output.write(
                    (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: ${src.contentType}\r\n" +
                            "Accept-Ranges: bytes\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(),
                )
                output.flush()
                if (headOnly) return
                src.openRange(0, Long.MAX_VALUE)?.use { body -> pump(body, output, Long.MAX_VALUE) }
                return
            }

            val range = parseRange(rangeHeader, total)
            if (range == null && rangeHeader != null) {
                // 客户端要的范围不合法：标准做法是回 416
                DebugLog.record("cast", "取流响应 416 · 非法 range=$rangeHeader")
                output.write(
                    (
                        "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                            "Content-Range: bytes */$total\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(),
                )
                output.flush()
                return
            }

            if (range != null) {
                val (start, end) = range
                val length = end - start + 1
                DebugLog.record("cast", "取流响应 206 · $start-$end/$total")
                output.write(
                    (
                        "HTTP/1.1 206 Partial Content\r\n" +
                            "Content-Type: ${src.contentType}\r\n" +
                            "Content-Length: $length\r\n" +
                            "Content-Range: bytes $start-$end/$total\r\n" +
                            "Accept-Ranges: bytes\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(),
                )
                output.flush()
                if (headOnly) return
                src.openRange(start, end)?.use { body -> pump(body, output, length) }
                return
            }

            DebugLog.record("cast", "取流响应 200 · 全量 0-${total - 1}")
            output.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: ${src.contentType}\r\n" +
                        "Content-Length: $total\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(),
            )
            output.flush()
            if (headOnly) return
            src.openRange(0, total - 1)?.use { body -> pump(body, output, total) }
        }
    }

    private fun pump(body: InputStream, output: OutputStream, limit: Long) {
        val buffer = ByteArray(256 * 1024)
        var left = limit
        while (left > 0) {
            val read = try {
                body.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            } catch (e: Exception) {
                DebugLog.record("cast", "上游读取中断 · ${e.javaClass.simpleName} · 已转发 ${limit - left}")
                -1
            }
            if (read <= 0) break
            runCatching { output.write(buffer, 0, read) }.onFailure {
                // 电视主动断开：正常收尾，不算错误
                return
            }
            left -= read
        }
        runCatching { output.flush() }
        // 码流被截断是电视端花屏的直接原因，必须在日志里留证
        if (limit != Long.MAX_VALUE && left > 0) {
            DebugLog.record("cast", "转发提前结束 · 已发 ${limit - left} / 预期 $limit · 余 $left")
        }
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val value = input.read()
            if (value == -1) return if (builder.isEmpty()) null else builder.toString()
            if (value == '\n'.code) return builder.toString().trimEnd('\r')
            if (value == '\r'.code) continue
            builder.append(value.toChar())
            if (builder.length > 4096) return builder.toString()
        }
    }

    private fun writeStatus(output: OutputStream, code: Int, text: String) {
        output.write(
            "HTTP/1.1 $code $text\r\nConnection: close\r\n\r\n".toByteArray(),
        )
        output.flush()
    }

    /** `bytes=start-end` / `bytes=start-` / `bytes=-suffix`。 */
    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        if (header == null || total <= 0) return null
        val spec = header.substringAfter('=', "").trim()
        if (spec.isEmpty()) return null
        val first = spec.substringBefore(',')
        val startRaw = first.substringBefore('-').trim()
        val endRaw = first.substringAfter('-', "").trim()
        return when {
            startRaw.isEmpty() && endRaw.isNotEmpty() -> {
                // 结尾若干字节
                val suffix = endRaw.toLongOrNull() ?: return null
                if (suffix <= 0) return null
                val start = (total - suffix).coerceAtLeast(0)
                start to (total - 1)
            }
            startRaw.isNotEmpty() -> {
                val start = startRaw.toLongOrNull() ?: return null
                if (start >= total) return null
                val end = endRaw.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
                if (end < start) return null
                start to end
            }
            else -> null
        }
    }

    /** 投给电视的一段素材。 */
    interface Source {
        val length: Long
        val contentType: String
        fun openRange(start: Long, end: Long): InputStream?
        fun describe(): String
    }

    /** 本地文件（含 SAF 的 fd）。 */
    class LocalFileSource(private val opener: () -> InputStream?, override val length: Long, override val contentType: String) :
        Source {
        override fun openRange(start: Long, end: Long): InputStream? {
            val stream = opener() ?: return null
            var skipped = 0L
            while (skipped < start) {
                val n = stream.skip(start - skipped)
                if (n <= 0) break
                skipped += n
            }
            return stream
        }

        override fun describe(): String = "本地文件 ${length / 1024 / 1024}MB"
    }

    /**
     * 远程直链代理。夸克 CDN 对 UA / Referer / Cookie 的组合很敏感，不同文件命中的策略
     * 不一样，这里和播放端 QuarkRemoteDataSource 保持同一套多组合重试，只有拿到 4xx/5xx
     * 才换下一组请求头。
     */
    class RemoteSource(
        private val url: String,
        private val cookie: String?,
        override val contentType: String,
        private val quark: Boolean = true,
    ) : Source {

        @Volatile private var knownLength: Long = -1L

        init {
            val ckLen = cookie?.length ?: 0
            DebugLog.record("cast", "远程代理初始化 · quark=$quark · cookie长度=$ckLen · url前缀=${url.take(48)}")
        }

        override val length: Long
            get() {
                if (knownLength < 0) probeLength()
                return knownLength.coerceAtLeast(0)
            }

        private data class Variant(val ua: String, val headers: Map<String, String>)

        private val variants: List<Variant> = buildList {
            if (quark) {
                val ck = cookie?.takeIf { it.isNotBlank() }
                add(Variant(QUARK_UA, buildMap {
                    put("Referer", "https://pan.quark.cn/")
                    put("Origin", "https://pan.quark.cn")
                    if (ck != null) put("Cookie", ck)
                }))
                add(Variant(QUARK_UA, buildMap { if (ck != null) put("Cookie", ck) }))
                add(Variant(BROWSER_UA, buildMap {
                    put("Referer", "https://pan.quark.cn/")
                    if (ck != null) put("Cookie", ck)
                }))
                add(Variant(QUARK_UA, emptyMap()))
            } else {
                add(Variant("", emptyMap()))
            }
        }

        private fun connect(method: String, range: String?, variant: Variant): HttpURLConnection {
            // 手动跟随重定向：HttpURLConnection 不支持跨协议重定向，且自动跟随时可能丢头，
            // 这里禁用自动跳转，逐跳带齐 Cookie/Referer，并记录每一跳
            var current = url
            var hop = 0
            while (true) {
                val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 8000
                    readTimeout = 20_000
                    instanceFollowRedirects = false
                    if (variant.ua.isNotBlank()) setRequestProperty("User-Agent", variant.ua)
                    if (range != null) setRequestProperty("Range", range)
                    variant.headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                val code = conn.responseCode
                if (code in listOf(301, 302, 303, 307, 308) && hop < 5) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrBlank()) {
                        DebugLog.record("cast", "重定向缺少 Location，code=$code")
                        return conn
                    }
                    current = URL(URL(current), location).toString()
                    hop++
                    DebugLog.record("cast", "手动跟随重定向 hop=$hop code=$code → ${current.take(64)}")
                    continue
                }
                return conn
            }
        }

        private fun probeLength() {
            // 用 GET bytes=0-0 依次试各组头：从 Content-Range 的 /total 拿总长，
            // 服务器忽略 Range 回 200 时就用 Content-Length
            for (variant in variants) {
                val conn = try {
                    connect("GET", "bytes=0-0", variant)
                } catch (e: Exception) {
                    continue
                }
                try {
                    val code = conn.responseCode
                    if (code in 200..299) {
                        val total = conn.getHeaderField("Content-Range")
                            ?.substringAfterLast('/')?.trim()?.toLongOrNull()
                        if (total != null && total > 0) {
                            knownLength = total
                            DebugLog.record("cast", "远程总长探测成功 · $total · code=$code")
                            return
                        }
                        valueOrNull(conn.getHeaderField("Content-Length"))?.let {
                            knownLength = it
                            DebugLog.record("cast", "远程总长探测成功(Content-Length) · $it")
                            return
                        }
                    }
                    DebugLog.record("cast", "远程总长探测被拒 code=$code，换下一组头")
                } catch (e: Exception) {
                    // 连接异常就试下一组
                } finally {
                    conn.disconnect()
                }
            }
        }

        private fun valueOrNull(raw: String?): Long? =
            raw?.trim()?.toLongOrNull()?.takeIf { it > 0 }

        override fun openRange(start: Long, end: Long): InputStream? {
            val range = if (end == Long.MAX_VALUE) "bytes=$start-" else "bytes=$start-$end"
            var lastCode = -1
            for (variant in variants) {
                val conn = try {
                    connect("GET", range, variant)
                } catch (e: Exception) {
                    DebugLog.record("cast", "远程连接失败 · ${e.javaClass.simpleName}: ${e.message}")
                    return null
                }
                try {
                    val code = conn.responseCode
                    if (code in 200..299) {
                        DebugLog.record(
                            "cast",
                            "远程已连接 code=$code · Content-Range=${conn.getHeaderField("Content-Range") ?: "无"}",
                        )
                        val stream = conn.inputStream
                        // 上游不认 Range 时回的是 200 全量，这里得自己把前 start 字节丢掉
                        if (code == 200 && start > 0) {
                            skipFully(stream, start)
                        }
                        // 关键：HttpURLConnection 不 disconnect 就会一直占着底层 socket，
                        // 电视反复重连后连接池被耗干，表现就是越投越卡、码流错乱花屏。
                        // 这里包一层，流关闭时连带断开连接；外面再套预读缓冲吸收 CDN 抖动。
                        return PreBufferStream(guarded(conn, stream), PREBUFFER_BYTES)
                    }
                    lastCode = code
                    DebugLog.record("cast", "远程取流被拒 code=$code，尝试下一组头")
                    conn.disconnect()
                } catch (e: Exception) {
                    DebugLog.record("cast", "远程取流异常 · ${e.javaClass.simpleName}: ${e.message}")
                    conn.disconnect()
                    return null
                }
            }
            DebugLog.record("cast", "所有请求组合都失败，最后 code=$lastCode")
            return null
        }

        private fun skipFully(stream: InputStream, count: Long) {
            var left = count
            val buffer = ByteArray(8 * 1024)
            while (left > 0) {
                val n = stream.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n <= 0) break
                left -= n
            }
        }

        /** 流关闭时连带 disconnect，避免底层 socket 一直被占着。 */
        private fun guarded(conn: HttpURLConnection, stream: InputStream): InputStream =
            object : InputStream() {
                override fun read(): Int = stream.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
                override fun close() {
                    runCatching { stream.close() }
                    runCatching { conn.disconnect() }
                }
            }

        /**
         * 预读缓冲：后台线程持续把上游数据灌进有界队列，电视端从队列消费。
         * CDN 抖动、慢回源时靠队列里的积压顶着，不会立刻让电视断供；
         * 队列满则生产者阻塞，内存有明确上限。
         */
        private class PreBufferStream(
            private val upstream: InputStream,
            capacityBytes: Int,
        ) : InputStream() {

            private val chunkSize = 256 * 1024
            private val queue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(
                (capacityBytes / chunkSize).coerceAtLeast(2) + 1,
            )

            @Volatile private var closed = false
            @Volatile private var ended = false

            private val worker = Thread({
                try {
                    val buf = ByteArray(chunkSize)
                    while (!closed) {
                        val n = upstream.read(buf, 0, chunkSize)
                        if (n <= 0) break
                        queue.put(buf.copyOf(n))
                    }
                } catch (_: Throwable) {
                    // 上游中断（超时、被 CDN 掐断）按结束处理，已入队的数据照常供完
                } finally {
                    ended = true
                }
            }, "cast-prebuffer").apply {
                isDaemon = true
                start()
            }

            private var current: ByteArray = EMPTY
            private var pos = 0

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len <= 0) return 0
                while (pos >= current.size) {
                    if (ended && queue.isEmpty()) return -1
                    val next = try {
                        queue.poll(2, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        return -1
                    } ?: continue
                    current = next
                    pos = 0
                }
                val n = minOf(len, current.size - pos)
                System.arraycopy(current, pos, b, off, n)
                pos += n
                return n
            }

            override fun close() {
                closed = true
                worker.interrupt()
                runCatching { upstream.close() }
            }

            companion object {
                private val EMPTY = ByteArray(0)
            }
        }

        override fun describe(): String = "远程代理 $url"
    }

    companion object {
        /** 远程代理预读缓冲容量：吸收 CDN 抖动，代价是首帧多等几百毫秒。 */
        private const val PREBUFFER_BYTES = 8 * 1024 * 1024

        // 夸克官方 PC 客户端 UA：浏览器/默认 Java UA 会被 CDN 按网页端限流或直接拒绝
        const val QUARK_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 " +
                "Safari/537.36 Channel/pckk_other_ch"
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/94.0.4606.71 Safari/537.36 Core/1.94.225.400 QQBrowser/12.2.5544.400"

        fun contentTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v", "mov" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "ts", "m2ts" -> "video/mp2t"
            "flv" -> "video/x-flv"
            "webm" -> "video/webm"
            "m3u8" -> "application/vnd.apple.mpegurl"
            "mp3" -> "audio/mpeg"
            "aac", "m4a" -> "audio/mp4"
            else -> "video/mp4"
        }

        /** 从文件路径 / content uri 造出一个可直接播的本地 Source。 */
        fun localSource(context: Context, uri: Uri, displayName: String): Source? {
            return runCatching {
                if (uri.scheme == "file" || uri.scheme == null) {
                    val file = File(uri.path ?: return null)
                    if (!file.exists() || file.length() <= 0) return null
                    LocalFileSource(
                        opener = { runCatching { FileInputStream(file) as InputStream }.getOrNull() },
                        length = file.length(),
                        contentType = contentTypeOf(file.name),
                    )
                } else {
                    // content:// 每次请求都重新开 fd：多个连接共享同一个 fd 会互相 seek 乱位
                    val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                    if (size <= 0) return null
                    LocalFileSource(
                        opener = {
                            runCatching {
                                context.contentResolver.openFileDescriptor(uri, "r")?.let { pfd ->
                                    FileInputStream(pfd.fileDescriptor)
                                } as InputStream?
                            }.getOrNull()
                        },
                        length = size,
                        contentType = contentTypeOf(displayName),
                    )
                }
            }.getOrNull()
        }
    }
}

package com.glassplayer.danmaku

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 极简 HTTP 客户端。工程不引入 OkHttp，避免新增依赖（当前构建是 --offline）。
 * 站点会返回 gzip / deflate，这里手动解压，否则拿到的是一堆二进制。
 */
internal object DanmakuNet {

    /**
     * 优酷弹幕要先拿 cna / _m_h5_tk 令牌，必须自己保存 cookie。
     *
     * 早先用的是全局 `CookieManager`，但只在 `CookieHandler.getDefault() == null`
     * 时才安装 —— Android 上这个全局值通常已被系统或别的库占住，于是容器从没生效：
     * 请求不带 Cookie、响应的 Set-Cookie 也没人接，`_m_h5_tk` 永远读不到，
     * 最终表现为「优酷一集弹幕都采集不到」。这里改成自己维护一张表，不再依赖全局状态。
     */
    private val cookieStore = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 优酷弹幕链路涉及的两个域：acs.youku.com / openapi.youku.com 与 cna 的 mmstat.com。 */
    private fun isYoukuHost(url: String): Boolean {
        val host = runCatching { URL(url).host }.getOrNull()?.lowercase().orEmpty()
        return host.contains("youku.com") || host.contains("mmstat.com")
    }

    fun cookieOf(name: String): String? = cookieStore[name]

    /** 丢掉某个 cookie（令牌过期时用来强制重新获取）。 */
    fun forgetCookie(name: String) {
        cookieStore.remove(name)
    }

    /** 收发 cookie：请求前拼 Cookie 头，响应后把 Set-Cookie 收进来。 */
    private fun cookieHeader(): String? {
        if (cookieStore.isEmpty()) return null
        return cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun absorbCookies(conn: HttpURLConnection) {
        val fields = runCatching { conn.headerFields }.getOrNull() ?: return
        // header 名大小写在不同实现里不一致，按忽略大小写找
        val lines = fields.entries
            .firstOrNull { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
            ?.value
            ?: return
        lines.forEach { line ->
            if (line.isNullOrBlank()) return@forEach
            val pair = line.substringBefore(';').trim()
            if (pair.isEmpty()) return@forEach
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', "").trim()
            // 服务端有时下发 undefined 占位值，别拿它覆盖已有有效 cookie
            if (name.isNotEmpty() && !name.startsWith("=") && value != "undefined") {
                cookieStore[name] = value
            }
        }
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** 部分站点（爱奇艺专辑页、优酷网页）只有桌面版才带完整数据，需要桌面 UA。 */
    private const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    suspend fun get(
        url: String,
        referer: String? = null,
        cookie: String? = null,
        desktop: Boolean = false,
    ): String = withContext(Dispatchers.IO) { request("GET", url, null, referer, cookie, desktop) }

    /** 需要读响应头（优酷的 cna 在 etag、令牌在 Set-Cookie）。 */
    suspend fun getWithHeaders(
        url: String,
        referer: String? = null,
        desktop: Boolean = false,
    ): Pair<String, Map<String, String>> = withContext(Dispatchers.IO) {
        val sink = HashMap<String, String>()
        val body = request("GET", url, null, referer, null, desktop, sink)
        body to sink
    }

    suspend fun post(
        url: String,
        body: String,
        referer: String? = null,
        contentType: String = "application/json; charset=utf-8",
    ): String = withContext(Dispatchers.IO) {
        request("POST", url, body, referer, null, false, null, contentType)
    }

    private fun request(
        method: String,
        url: String,
        body: String?,
        referer: String?,
        cookie: String? = null,
        desktop: Boolean = false,
        headerSink: MutableMap<String, String>? = null,
        contentType: String = "application/json; charset=utf-8",
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", if (desktop) UA_DESKTOP else UA)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "gzip, deflate")
            referer?.let { setRequestProperty("Referer", it) }
            // 显式传入的 cookie 优先；自动带上自己存的表时只限优酷体系，
            // 免得把优酷令牌发给腾讯/爱奇艺/B站
            val autoCookie = if (cookie == null && isYoukuHost(url)) cookieHeader() else null
            (cookie ?: autoCookie)?.let { setRequestProperty("Cookie", it) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType)
            }
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            // 响应头一到就把 Set-Cookie 收进来，后续请求才能带上令牌
            absorbCookies(conn)
            headerSink?.let { sink ->
                conn.headerFields.forEach { (key, values) ->
                    if (key != null && values.isNotEmpty()) {
                        sink[key.lowercase()] = values.joinToString("; ")
                    }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { readAll(it, conn.contentEncoding) } ?: ByteArray(0)
            if (code !in 200..299) {
                throw IOException("HTTP $code ${conn.responseMessage ?: ""}".trim())
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 先整段读原始字节，再按「魔数」判断要不要解压，而不是无条件相信 Content-Encoding。
     *
     * 有些 CDN / 运营商中转会把 gzip 头保留下来但内容已经是明文，
     * 直接套 GZIPInputStream 就会抛 "incorrect header check"。这里嗅探前两个字节：
     * 1f 8b = gzip，CMF/FLG 合法 = zlib，其余一律按明文处理；解压失败也退回原始字节。
     */
    private fun readAll(stream: java.io.InputStream, encoding: String?): ByteArray {
        val raw = stream.use { it.readBytes() }
        if (raw.size < 2) return raw

        val b0 = raw[0].toInt() and 0xFF
        val b1 = raw[1].toInt() and 0xFF

        // 1) gzip：魔数 1f 8b
        if (b0 == 0x1F && b1 == 0x8B) {
            return runCatching { GZIPInputStream(raw.inputStream()).use { it.readBytes() } }.getOrDefault(raw)
        }

        // 2) 标准 zlib：CMF/FLG 合法
        val zlibHeader = (b0 and 0x0F) == 8 && ((b0 shl 8) + b1) % 31 == 0
        if (zlibHeader) {
            return runCatching { InflaterInputStream(raw.inputStream()).use { it.readBytes() } }.getOrDefault(raw)
        }

        // 3) 裸 deflate（没有 zlib 头）：B 站 comment.xml 就是这种，必须 Inflater(nowrap = true)
        if (encoding != null && encoding.contains("deflate", true)) {
            val inflated: ByteArray? = runCatching {
                val inflater = java.util.zip.Inflater(true)
                try {
                    inflater.setInput(raw)
                    val out = java.io.ByteArrayOutputStream(raw.size * 4)
                    val buffer = ByteArray(8192)
                    while (!inflater.finished()) {
                        val n = inflater.inflate(buffer)
                        if (n == 0) {
                            if (inflater.needsInput() || inflater.needsDictionary()) break
                        } else {
                            out.write(buffer, 0, n)
                        }
                    }
                    out.toByteArray()
                } finally {
                    inflater.end()
                }
            }.getOrNull()
            if (inflated != null && inflated.isNotEmpty()) {
                // 解出来还是二进制，说明猜错了，退回原文
                return if (looksLikeText(inflated)) inflated else raw
            }
        }

        // 4) 本来就是明文（部分 CDN 会保留压缩头但内容已解压）
        return raw
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        val sample = minOf(bytes.size, 256)
        var printable = 0
        for (i in 0 until sample) {
            val c = bytes[i].toInt() and 0xFF
            if (c == 0x09 || c == 0x0A || c == 0x0D || (c in 0x20..0x7E) || c >= 0xC2) printable++
        }
        return printable >= sample * 0.85
    }
}

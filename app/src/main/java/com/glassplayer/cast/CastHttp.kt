package com.glassplayer.cast

import com.glassplayer.data.DebugLog

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** 投屏用的小 HTTP 工具：设备描述和 SOAP 都走它，超时短，失败不纠结。 */
internal object CastHttp {

    private const val UA = "GlassPlayer/1.0 UPnP/1.1 Android"

    fun get(url: String, timeoutMs: Int = 3000): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "*/*")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                DebugLog.record("cast", "HTTP $code · ${url.take(64)}")
                return null
            }
            readBody(conn.inputStream, conn).toString(Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }.onFailure {
        // 把真实异常类型暴露出来：明文被禁 / 连接拒绝 / 超时 / 不可达，处理方式完全不同
        DebugLog.record(
            "cast",
            "GET 失败 ${url.take(64)} · ${it.javaClass.simpleName}: ${it.message}",
        )
    }.getOrNull()

    /** SOAP 调用：返回响应体文本；设备回 500 也可能带 UPnPError，所以错误体也返回。 */
    fun soap(controlUrl: String, action: String, serviceType: String, body: String): String? =
        runCatching {
            val conn = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 6000
                doOutput = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPACTION", "\"$serviceType#$action\"")
            }
            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                stream?.let { readBody(it, conn) }?.toString(Charsets.UTF_8)
            } finally {
                conn.disconnect()
            }
        }.getOrNull()

    private fun readBody(raw: InputStream, conn: HttpURLConnection): ByteArray {
        val stream = if (conn.contentEncoding?.contains("gzip", true) == true) GZIPInputStream(raw) else raw
        return stream.use { input ->
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                bos.write(buf, 0, n)
            }
            bos.toByteArray()
        }
    }
}

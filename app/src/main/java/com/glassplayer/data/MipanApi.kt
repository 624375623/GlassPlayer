package com.glassplayer.data

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** 一条公开分享的网盘资源（来自觅盘聚合搜索）。 */
data class ResourceLink(
    val type: String,
    val url: String,
    val password: String,
    val note: String,
    val datetime: String,
    val source: String,
    val site: String = "觅盘",
)

data class ResourceSearchResult(
    val total: Int,
    val links: List<ResourceLink>,
)

/**
 * 觅盘（mipan.so）公开分享资源搜索。
 * 接口：GET /api/token 拿临时 token，再 POST /api/search（body {"kw": "关键词"}，头 X-Mipan-Token）。
 * 返回 data.merged_by_type，按网盘类型分桶（quark / baidu / aliyun / 115 ...）。
 */
object MipanApi {

    private const val BASE = "https://www.mipan.so"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    @Volatile
    private var token: String? = null

    @Volatile
    private var tokenExpireAt = 0L

    @Synchronized
    private fun token(): String {
        val cached = token
        if (cached != null && System.currentTimeMillis() < tokenExpireAt) return cached
        val conn = open("$BASE/api/token", "GET")
        val json = try {
            JSONObject(readBody(conn))
        } finally {
            runCatching { conn.disconnect() }
        }
        val fresh = json.optString("token")
        if (fresh.isBlank()) throw IllegalStateException("资源站 token 获取失败")
        val ttl = json.optLong("ttl", 7200L)
        token = fresh
        tokenExpireAt = System.currentTimeMillis() + ((ttl - 300L).coerceAtLeast(60L)) * 1000L
        return fresh
    }

    fun search(keyword: String): ResourceSearchResult {
        val body = JSONObject().put("kw", keyword).toString()
        val conn = open("$BASE/api/search", "POST")
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("X-Mipan-Token", token())
        conn.doOutput = true
        val json = try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            JSONObject(readBody(conn))
        } finally {
            // 搜索结果页会反复「加载更多」，不 disconnect 会持续堆积 keep-alive 连接
            runCatching { conn.disconnect() }
        }
        if (json.optInt("code", -1) != 0) {
            throw IllegalStateException(json.optString("message").ifBlank { "资源搜索失败" })
        }
        val data = json.optJSONObject("data")
        val total = data?.optInt("total", 0) ?: 0
        val buckets = data?.optJSONObject("merged_by_type")
        if (buckets == null) return ResourceSearchResult(total, emptyList())

        val out = ArrayList<ResourceLink>()
        val keys = buckets.keys()
        while (keys.hasNext()) {
            val type = keys.next()
            val arr = buckets.optJSONArray(type) ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val url = item.optString("url").trim()
                if (url.isBlank()) continue
                out += ResourceLink(
                    type = type,
                    url = url,
                    password = item.optString("password").trim(),
                    note = item.optString("note").trim().ifBlank { "未命名资源" },
                    datetime = item.optString("datetime"),
                    source = item.optString("source"),
                    site = "觅盘",
                )
            }
        }
        return ResourceSearchResult(total, out)
    }

    /**
     * 批量检测分享链接是否存活。返回 map：原始 url -> valid / invalid / unknown。
     * 接口 POST /api/check/links，body {"items":[{"url":..., "disk_type":"quark"}]}。
     */
    fun checkLinks(links: List<ResourceLink>): Map<String, String> {
        if (links.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        links.chunked(50).forEach { batch ->
            val items = org.json.JSONArray()
            batch.forEach { link ->
                items.put(JSONObject().put("url", link.url).put("disk_type", link.type))
            }
            val body = JSONObject().put("items", items).toString()
            val conn = open("$BASE/api/check/links", "POST")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("X-Mipan-Token", token())
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val json = JSONObject(readBody(conn))
            val results = json.optJSONArray("results") ?: return@forEach
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val url = item.optString("url")
                val normalized = item.optString("normalized_url")
                val state = when (item.optString("state").lowercase()) {
                    "valid", "locked", "ok", "alive" -> "valid"
                    "invalid", "bad", "dead", "expired", "deleted", "not_found", "failed" -> "invalid"
                    else -> "unknown"
                }
                if (url.isNotBlank()) out[url] = state
                if (normalized.isNotBlank()) out[dedupeKey(normalized)] = state
            }
            // 请求失败的条目按 unknown 处理，避免整批结果被误删
            batch.forEach { link ->
                if (link.url !in out) out[link.url] = "unknown"
            }
        }
        return out
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 12_000
        conn.readTimeout = 35_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/json")
        return conn
    }

    private fun readBody(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).optString("message") }.getOrNull()
            throw IllegalStateException("HTTP $code" + if (msg.isNullOrBlank()) "" else " · $msg")
        }
        return text
    }

    /** 链接去重键：同一条资源常同时给「带提取码」和「不带提取码」两个 URL。 */
    fun dedupeKey(url: String): String =
        url.substringBefore('#').substringBefore("?pwd=").trimEnd('/')

    /** 复制用文本：带提取码时把提取码一起给出。 */
    fun shareText(link: ResourceLink): String =
        if (link.password.isBlank()) link.url else "${link.url} 提取码：${link.password}"

    val typeLabel: (String) -> String = { type ->
        when (type) {
            "quark" -> "夸克"
            "baidu" -> "百度"
            "aliyun" -> "阿里"
            "115" -> "115"
            "uc" -> "UC"
            "xunlei" -> "迅雷"
            "tianyi" -> "天翼"
            "123" -> "123"
            "pikpak" -> "PikPak"
            "mobile" -> "移动"
            else -> type
        }
    }
}

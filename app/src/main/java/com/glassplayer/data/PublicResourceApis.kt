package com.glassplayer.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 资源搜索的数据来源，界面上用来区分结果出处。 */
enum class ResourceSource(val label: String) {
    MIPAN("觅盘"),
    GUGESO("谷哥搜"),
    KKSO("KK搜"),
    PANPANSOU("盘盘搜"),
    PANSOUDE("Pansou.de"),
}

/**
 * 四个公开分享站的接口封装：
 * - 谷哥搜 / KK网盘搜 / 盘盘搜：GET /api/other/web_search（SSE），返回的是加密路径，
 *   再调谷哥搜 /api/other/decrypt_urls 批量解密成 pan.quark.cn 链接。
 * - Pansou.de：POST /api/v1/search/stream（NDJSON），直接返回真实链接。
 */
object PublicResourceApis {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun search(source: ResourceSource, keyword: String): List<ResourceLink> = when (source) {
        ResourceSource.MIPAN -> MipanApi.search(keyword).links
        ResourceSource.GUGESO -> searchSite("https://gugeso.com", source.label, keyword)
        ResourceSource.KKSO -> searchSite("https://kkso.net", source.label, keyword)
        ResourceSource.PANPANSOU -> searchSite("https://panpansou.com", source.label, keyword)
        ResourceSource.PANSOUDE -> searchPansouDe(source.label, keyword)
    }

    /** 三个同源站点同时有「本地搜」和「全网搜」。网站默认展示本地搜，质量明显更高。 */
    private fun searchSite(host: String, site: String, keyword: String): List<ResourceLink> {
        val local = runCatching { searchLocalHtml(host, site, keyword) }.getOrElse { emptyList() }
        val remote = searchSse(host, site, keyword)
        return (local + remote).distinctBy { it.url }
    }

    /**
     * 解析站点服务端渲染的「本地搜」结果页：/s/<关键词>.html。
     * 页面里每条资源都有一个 copyText($event,'标题','链接','提取码') 调用，直接正则提取。
     */
    private fun searchLocalHtml(host: String, site: String, keyword: String): List<ResourceLink> {
        val url = "$host/s/${URLEncoder.encode(keyword, "UTF-8")}.html"
        val conn = open(url, "GET")
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        conn.setRequestProperty("Referer", "$host/")
        val html = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { conn.disconnect() }
        }
        if (html.isBlank()) return emptyList()

        val out = ArrayList<ResourceLink>()
        val regex = Regex("""copyText\([^,]*,'(.*?)','(https?://[^']+)','([^']*)'\)""", RegexOption.DOT_MATCHES_ALL)
        regex.findAll(html).forEach { m ->
            val title = unescapeHtml(m.groupValues[1]).replace("\\/", "/")
            val rawUrl = unescapeHtml(m.groupValues[2]).replace("\\/", "/")
            if (!rawUrl.startsWith("http")) return@forEach
            val type = typeFromUrl(rawUrl)
            if (type != "quark") return@forEach
            val start = m.range.first
            val blockStart = maxOf(
                html.lastIndexOf("<div class=\"g-result-item\"", start),
                html.lastIndexOf("<div class=\"item\"", start),
                start - 2000,
            )
            val block = html.substring(maxOf(blockStart, 0), start)
            val date = Regex("""data-date=["']([^"']+)["']""").findAll(block).lastOrNull()?.groupValues?.get(1)
                ?: Regex("""class=["']type time["']>([^<]+)""").findAll(block).lastOrNull()?.groupValues?.get(1)
                ?: ""
            var password = unescapeHtml(m.groupValues[3])
            if (password.isBlank()) {
                Regex("""[?&]pwd=([A-Za-z0-9]+)""").find(rawUrl)?.let { password = it.groupValues[1] }
            }
            out += ResourceLink(
                type = type,
                url = rawUrl,
                password = password,
                note = title.ifBlank { "未命名资源" },
                datetime = date,
                source = "本地资源",
                site = site,
            )
        }
        return out.distinctBy { it.url }
    }

    private fun typeFromUrl(url: String): String = when {
        url.contains("pan.quark.cn") -> "quark"
        url.contains("pan.baidu.com") -> "baidu"
        url.contains("aliyundrive.com") || url.contains("alipan.com") -> "aliyun"
        url.contains("cloud.189.cn") -> "tianyi"
        url.contains("xunlei.com") -> "xunlei"
        url.contains("123pan.com") -> "123"
        url.contains("115.com") -> "115"
        else -> "quark"
    }

    private fun unescapeHtml(s: String): String {
        var out = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        Regex("""&#(\d+);""").findAll(out).forEach { m ->
            out = out.replace(m.value, m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value)
        }
        return out.trim()
    }

    private fun searchSse(host: String, site: String, keyword: String): List<ResourceLink> {
        val url = "$host/api/other/web_search?title=${URLEncoder.encode(keyword, "UTF-8")}" +
            "&is_type=0&is_show=0&batch=1"
        val conn = open(url, "GET")
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.setRequestProperty("Referer", "$host/")

        val raw = ArrayList<RawItem>()
        try {
            conn.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.contains("[DONE]")) break
                    val json = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    if (json.optString("type") == "progress" || json.optString("type") == "cache_start") continue
                    val title = json.optString("title").ifBlank { json.optString("name") }
                    val link = json.optString("url")
                    if (title.isBlank() || link.isBlank()) continue
                    if (json.has("check_valid") && !json.optBoolean("check_valid", true)) continue
                    raw += RawItem(
                        title = title,
                        url = link,
                        password = json.optString("code").ifBlank { json.optString("password") },
                        datetime = json.optString("datetime").takeIf { it.isNotBlank() } ?: nowIso(),
                        type = json.optInt("is_type", 0),
                    )
                }
            }
        } catch (_: Throwable) {
            // 流被服务端提前掐断时保留已读到的结果
        } finally {
            runCatching { conn.disconnect() }
        }

        if (raw.isEmpty()) return emptyList()
        val decryptIndexes = raw.indexOfFirst { !it.url.startsWith("http") }
        if (decryptIndexes >= 0) {
            val encrypted = raw.filter { !it.url.startsWith("http") }.map { it.url }
            val decrypted = decryptUrls(encrypted)
            var cursor = 0
            raw.forEachIndexed { index, item ->
                if (!item.url.startsWith("http")) {
                    raw[index] = item.copy(url = decrypted.getOrNull(cursor).orEmpty())
                    cursor++
                }
            }
        }
        return raw.mapNotNull { item ->
            if (!item.url.startsWith("http")) return@mapNotNull null
            ResourceLink(
                type = panType(item.type),
                url = item.url,
                password = item.password,
                note = item.title,
                datetime = item.datetime,
                source = "sse",
                site = site,
            )
        }
    }

    private fun decryptUrls(encrypted: List<String>): List<String?> {
        if (encrypted.isEmpty()) return emptyList()
        val out = ArrayList<String?>()
        encrypted.chunked(50).forEach { batch ->
            // 文档明确要求表单编码 + 重复的 urls[] 字段；JSON 在部分部署会返回 400。
            val body = batch.joinToString("&") {
                "urls%5B%5D=" + URLEncoder.encode(it, "UTF-8")
            }
            val conn = open("https://gugeso.com/api/other/decrypt_urls", "POST")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            conn.setRequestProperty("Referer", "https://gugeso.com/")
            conn.doOutput = true
            val parsed = try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val text = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                JSONObject(text).optJSONObject("data")?.optJSONArray("urls")
            } catch (_: Throwable) {
                null
            } finally {
                runCatching { conn.disconnect() }
            }
            if (parsed == null) {
                repeat(batch.size) { out.add(null) }
            } else {
                for (i in batch.indices) out.add(parsed.optString(i).takeIf { it.isNotBlank() })
            }
        }
        return out
    }

    private fun searchPansouDe(site: String, keyword: String): List<ResourceLink> {
        val body = JSONObject()
            .put("keyword", keyword)
            .put("platform", "quark")
            .put("maxResults", 100)
            .toString()
        val conn = open("https://pansou.de/api/v1/search/stream", "POST")
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val out = ArrayList<ResourceLink>()
        try {
            conn.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    val event = json.optString("event")
                    if (event == "done") break
                    if (event != "candidate") continue
                    val results = json.optJSONArray("results") ?: continue
                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        val url = item.optString("url")
                        if (!url.startsWith("http")) continue
                        if (out.size >= 100) break
                        out += ResourceLink(
                            type = "quark",
                            url = url,
                            password = item.optString("password"),
                            note = item.optString("title").ifBlank { "未命名资源" },
                            datetime = item.optString("datetime").takeIf { it.isNotBlank() } ?: nowIso(),
                            source = item.optString("source"),
                            site = site,
                        )
                    }
                }
            }
        } catch (_: Throwable) {
            // 流中断时保留已拿到的候选
        } finally {
            runCatching { conn.disconnect() }
        }
        return out
    }

    private data class RawItem(
        val title: String,
        val url: String,
        val password: String,
        val datetime: String,
        val type: Int,
    )

    private fun panType(type: Int): String = when (type) {
        1 -> "baidu"
        2 -> "xunlei"
        3 -> "uc"
        else -> "quark"
    }

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

    private fun open(url: String, method: String): HttpURLConnection {
        val conn = (java.net.URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        }
        return conn
    }
}

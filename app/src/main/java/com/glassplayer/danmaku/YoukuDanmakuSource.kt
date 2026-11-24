package com.glassplayer.danmaku

import android.util.Base64
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * 优酷弹幕（直连，2026-11 实测可用）。
 *
 * 链路：
 * 1) 搜索 `search.youku.com/api/search`（站内搜索，免登录）；
 * 2) 剧集 `openapi.youku.com/v2/shows/videos.json`（公开 client_id，整季每集的 vid）；
 * 3) 时长 `openapi.youku.com/v2/videos/show.json`；
 * 4) cna：`log.mmstat.com/eg.js` 响应头 etag；
 * 5) 令牌 `acs.youku.com/h5/mtop.com.youku.aplatform.weakget/1.0/` 下发 `_m_h5_tk`；
 * 6) 弹幕 `acs.youku.com/h5/mopen.youku.danmu.list/1.0/`，POST + 双层 md5 签名，
 *    每 60 秒一段（`mat` 从 0 递增），返回 playat(毫秒) / content / propertis(pos,color)。
 */
object YoukuDanmakuSource {

    const val SOURCE = "youku"

    private const val CLIENT_ID = "53e6cc67237fc59a"
    private const val PARTNER_PACKAGE = "com.huawei.hwvplayer.youku"
    private const val APP_KEY = "24679788"
    private const val DANMU_API = "mopen.youku.danmu.list"
    private const val INNER_SALT = "MkmC9SoIw6xCkSKHhJ7b5D2r51kBiREr"

    private const val MAX_SEGMENTS = 120
    private const val PARALLEL = 4
    private const val TOTAL_LIMIT = 20_000

    private val showLink = Regex("youku\\.com/v_show/id_([A-Za-z0-9=+/]{8,})\\.html")
    private val tagStrip = Regex("<[^>]+>")
    private val yearRegex = Regex("((?:18|19|20)\\d{2})")

    /** 搜索：贴 v_show 链接时按单集导入，否则列站内结果。 */
    suspend fun search(keyword: String): List<DanmakuMatch> {
        showLink.find(keyword)?.let { hit ->
            return listOf(
                DanmakuMatch(SOURCE, "v:${hit.groupValues[1]}", "优酷视频", "链接导入 · 单集"),
            )
        }
        val query = URLEncoder.encode(keyword.trim(), "UTF-8")
        val url = "https://search.youku.com/api/search?keyword=$query&site=1&categories=0&ftype=0&ob=0&pg=1"
        val body = DanmakuNet.get(url, "https://www.youku.com/")
        val root = runCatching { JSONObject(body) }.getOrElse {
            throw IOException("优酷搜索返回了非 JSON 内容，稍后再试")
        }
        val items = root.optJSONArray("pageComponentList") ?: return emptyList()

        val out = LinkedHashMap<String, DanmakuMatch>()
        val scores = HashMap<String, Int>()
        for (i in 0 until items.length()) {
            val common = items.optJSONObject(i)?.optJSONObject("commonData") ?: continue
            val isYouku = common.optInt("isYouku", 0) == 1 || common.optInt("hasYouku", 0) == 1
            if (!isYouku) continue
            val showId = common.optString("showId").takeIf { it.isNotBlank() } ?: continue
            val title = tagStrip.replace(
                common.optJSONObject("titleDTO")?.optString("displayName").orEmpty(), "",
            ).trim()
            if (title.isBlank()) continue
            val key = "show:$showId"
            if (key in out) continue
            val feature = common.optString("feature").trim()
            // 只靠一两个字召回的（搜“功夫”出“精武英雄”）直接剔除，不占列表位置
            if (rankScore(title, keyword, feature) == Int.MIN_VALUE) continue
            out[key] = DanmakuMatch(
                source = SOURCE,
                id = key,
                title = title,
                // 专辑级结果不写“N 集”，避免被界面误当成单集网格；年份不含“集”，可保留
                subtitle = buildString {
                    append("优酷 · 点开选择集数")
                    yearRegex.find(feature)?.groupValues?.getOrNull(1)?.let {
                        append(" · ").append(it)
                    }
                },
            )
            scores[key] = rankScore(title, keyword, feature)
        }
        if (out.isEmpty()) return emptyList()
        // 优酷搜索里混着大量游戏解说 / 剪辑解说合集，按正片可能性重排，
        // 否则「功夫」会被「功夫鸡崽和平精英」这类 1000 集游戏合集挤在前面
        return out.keys
            .sortedByDescending { scores[it] ?: 0 }
            .mapNotNull { out[it] }
            .take(12)
    }

    /** 整季剧集（openapi 公开 client_id，一页 100 条）。 */
    suspend fun episodes(showId: String, onProgress: (String) -> Unit = {}): List<DanmakuMatch> {
        val out = ArrayList<DanmakuMatch>()
        for (page in 1..50) {
            onProgress("读取第 $page 页剧集 · 已 ${out.size} 集")
            val url = "https://openapi.youku.com/v2/shows/videos.json" +
                "?client_id=$CLIENT_ID&package=$PARTNER_PACKAGE&ext=show" +
                "&show_id=${URLEncoder.encode(showId, "UTF-8")}&page=$page&count=100"
            val body = DanmakuNet.get(url, "https://v.youku.com/")
            val videos = runCatching { JSONObject(body).optJSONArray("videos") }.getOrNull()
                ?: throw IOException("优酷剧集接口没有返回数据")
            if (videos.length() == 0) break

            val before = out.size
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val vid = v.optString("id").takeIf { it.isNotBlank() } ?: continue
                val stage = v.optString("stage").toIntOrNull()
                    ?: v.optInt("stage", 0).takeIf { it > 0 }
                val title = v.optString("title").trim()
                out.add(
                    DanmakuMatch(
                        source = SOURCE,
                        id = "v:$vid",
                        title = title.ifBlank { showId },
                        subtitle = if (stage != null && stage > 0) "第 $stage 集" else "第 ${out.size + 1} 集",
                    ),
                )
            }
            if (out.size == before || videos.length() < 100) break
        }
        return out
    }

    /** 单集弹幕：拿时长 → cna / 令牌 → 逐分钟签名 POST。 */
    suspend fun load(vid: String, onProgress: (String) -> Unit = {}): List<Danmaku> {
        onProgress("读取视频信息…")
        val duration = videoDuration(vid)
        val segments = if (duration > 0) {
            ((duration / 60.0).toInt() + 1).coerceAtMost(MAX_SEGMENTS)
        } else {
            24
        }

        onProgress("获取优酷弹幕凭证…")
        // cna 的 ETag 偶尔拿不到，退回到 mmstat 下发的 cna cookie
        val guid = fetchCna().ifBlank { DanmakuNet.cookieOf("cna").orEmpty() }
        val token = fetchToken()
        if (token.isNullOrBlank()) throw IOException("优酷没有下发弹幕令牌，稍后再试")

        onProgress("开始抓取 0/$segments 段…")
        var all = fetchAllSegments(vid, segments, guid, token) { message -> onProgress(message) }

        // 全部为空通常是缓存的令牌过期了：丢掉重取一次，别直接报“没有弹幕”
        if (all.isEmpty()) {
            onProgress("令牌可能已过期，重新获取…")
            DanmakuNet.forgetCookie("_m_h5_tk")
            DanmakuNet.forgetCookie("_m_h5_tk_enc")
            val freshGuid = fetchCna().ifBlank { guid }
            val freshToken = fetchToken()
            if (!freshToken.isNullOrBlank()) {
                all = fetchAllSegments(vid, segments, freshGuid, freshToken) { message ->
                    onProgress(message)
                }
            }
        }
        if (all.isEmpty()) throw IOException("这一集在优酷没有弹幕数据（已试 $segments 段）")
        return all.sortedBy { it.timeMs }.take(TOTAL_LIMIT)
    }

    private suspend fun fetchAllSegments(
        vid: String,
        segments: Int,
        guid: String,
        token: String,
        onProgress: (String) -> Unit,
    ): List<Danmaku> {
        var done = 0
        val all = ArrayList<Danmaku>(4000)
        for (group in (0 until segments).chunked(PARALLEL)) {
            val batch = coroutineScope {
                group.map { mat ->
                    async { runCatching { fetchSegment(vid, mat, guid, token) }.getOrDefault(emptyList()) }
                }.awaitAll()
            }
            batch.forEach { all.addAll(it) }
            done += group.size
            onProgress("已抓 $done/$segments 段 · ${all.size} 条")
        }
        return all
    }

    private suspend fun videoDuration(vid: String): Double {
        val url = "https://openapi.youku.com/v2/videos/show.json" +
            "?client_id=$CLIENT_ID&video_id=${URLEncoder.encode(vid, "UTF-8")}" +
            "&package=$PARTNER_PACKAGE&ext=show"
        val body = runCatching { DanmakuNet.get(url, "https://v.youku.com/") }.getOrNull() ?: return 0.0
        return runCatching { JSONObject(body).optDouble("duration", 0.0) }.getOrDefault(0.0)
    }

    /** cna guid 藏在 log.mmstat.com/eg.js 的 ETag 里。 */
    private suspend fun fetchCna(): String {
        repeat(2) {
            val (_, headers) = runCatching {
                DanmakuNet.getWithHeaders("https://log.mmstat.com/eg.js", "https://v.youku.com/")
            }.getOrNull() ?: return ""
            val etag = headers["etag"]?.trim()?.trim('"').orEmpty()
            if (etag.length >= 16) return etag
        }
        return ""
    }

    /**
     * mtop 的令牌在 `_m_h5_tk` cookie 里。
     *
     * mtop 首次请求经常只下发 `_m_h5_tk_enc` 或返回 FAIL_SYS_TOKEN_EMPTY，
     * 要再请求一次才拿到带时间戳的 `_m_h5_tk`，所以这里最多试 3 次。
     */
    private suspend fun fetchToken(): String? {
        repeat(3) {
            runCatching {
                DanmakuNet.getWithHeaders(
                    "https://acs.youku.com/h5/mtop.com.youku.aplatform.weakget/1.0/" +
                        "?jsv=2.5.1&appKey=$APP_KEY",
                    "https://v.youku.com/",
                )
            }
            val token = DanmakuNet.cookieOf("_m_h5_tk")
                ?.substringBefore('_')
                ?.takeIf { it.length >= 32 && it != "undefined" }
            if (token != null) return token
        }
        return null
    }

    private suspend fun fetchSegment(vid: String, mat: Int, guid: String, token: String): List<Danmaku> {
        val t = System.currentTimeMillis()
        val msg = JSONObject()
            .put("ctime", t)
            .put("ctype", 10004)
            .put("cver", "v1.0")
            .put("guid", guid)
            .put("mat", mat)
            .put("mcount", 1)
            .put("pid", 0)
            .put("sver", "3.1.0")
            .put("type", 1)
            .put("vid", vid)
        val msgStr = msg.toString()
        val msgB64 = Base64.encodeToString(msgStr.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val innerSign = md5(msgB64 + INNER_SALT)
        val data = msgStr.dropLast(1) +
            ",\"msg\":\"" + msgB64 + "\"" +
            ",\"sign\":\"" + innerSign + "\"}"
        val outerSign = md5(token + "&" + t + "&" + APP_KEY + "&" + data)

        val url = "https://acs.youku.com/h5/$DANMU_API/1.0/" +
            "?jsv=2.5.6&appKey=$APP_KEY&t=$t&sign=$outerSign&api=$DANMU_API" +
            "&v=1.0&type=originaljson&dataType=jsonp&timeout=20000&jsonpIncPrefix=utility"
        val form = "data=" + URLEncoder.encode(data, "UTF-8")
        val text = DanmakuNet.post(
            url,
            form,
            "https://v.youku.com",
            "application/x-www-form-urlencoded; charset=utf-8",
        )
        return parseSegment(text)
    }

    private fun parseSegment(text: String): List<Danmaku> {
        val trimmed = text.trim().removePrefix("utility(").removeSuffix(");").removeSuffix(")")
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
        val payload = root.optJSONObject("data") ?: return emptyList()
        val inner = payload.optString("result")
        if (inner.isBlank()) return emptyList()
        val body = runCatching { JSONObject(inner) }.getOrNull() ?: return emptyList()
        val arr = body.optJSONObject("data")?.optJSONArray("result") ?: return emptyList()

        val out = ArrayList<Danmaku>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val content = item.optString("content").trim()
            if (content.isEmpty()) continue
            val playAt = item.optString("playat").toLongOrNull() ?: item.optLong("playat", 0L)
            val props = runCatching { JSONObject(item.optString("propertis")) }.getOrNull()
            val pos = props?.optInt("pos", 3) ?: 3
            val color = props?.optInt("color", 0xFFFFFF) ?: 0xFFFFFF
            out.add(Danmaku(playAt.coerceAtLeast(0L), content, modeOf(pos), color))
        }
        return out
    }

    private fun modeOf(pos: Int): DanmakuMode = when (pos) {
        5 -> DanmakuMode.TOP
        4 -> DanmakuMode.BOTTOM
        else -> DanmakuMode.SCROLL
    }

    private fun md5(plain: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(plain.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { String.format(Locale.US, "%02x", it) }
    }

    private fun relevance(title: String, keyword: String): Float {
        val a = title.replace(Regex("\\s"), "")
        val b = keyword.replace(Regex("[\\s《》()（）·:：]"), "")
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a.contains(b)) return 1f
        val hit = b.count { ch -> a.contains(ch) }
        return hit.toFloat() / b.length
    }

    /**
     * 正片可能性打分。优酷搜索结果里既有正片剧集，也有游戏解说合集、
     * 剧情速看、《X》深度解读这类衍生内容，靠标题与 feature 里的品类词区分。
     */
    private fun rankScore(title: String, keyword: String, feature: String): Int {
        val a = title.replace(Regex("\\s"), "")
        val b = keyword.replace(Regex("[\\s《》()（）·:：]"), "")
        if (a.isEmpty() || b.isEmpty()) return 0
        var score = 0

        when {
            a == b -> score += 100
            a.startsWith(b) -> score += 55
            a.contains(b) -> score += 35
            else -> {
                val hit = b.count { ch -> a.contains(ch) }
                val ratio = hit.toFloat() / b.length
                // 只命中一两个字就召回的（搜“功夫”出“精武英雄”）直接剔除
                if (ratio < 0.66f) return Int.MIN_VALUE
                score += 5
            }
        }

        // 品类：影视剧 / 动漫 / 电影类是正片；游戏 / 搞笑 / 生活类基本都是剪辑合集
        val category = feature.split("·").getOrNull(1)?.trim().orEmpty()
        score += when (category) {
            "电视剧", "电影", "动漫", "综艺", "纪录片", "少儿", "体育" -> 20
            "游戏", "搞笑", "生活", "音乐", "资讯", "娱乐" -> -55
            else -> 0
        }
        if (feature.contains("集全")) score += 10

        // 标题里的衍生修饰
        if (Regex("(深度解读|超长幕后|幕后特辑|精彩分享|速看|解说|剪辑|盘点|花絮|预告|特辑)")
                .containsMatchIn(title)
        ) score -= 35
        if (title.length >= 26) score -= 20

        return score
    }
}

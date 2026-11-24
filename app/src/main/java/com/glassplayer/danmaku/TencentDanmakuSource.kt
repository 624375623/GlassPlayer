package com.glassplayer.danmaku

import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * 腾讯视频弹幕（单集直连）。
 *
 * 链路（实测可用）：
 * 1) 关键词 → 必应站内 RSS（site:v.qq.com）拿到剧集封面下某一集的播放页链接，链接里带 cid + vid；
 * 2) `m.v.qq.com/play.html?cid={cid}&vid={vid}` 页面里带 `"video_ids":[...]`，即整季所有集的 vid；
 * 3) `dm.video.qq.com/barrage/base/{vid}` 返回分段索引（每 30 秒一段，一集约 90 段）；
 * 4) `dm.video.qq.com/barrage/segment/{vid}/{段名}` 返回该段弹幕 JSON（barrage_list）。
 *
 * 弹幕按 **单集 vid** 分桶，不是封面 cid，所以要先定位到具体一集。
 */
object TencentDanmakuSource {

    const val SOURCE = "tencent"
    private const val REFERER = "https://v.qq.com/"
    private val coverLink = Regex("https?://v\\.qq\\.com/x/cover/([a-z0-9]+)/([a-z0-9]+)\\.html")
    private val itemTag = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
    private val titleTag = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
    private val linkTag = Regex("<link>(.*?)</link>", RegexOption.DOT_MATCHES_ALL)
    private val videoIds = Regex("\"video_ids\":\\[([^]]*)]", RegexOption.DOT_MATCHES_ALL)
    private val quoted = Regex("\"([a-z0-9]{8,12})\"")
    private val showTitle = Regex("\"title\":\"([^\"]*)\"")

    /** 单集总量上限：一集动辄 5 万条，只留热度最高的 1 万条，兼顾密度与渲染。 */
    private const val TOTAL_LIMIT = 10_000
    private const val MIN_PER_SEGMENT = 30
    private const val PARALLEL = 6
    private const val EPISODE_PARALLEL = 8
    /** 正片最短时长（秒）：预告/花絮通常几十秒，低于这个值当非正片。 */
    private const val MIN_EPISODE_SECONDS = 300.0

    /** 类型提示词：必应的站内检索对短标题经常“查不到”，叠一个类型词才命中，多组并行提高召回。 */
    private val SEARCH_HINTS = listOf("动漫", "电视剧", "在线观看", "电影", "第1集", "全集", "综艺", "纪录片")

    /**
     * 搜索：返回剧集（封面）级结果，id 用 `cid|vid` 表示，点开后再取整季集数。
     *
     * 单条查询不稳定（必应时灵时不灵，同一关键词两次结果可能不同），所以改成
     * 「多检索式并行 + 结果合并去重」：先用原词和类型词一起找，命中不足再补一批，
     * 这样「遮天」这类只有两个字、又不带类型词的标题也能落到结果。
     */
    suspend fun search(keyword: String, onProgress: (String) -> Unit = {}): List<DanmakuMatch> {
        val direct = coverLink.find(keyword)
        if (direct != null) {
            return listOf(
                DanmakuMatch(SOURCE, "${direct.groupValues[1]}|${direct.groupValues[2]}", "腾讯视频", "点开选择集数"),
            )
        }
        val variants = queryVariants(keyword)
        if (variants.isEmpty()) throw IOException("请输入要搜索的剧名")
        val sink = LinkedHashMap<String, DanmakuMatch>()
        val base = variants.first()

        onProgress("搜索中…")
        searchBatch(listOf(base, "$base 动漫", "$base 电视剧", "$base 在线观看"), sink)

        // 候选太少时再补一批：类型词全覆盖 + 关键词变体（去季数/去修饰后的简名）
        if (sink.size < 3) {
            onProgress("再找几个候选…")
            val more = SEARCH_HINTS.drop(3).map { "$base $it" } + variants.drop(1)
            searchBatch(more, sink)
        }

        // 必应偶发整批空结果，原词再试一次
        if (sink.isEmpty()) {
            onProgress("再试一次…")
            searchBatch(listOf(base), sink)
        }

        val matches = sink.values
            .filter { relevance(it.title, keyword) >= 0.34f }
            .sortedByDescending { relevance(it.title, keyword) }
            .take(12)
        if (matches.isEmpty()) {
            throw IOException("没找到腾讯视频的对应剧集，可以换个更短的关键词（比如只留剧名），或直接粘贴 v.qq.com 播放页链接")
        }
        return matches
    }

    /** 一批查询并行跑，结果按 cid 去重合并；单条失败不影响别的。 */
    private suspend fun searchBatch(queries: List<String>, sink: MutableMap<String, DanmakuMatch>) {
        val results = coroutineScope {
            queries.distinct().map { query ->
                async { runCatching { bingSearch(query) }.getOrDefault(emptyList()) }
            }.awaitAll()
        }
        results.forEach { list ->
            list.forEach { match ->
                val cid = match.id.substringBefore('|')
                if (cid !in sink) sink[cid] = match
            }
        }
    }

    private suspend fun bingSearch(keyword: String): List<DanmakuMatch> {
        val query = URLEncoder.encode("site:v.qq.com $keyword", "UTF-8")
        val rss = DanmakuNet.get(
            "https://www.bing.com/search?q=$query&format=rss&setlang=zh-CN",
        )
        val out = ArrayList<DanmakuMatch>(12)
        val seen = HashSet<String>()
        itemTag.findAll(rss).forEach { item ->
            val link = linkTag.find(item.groupValues[1])?.groupValues?.get(1) ?: return@forEach
            val hit = coverLink.find(link) ?: return@forEach
            val cid = hit.groupValues[1]
            val vid = hit.groupValues[2]
            if (!seen.add(cid)) return@forEach
            val rawTitle = titleTag.find(item.groupValues[1])?.groupValues?.get(1) ?: ""
            out.add(
                DanmakuMatch(
                    SOURCE,
                    "$cid|$vid",
                    cleanTitle(rawTitle),
                    "腾讯视频 · 点开选择集数",
                ),
            )
        }
        return out
    }

    /** 关键词变体：去掉书名号 / 季数 / 集数 / 清晰度等修饰，再退到文件名首段。 */
    private fun queryVariants(keyword: String): List<String> {
        val raw = keyword.trim()
        if (raw.isEmpty()) return emptyList()
        val cleaned = raw
            .replace(Regex("[《》【】()（）\\[\\]·:：,，.。!！?？/|]"), " ")
            .replace(Regex("第[一二三四五六七八九十百0-9]+季"), " ")
            .replace(Regex("season\\s*[0-9]+", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("第[一二三四五六七八九十百0-9]+集"), " ")
            .replace(Regex("(ep|e)\\s*[0-9]+"), " ")
            .replace(Regex("(高清|蓝光|1080p|2160p|4k|8k|完整版|未删减|国语|粤语|中字|双语)", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val out = ArrayList<String>(3)
        if (raw.isNotEmpty()) out.add(raw)
        if (cleaned.isNotEmpty() && cleaned != raw) out.add(cleaned)
        // 文件名常见「剧名.S01E02.中字」→ 取第一段
        val head = cleaned.split(' ', '-', '_', '.').firstOrNull { it.length >= 2 }?.trim().orEmpty()
        if (head.length >= 2 && head != cleaned && head != raw) out.add(head)
        return out.distinct().take(3)
    }

    private fun relevance(title: String, keyword: String): Float {
        val a = title.replace(Regex("\\s"), "")
        val b = keyword.replace(Regex("[\\s《》()（）·:：]"), "")
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a.contains(b)) return 1f
        val hit = b.count { ch -> a.contains(ch) }
        return hit.toFloat() / b.length
    }

    /** 用封面下任意一集的页面，取出整季所有集的 vid；再用 getinfo 的标题剔掉预告/花絮，按集号排序。 */
    suspend fun episodes(cid: String, vid: String, onProgress: (String) -> Unit = {}): List<DanmakuMatch> {
        val html = DanmakuNet.get("https://m.v.qq.com/play.html?cid=$cid&vid=$vid")
        val ids = videoIds.find(html)?.groupValues?.get(1)
            ?.let { quoted.findAll(it).map { m -> m.groupValues[1] }.toList() }
            ?.distinct()
            ?: emptyList()
        if (ids.size <= 1) return emptyList()

        val metas = ArrayList<EpisodeMeta>(ids.size)
        ids.chunked(EPISODE_PARALLEL).forEach { chunk ->
            val part = coroutineScope {
                chunk.map { each ->
                    async { runCatching { fetchMeta(each) }.getOrNull() }
                }.awaitAll()
            }
            part.filterNotNull().forEach { metas.add(it) }
            onProgress("读取集数 ${metas.size}/${ids.size}")
        }

        val episodes = metas
            .mapNotNull { meta -> episodeIndexOf(meta.title)?.let { index -> Triple(index, meta, true) } }
            .filter { (_, meta, _) -> meta.durationSec >= MIN_EPISODE_SECONDS && !isExtra(meta.title) }
            .distinctBy { (_, meta, _) -> meta.vid }
            .sortedBy { (index, _, _) -> index }
        if (episodes.isEmpty()) return emptyList()

        val showName = episodes.first().second.title
            .substringBeforeLast('_')
            .trim()
            .ifBlank { "腾讯视频" }
        return episodes.map { (index, meta, _) ->
            DanmakuMatch(
                SOURCE,
                meta.vid,
                showName,
                "第 ${index.toString().padStart(2, '0')} 集",
            )
        }
    }

    private data class EpisodeMeta(val vid: String, val title: String, val durationSec: Double)

    private suspend fun fetchMeta(vid: String): EpisodeMeta? {
        val body = DanmakuNet.get(
            "https://vv.video.qq.com/getinfo?vids=$vid&platform=101001&charge=0&otype=json",
        )
        val json = body.substringAfter("QZOutputJson=", "").trim().removeSuffix(";")
        if (json.isBlank()) return null
        val obj = JSONObject(json)
        val vi = obj.optJSONObject("vl")?.optJSONArray("vi")?.optJSONObject(0) ?: return null
        val title = vi.optString("ti").trim()
        if (title.isEmpty()) return null
        val duration = vi.optString("td").toDoubleOrNull() ?: 0.0
        return EpisodeMeta(vid, title, duration)
    }

    /** 「庆余年第二季_05」→ 5；「第12集」→ 12；拿不到就返回 null。 */
    private fun episodeIndexOf(title: String): Int? {
        Regex("_(\\d{1,4})$").find(title)?.let { match ->
            return match.groupValues[1].toIntOrNull()
        }
        Regex("第\\s*([0-9一二三四五六七八九十百]{1,4})\\s*集").find(title)?.let { match ->
            return chineseToInt(match.groupValues[1])
        }
        return null
    }

    private fun chineseToInt(raw: String): Int? {
        raw.toIntOrNull()?.let { return it }
        val digits = mapOf(
            '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
            '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        )
        if (raw == "十") return 10
        if (raw.length == 1) return digits[raw[0]]
        if (raw.length == 2) {
            if (raw[0] == '十') return 10 + (digits[raw[1]] ?: 0)
            if (raw[1] == '十') return (digits[raw[0]] ?: 0) * 10
        }
        if (raw.length == 3 && raw[1] == '十') {
            return (digits[raw[0]] ?: 0) * 10 + (digits[raw[2]] ?: 0)
        }
        return null
    }

    private fun isExtra(title: String): Boolean =
        listOf("预告", "花絮", "特辑", "片花", "彩蛋", "宣传", "采访", "幕后", "抢先", "直拍", "彩排", "开机")
            .any { title.contains(it) }

    suspend fun load(vid: String, onProgress: (String) -> Unit = {}): List<Danmaku> {
        val base = JSONObject(DanmakuNet.get("https://dm.video.qq.com/barrage/base/$vid", REFERER))
        val index = base.optJSONObject("segment_index") ?: throw IOException("该集没有弹幕数据")
        val segments = index.keys().asSequence()
            .mapNotNull { key -> index.optJSONObject(key)?.optString("segment_name")?.takeIf { it.isNotBlank() } }
            .sortedBy { seg ->
                seg.removePrefix("t/v1/").substringBefore('/').toLongOrNull() ?: 0L
            }
            .toList()
        if (segments.isEmpty()) throw IOException("该集还没有弹幕")

        // 总量按段数摊平，保证整集落在 1 万条附近
        val perSegment = (TOTAL_LIMIT / segments.size).coerceAtLeast(MIN_PER_SEGMENT)

        val all = ArrayList<Danmaku>(TOTAL_LIMIT.coerceAtMost(segments.size * 120))
        var done = 0
        segments.chunked(PARALLEL).forEach { chunk ->
            val parts = coroutineScope {
                chunk.map { seg ->
                    async { runCatching { fetchSegment(vid, seg, perSegment) }.getOrDefault(emptyList()) }
                }.awaitAll()
            }
            parts.forEach { all.addAll(it) }
            done += chunk.size
            onProgress("加载中 $done/${segments.size} 段 · 已 ${all.size} 条")
        }
        if (all.isEmpty()) throw IOException("弹幕是空的（可能是花絮、预告或未开放弹幕）")
        return all.sortedBy { it.timeMs }
    }

    private suspend fun fetchSegment(vid: String, segment: String, limit: Int): List<Danmaku> {
        val body = DanmakuNet.get(
            "https://dm.video.qq.com/barrage/segment/$vid/$segment",
            REFERER,
        )
        val arr = JSONObject(body).optJSONArray("barrage_list") ?: return emptyList()
        val scored = ArrayList<Pair<Double, Danmaku>>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val text = item.optString("content").trim()
            if (text.isEmpty()) continue
            val timeMs = item.optString("time_offset").toLongOrNull() ?: continue
            val color = parseColor(item.optString("content_style"))
            val score = item.optDouble("content_score", 0.0)
            scored.add(score to Danmaku(timeMs, text, DanmakuMode.SCROLL, color))
        }
        if (scored.size <= limit) {
            return scored.map { it.second }
        }
        return scored.sortedByDescending { it.first }
            .take(limit)
            .map { it.second }
            .sortedBy { it.timeMs }
    }

    private fun parseColor(style: String): Int {
        if (style.isBlank()) return 0xFFFFFF
        return runCatching {
            val hex = JSONObject(style).optString("color").removePrefix("#")
            if (hex.length == 6) hex.toInt(16) else 0xFFFFFF
        }.getOrDefault(0xFFFFFF)
    }

    private fun decodeHtml(raw: String): String = raw.replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    /** 「遮天_01_动漫_高清完整版视频在线观看_腾讯视频」→「遮天」，只留剧名那一段。 */
    private fun cleanTitle(raw: String): String {
        val plain = decodeHtml(raw).replace(Regex("<[^>]+>"), "")
            .replace("_腾讯视频", "")
            .replace(" - 腾讯视频", "")
            .trim()
        val parts = plain.split('_', '|', '-')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val first = parts.firstOrNull().orEmpty()
        return when {
            first.length >= 2 -> first
            parts.size >= 2 -> parts.take(2).joinToString(" · ")
            plain.isNotBlank() -> plain
            else -> "腾讯视频"
        }
    }
}

package com.glassplayer.danmaku

import java.io.IOException
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * 内置弹幕源。
 *
 * 目前只保留两条链路：
 * 1) B 站（公开页面接口，无需登录/凭证，实测可用）；
 * 2) 自建弹幕服务器（兼容弹弹play v2 协议，例如 danmu_api）。
 *
 * 腾讯/爱奇艺/优酷的搜索接口都有风控或签名，直连不可靠，统一走自建服务器那一条。
 */
object BiliDanmakuSource {

    const val SOURCE = "bili"
    private const val SEARCH_URL = "https://api.bilibili.com/x/web-interface/search/type"
    private const val SEASON_URL = "https://api.bilibili.com/pgc/view/web/season"
    private const val REFERER = "https://search.bilibili.com"

    /**
     * 只搜「正片」：番剧 / 国创 / 纪录片 + 影视。
     *
     * 之前只查 search_type=video，搜「凡人修仙传」全是二创剪辑；v3.9 虽然加了
     * media_bangumi，但投稿视频仍跟在后面，弹幕面板一打开先看到的还是二创。
     * 现在投稿视频整个不再混进结果——用户要的是正片，二创没有弹幕价值。
     *
     * 另外支持直接粘贴番剧链接（/bangumi/play/ss123 或 ep456），
     * 搜索接口被 B 站限流时还能手动指定。
     */
    suspend fun search(keyword: String): List<DanmakuMatch> {
        parseBangumiLink(keyword)?.let { return listOf(it) }
        var lastError: String? = null
        val media = ArrayList<DanmakuMatch>()
        for (type in listOf("media_bangumi", "media_ft")) {
            runCatching { searchMedia(keyword, type) }
                .onSuccess { media.addAll(it) }
                .onFailure { lastError = it.message }
        }
        val distinct = media.distinctBy { it.id }
        if (distinct.isEmpty() && lastError != null) {
            throw IOException("B 站正片检索失败：$lastError")
        }
        return distinct
    }

    /** 解析番剧播放页链接 / ss、ep 号，例如 https://www.bilibili.com/bangumi/play/ss28747 。 */
    private suspend fun parseBangumiLink(raw: String): DanmakuMatch? {
        val text = raw.trim()
        val season = Regex("(?i)(?:ss|season_id=)(\\d{2,9})").find(text)?.let {
            if (text.contains("ss", true) || text.contains("season", true)) it.groupValues[1] else null
        }
        if (season != null) {
            return DanmakuMatch(
                source = SOURCE,
                id = "season:$season",
                title = "番剧 $season",
                subtitle = "链接导入",
            )
        }
        val ep = Regex("(?i)(?:ep|ep_id=)(\\d{4,12})").find(text)?.groupValues?.getOrNull(1) ?: return null
        val body = DanmakuNet.get(
            "$SEASON_URL?ep_id=$ep",
            "https://www.bilibili.com/bangumi/play/ep$ep",
            biliCookie(),
        )
        val result = runCatching { JSONObject(body) }.getOrNull()?.optJSONObject("result") ?: return null
        val title = result.optString("title").ifBlank { "番剧" }
        val arr = result.optJSONArray("episodes") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("id", 0L).toString() != ep) continue
            val cid = o.optLong("cid", 0L)
            if (cid <= 0L) continue
            return DanmakuMatch(
                source = SOURCE,
                id = "cid:$cid",
                title = title,
                subtitle = "第 ${o.optString("title")} 集" +
                    o.optString("long_title").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
            )
        }
        return null
    }

    /**
     * B 站搜索需要一枚 buvid cookie 才稳定，否则偶发 -412 / 直接回空。
     * 这里从官方 finger/spi 接口现取，进程内缓存，失败也不影响主流程。
     */
    @Volatile
    private var cookieCache: String? = null

    private suspend fun biliCookie(): String? {
        cookieCache?.let { return it }
        return runCatching {
            val body = DanmakuNet.get("https://api.bilibili.com/x/frontend/finger/spi")
            val data = JSONObject(body).optJSONObject("data") ?: return@runCatching null
            val b3 = data.optString("b_3")
            val b4 = data.optString("b_4")
            buildString {
                if (b3.isNotBlank()) append("buvid3=").append(b3)
                if (b4.isNotBlank()) {
                    if (isNotEmpty()) append("; ")
                    append("buvid4=").append(b4)
                }
            }.ifBlank { null }?.also { cookieCache = it }
        }.getOrNull()
    }

    private suspend fun searchRaw(url: String, referer: String): JSONObject {
        var cookie = biliCookie()
        var lastCode = 0
        var lastMessage = ""
        for (attempt in 0 until 2) {
            val body = DanmakuNet.get(url, referer, cookie)
            val root = runCatching { JSONObject(body) }.getOrElse {
                throw IOException("B 站返回了非 JSON 内容（多半被风控了），稍后再试")
            }
            val code = root.optInt("code")
            if (code == 0) return root
            lastCode = code
            lastMessage = root.optString("message")
            if (attempt == 0 && (code == -412 || code == -352 || code == -799)) {
                // cookie 失效或被风控，换一枚再试
                cookieCache = null
                cookie = biliCookie()
                continue
            }
        }
        throw IOException("B 站搜索失败：code $lastCode $lastMessage".trim())
    }

    private suspend fun searchMedia(keyword: String, searchType: String): List<DanmakuMatch> {
        val enc = URLEncoder.encode(keyword, "UTF-8")
        val root = searchRaw("$SEARCH_URL?search_type=$searchType&keyword=$enc&page=1", REFERER)
        val arr = root.optJSONObject("data")?.optJSONArray("result") ?: return emptyList()
        val out = ArrayList<DanmakuMatch>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val seasonId = o.optLong("season_id", 0L)
            if (seasonId <= 0L) continue
            val typeName = o.optString("season_type_name")
                .ifBlank { if (searchType == "media_ft") "影视" else "番剧" }
            out.add(
                DanmakuMatch(
                    source = SOURCE,
                    id = "season:$seasonId",
                    title = stripTags(o.optString("title")),
                    // 专辑级结果不写“N 集”，避免被界面误当成单集网格平铺，点开再展开整季
                    subtitle = "$typeName · 点开选择集数",
                ),
            )
        }
        return out
    }

    /**
     * 展开整季集数：取 season 详情里的 episodes，过滤预告 / PV / 花絮，
     * 按集号排序去重，返回每集对应的 cid（拿 cid 才能取弹幕 XML）。
     */
    suspend fun episodes(seasonId: String): List<DanmakuMatch> {
        val body = DanmakuNet.get(
            "$SEASON_URL?season_id=$seasonId",
            "https://www.bilibili.com/bangumi/play/ss$seasonId",
            biliCookie(),
        )
        val root = JSONObject(body)
        if (root.optInt("code") != 0) {
            throw IOException("读取番剧集数失败：${root.optString("message").ifBlank { "code ${root.optInt("code")}" }}")
        }
        val season = root.optJSONObject("result") ?: throw IOException("这部番剧没有可用的集数")
        val seasonTitle = season.optString("title").ifBlank { "番剧" }
        val arr = season.optJSONArray("episodes") ?: throw IOException("这部番剧没有可用的集数")

        data class Raw(val index: Int, val cid: Long, val match: DanmakuMatch, val extra: Boolean)

        val raws = ArrayList<Raw>(arr.length())
        for (i in 0 until arr.length()) {
            val ep = arr.optJSONObject(i) ?: continue
            val cid = ep.optLong("cid", 0L)
            if (cid <= 0L) continue
            val badge = ep.optString("badge")
            val longTitle = ep.optString("long_title")
            val indexText = ep.optString("title")
            val index = indexText.toIntOrNull()
            val isExtra = badge.contains("预告") || isExtraTitle(longTitle) || isExtraTitle(indexText)
            raws.add(
                Raw(
                    index = index ?: Int.MAX_VALUE,
                    cid = cid,
                    match = DanmakuMatch(
                        source = SOURCE,
                        id = "cid:$cid",
                        title = seasonTitle,
                        subtitle = if (index != null) {
                            "第 $index 集" + if (longTitle.isNotBlank()) " · $longTitle" else ""
                        } else {
                            longTitle.ifBlank { indexText }
                        },
                    ),
                    extra = isExtra,
                ),
            )
        }
        // 预告和正片会共用同一个集号，先把预告剔掉再按集号去重
        val cleaned = raws.filter { !it.extra }
            .distinctBy { it.index }
            .sortedBy { it.index }
        // 去掉预告片后按正片顺序重新编号：列表第 1 个就是正片第 1 集，
        // 不沿用 B 站原始 index，避免预告/番外占号造成集数偏移
        return cleaned.mapIndexed { order, raw ->
            val longTitle = raw.match.subtitle
                .substringAfter(" · ", "")
                .takeIf { it.isNotBlank() }
                ?.let { " · $it" }
                .orEmpty()
            raw.match.copy(subtitle = "第 ${order + 1} 集$longTitle")
        }
    }

    private fun isExtraTitle(text: String): Boolean {
        if (text.isBlank()) return false
        return listOf(
            "预告", "PV", "pv", "花絮", "特辑", "片花", "彩蛋", "宣传", "采访", "幕后", "抢先", "直拍", "彩排", "开机",
        ).any { text.contains(it) }
    }

    /** 按 cid 直接取弹幕，番剧 / 剧集走这条。 */
    suspend fun loadByCid(cid: String, title: String): Pair<String, List<Danmaku>> {
        val xml = DanmakuNet.get(
            "https://comment.bilibili.com/$cid.xml",
            "https://www.bilibili.com/",
            biliCookie(),
        )
        if (!xml.contains("<d ") && !xml.contains("<i>")) {
            throw IOException("B 站返回的内容不是弹幕 XML（可能被限流，稍后重试）")
        }
        val items = DanmakuParser.parseXml(xml)
        if (items.isEmpty()) throw IOException("这一集还没有弹幕")
        return title to items
    }

    /** @return 弹幕列表 + 实际加载的分P标题 */
    suspend fun load(bvid: String): Pair<String, List<Danmaku>> {
        val pages = DanmakuNet.get(
            "https://api.bilibili.com/x/player/pagelist?bvid=$bvid",
            "https://www.bilibili.com/video/$bvid",
        )
        val root = JSONObject(pages)
        if (root.optInt("code") != 0) {
            throw IOException("获取分P失败：${root.optString("message")}")
        }
        val list = root.optJSONArray("data") ?: throw IOException("该视频没有可用分P")
        val first = list.optJSONObject(0) ?: throw IOException("该视频没有可用分P")
        val cid = first.optLong("cid")
        val part = first.optString("part")
        val xml = DanmakuNet.get(
            "https://comment.bilibili.com/$cid.xml",
            "https://www.bilibili.com/video/$bvid",
        )
        val items = DanmakuParser.parseXml(xml)
        if (items.isEmpty()) throw IOException("这个视频还没有弹幕")
        return part to items
    }

    private fun stripTags(raw: String): String =
        raw.replace("<em class=\"keyword\">", "")
            .replace("</em>", "")
            .replace(Regex("<[^>]+>"), "")
            .trim()

    private fun formatCount(n: Int): String = when {
        n >= 100_000_000 -> "${"%.1f".format(n / 100_000_000f)}亿播放"
        n >= 10_000 -> "${"%.1f".format(n / 10_000f)}万播放"
        else -> "$n 播放"
    }
}

/** 自建弹幕服务器（弹弹play v2 兼容协议，如 danmu_api / 自建代理）。 */
object ServerDanmakuSource {

    const val SOURCE = "server"

    suspend fun search(baseUrl: String, keyword: String): List<DanmakuMatch> {
        val base = baseUrl.trimEnd('/')
        if (base.isEmpty()) throw IOException("还没有配置弹幕服务器地址")
        val enc = URLEncoder.encode(keyword, "UTF-8")
        val body = DanmakuNet.get("$base/api/v2/search/episodes?anime=$enc")
        val root = JSONObject(body)
        if (root.optInt("errorCode", 0) != 0) {
            throw IOException("服务器返回：${root.optString("errorMessage")}")
        }
        val animes = root.optJSONArray("animes") ?: root.optJSONArray("anime") ?: JSONArray()
        val out = ArrayList<DanmakuMatch>(30)
        for (i in 0 until animes.length()) {
            val anime = animes.optJSONObject(i) ?: continue
            val title = anime.optString("animeTitle")
            val episodes = anime.optJSONArray("episodes") ?: continue
            for (j in 0 until episodes.length()) {
                val ep = episodes.optJSONObject(j) ?: continue
                val id = ep.optLong("episodeId", 0)
                if (id <= 0) continue
                out.add(
                    DanmakuMatch(
                        source = SOURCE,
                        id = id.toString(),
                        title = title,
                        subtitle = ep.optString("episodeTitle"),
                    ),
                )
            }
        }
        return out
    }

    suspend fun load(baseUrl: String, episodeId: String): List<Danmaku> {
        val base = baseUrl.trimEnd('/')
        val sep = if (base.contains("?")) "&" else "?"
        val body = DanmakuNet.get("$base/api/v2/comment/$episodeId${sep}withRelated=true")
        val items = DanmakuParser.parse(body)
        if (items.isEmpty()) throw IOException("这个弹幕库是空的")
        return items
    }
}

package com.glassplayer.danmaku

import java.io.IOException
import java.net.URLEncoder
import org.json.JSONObject

/**
 * 爱奇艺弹幕（直连抓取，无需登录 / 凭证）。
 *
 * 链路（2026-11 实测可用）：
 * 1) 关键词 → `search.video.iqiyi.com/o?if=html5` 返回专辑列表（albumTitle / albumLink / 集数）；
 * 2) 专辑页（桌面 UA）里 `rseat="...sub1_brief"` 的链接就是整季每一集的 `v_xxx`；
 * 3) 单集页（移动 UA）里的 `"qipuId"` 即该集 tvid（弹幕库编号）；
 * 4) `cmts.iqiyi.com/bullet/{tvid 倒数4-3位}/{tvid 倒数2位}/{tvid}_300_{序号}.z`
 *    每 5 分钟一包，zlib 压缩的 XML（bulletInfo: content / showTime / color / position）。
 */
object IqiyiDanmakuSource {

    const val SOURCE = "iqiyi"
    private const val SEARCH_URL = "https://search.video.iqiyi.com/o"
    private const val BULLET_URL = "https://cmts.iqiyi.com/bullet"
    private const val EPISODE_API = "https://pcw-api.iqiyi.com/albums/album/avlistinfo"
    private const val EPISODE_PAGE_SIZE = 100
    private const val MAX_EPISODE_PAGES = 30
    private const val REFERER = "https://www.iqiyi.com/"

    private const val MAX_PAGES = 24
    private const val TOTAL_LIMIT = 10_000

    /**
     * v_xxx → tvid 缓存。剧集接口直接下发 tvId，展开整季后加载那一集就不用
     * 再多请求一次移动页（长剧 1000+ 集时这一步很拖时间）。
     */
    private val tvidCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val pageAlbumId = Regex("albumId: *\"(\\d+)\"")
    private val episodeNumber = Regex("第\\s*(\\d+)\\s*[集话期]")
    private val extraKeywords = listOf(
        "预告", "PV", "pv", "花絮", "特辑", "片花", "彩蛋", "宣传", "采访", "幕后",
        "抢先", "直拍", "彩排", "开机", "先导",
    )

    private val pageLink = Regex("(?:https?://)?(?:www\\.|m\\.)?iqiyi\\.com/(v_[A-Za-z0-9]+)\\.html")
    private val albumLink = Regex("(?:https?://)?(?:www\\.|m\\.)?iqiyi\\.com/(a_[A-Za-z0-9_]+)\\.html")
    private val anchorTag = Regex("<a\\s[^>]*>", RegexOption.IGNORE_CASE)
    private val hrefVid = Regex("href=\"(?:https?:)?//www\\.iqiyi\\.com/(v_[A-Za-z0-9]+)\\.html\"", RegexOption.IGNORE_CASE)
    private val briefSeat = Regex("rseat=\"[^\"]*sub1_brief[^\"]*\"")
    private val titleAttr = Regex("title=\"([^\"]+)\"")
    // qipuId 实际是 9 位（如功夫 101621300），旧正则要求 12 位以上永远匹配不到
    private val loadInfoQipu = Regex("\"loadInfo\":\\{\"qipuId\":(\\d+)")
    private val qipuIdTag = Regex("\"qipuId\":(\\d{6,})")

    private val bulletTag = Regex("<bulletInfo>(.*?)</bulletInfo>", RegexOption.DOT_MATCHES_ALL)
    private val contentTag = Regex("<content>(.*?)</content>", RegexOption.DOT_MATCHES_ALL)
    private val showTimeTag = Regex("<showTime>(.*?)</showTime>")
    private val colorTag = Regex("<color>(.*?)</color>")
    private val positionTag = Regex("<position>(.*?)</position>")

    /** 搜索：直接粘贴 v_xxx / a_xxx 链接时走链接导入，否则走官方搜索接口。 */
    suspend fun search(keyword: String): List<DanmakuMatch> {
        pageLink.find(keyword)?.let { hit ->
            return listOf(DanmakuMatch(SOURCE, "v:${hit.groupValues[1]}", "爱奇艺视频", "链接导入"))
        }
        albumLink.find(keyword)?.let { hit ->
            return listOf(DanmakuMatch(SOURCE, "album:${hit.groupValues[1]}", "爱奇艺专辑", "链接导入"))
        }

        val query = URLEncoder.encode(keyword.trim(), "UTF-8")
        val body = DanmakuNet.get(
            "$SEARCH_URL?if=html5&key=$query&pageNum=1&pageSize=30",
            "https://so.iqiyi.com/",
        )
        val root = runCatching { JSONObject(body) }.getOrElse {
            throw IOException("爱奇艺搜索返回了非 JSON 内容，稍后再试")
        }
        val data = root.optJSONObject("data") ?: throw IOException("爱奇艺搜索没有返回数据")
        val docs = data.optJSONArray("docinfos") ?: return emptyList()

        // 按“像不像正片”打分排序：爱奇艺的搜索结果里混着大量自媒体剪辑短视频
        // （无集数、标题带 #话题），不排序的话会把正片/专辑挤出前 12 条
        val scored = ArrayList<Triple<Int, DanmakuMatch, String>>(docs.length())
        val loose = ArrayList<DanmakuMatch>(docs.length())
        for (i in 0 until docs.length()) {
            val doc = docs.optJSONObject(i) ?: continue
            val album = doc.optJSONObject("albumDocInfo") ?: continue
            val title = album.optString("albumTitle").trim()
            if (title.isBlank()) continue
            val link = album.optString("albumLink")
            // 专辑（a_，可展开整季）和单集（v_，如国语/粤语版电影）要分别处理；
            // 旧逻辑只认 a_，把功夫国语版、粤语版这类 v_ 单集电影整个漏掉了
            val albumHit = albumLink.find(link)
            val videoHit = pageLink.find(link)
            val total = album.optInt("itemTotalNumber", 0)
            val match: DanmakuMatch
            when {
                albumHit != null -> match = DanmakuMatch(
                    source = SOURCE,
                    id = "album:${albumHit.groupValues[1]}",
                    title = title,
                    // 专辑级结果不写“N 集”，避免被界面误当成单集网格平铺
                    subtitle = "爱奇艺 · 点开选择集数",
                )
                videoHit != null -> match = DanmakuMatch(
                    source = SOURCE,
                    id = "v:${videoHit.groupValues[1]}",
                    title = title,
                    // 单集（电影）直接加载，不展开；不同语言版本各自独立、弹幕各自独立
                    subtitle = "爱奇艺 · 单集",
                )
                else -> continue
            }
            if (loose.none { it.id == match.id }) loose.add(match)
            val score = rankScore(title, keyword, expandable = albumHit != null, episodeTotal = total)
            if (score <= UGC_SCORE) continue
            scored.add(Triple(score, match, match.id))
        }
        val ranked = scored
            .sortedByDescending { it.first }
            .distinctBy { it.third }
            .map { it.second }
            .take(12)
        // 全是短视频时不要返回空列表，宁可把原始顺序给出来
        if (ranked.isNotEmpty()) return ranked
        return loose.filter { relevance(it.title, keyword) >= 0.3f }.take(6)
    }

    /**
     * 专辑 → 整季每集的 v_xxx。
     *
     * 优先走官方剧集接口 `pcw-api.iqiyi.com/albums/album/avlistinfo`：
     * 它按真实集序分页返回 epsodelist，同时带 tvId（弹幕编号），
     * 长剧（航海王 1180 集）也能完整拿全。
     * 专辑页 HTML 只在拿 albumId 时读一次，接口不可用时退回解析页面链接。
     */
    suspend fun episodes(album: String, onProgress: (String) -> Unit = {}): List<DanmakuMatch> {
        onProgress("读取剧集…")
        val html = DanmakuNet.get("https://www.iqiyi.com/$album.html", REFERER, desktop = true)

        val albumId = pageAlbumId.find(html)?.groupValues?.getOrNull(1)
        if (albumId != null) {
            val list = runCatching { fetchEpisodeList(albumId, onProgress) }.getOrDefault(emptyList())
            if (list.isNotEmpty()) {
                // 第一集的名字形如「航海王 第1集」，剥掉集号部分当剧名
                val showName = list.first().title.replace(episodeNumber, "").trim()
                return list.map { ep ->
                    DanmakuMatch(
                        source = SOURCE,
                        id = "v:${ep.vid}",
                        title = showName.ifBlank { "爱奇艺" },
                        subtitle = "第 ${ep.episode} 集",
                    )
                }
            }
        }
        // 兜底：解析专辑页里的集链接
        return legacyEpisodes(html)
    }

    private data class IqEpisode(val vid: String, val episode: Int, val title: String)

    /** 分页拉完整剧集列表（一页 100 条，最多 30 页 = 3000 集）。 */
    private suspend fun fetchEpisodeList(
        albumId: String,
        onProgress: (String) -> Unit,
    ): List<IqEpisode> {
        val out = ArrayList<IqEpisode>(200)
        val seen = LinkedHashSet<String>()
        for (page in 1..MAX_EPISODE_PAGES) {
            onProgress("读取第 $page 页剧集 · 已 ${out.size} 集")
            val url = "$EPISODE_API?aid=$albumId&page=$page&size=$EPISODE_PAGE_SIZE"
            val body = runCatching { DanmakuNet.get(url, REFERER) }.getOrNull() ?: break
            val root = runCatching { JSONObject(body) }.getOrNull() ?: break
            if (root.optString("code") != "A00000") break
            val eps = root.optJSONObject("data")?.optJSONArray("epsodelist") ?: break
            if (eps.length() == 0) break

            val before = out.size
            for (i in 0 until eps.length()) {
                val ep = eps.optJSONObject(i) ?: continue
                val vid = pageLink.find(ep.optString("playUrl"))?.groupValues?.getOrNull(1)
                    ?: continue
                if (!seen.add(vid)) continue
                val name = ep.optString("name").trim()
                // 预告 / 花絮不占正片集号，扔掉避免整季编号偏移
                if (isExtraTitle(name)) continue
                val tvId = ep.optLong("tvId", 0L).takeIf { it > 0 }?.toString()
                if (tvId != null) tvidCache[vid] = tvId
                // 集号优先取标题里的“第N集”，取不到就用顺序号
                val num = episodeNumber.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                out.add(IqEpisode(vid, num ?: (out.size + 1), name))
            }
            if (out.size == before || eps.length() < EPISODE_PAGE_SIZE) break
        }
        // 集号可能重复（多语言版本行），按出现顺序重排保证连续
        return out
    }

    /** 旧链路：从专辑页链接里凑集数（接口不可用时的兜底）。 */
    private fun legacyEpisodes(html: String): List<DanmakuMatch> {
        val anchors = anchorTag.findAll(html).toList()
        val brief = LinkedHashSet<String>()
        val titles = HashMap<String, String>()
        anchors.forEach { anchor ->
            val tag = anchor.value
            if (!briefSeat.containsMatchIn(tag)) return@forEach
            val vid = hrefVid.find(tag)?.groupValues?.getOrNull(1) ?: return@forEach
            if (brief.add(vid)) {
                titleAttr.find(tag)?.groupValues?.getOrNull(1)
                    ?.takeIf { it.isNotBlank() && it != "null" }
                    ?.let { titles[vid] = it }
            }
        }

        val list = if (brief.size >= 2) {
            brief.toList()
        } else {
            // 兜底：没有 sub1_brief 标记的页面，只保留带标题、且不是预告/推荐位的链接
            val skip = listOf("prevue", "listhotplay", "toutu", "biaoti", "topplay")
            val fallback = LinkedHashSet<String>()
            anchors.forEach { anchor ->
                val tag = anchor.value
                if (skip.any { tag.contains(it) }) return@forEach
                val vid = hrefVid.find(tag)?.groupValues?.getOrNull(1) ?: return@forEach
                if (titleAttr.containsMatchIn(tag)) fallback.add(vid)
            }
            // 长剧页面只给前几十条链接，上限放宽到 500，别把整季卡掉
            fallback.toList().takeIf { it.size >= 2 } ?: emptyList()
        }
        if (list.isEmpty()) return emptyList()

        val showName = titles.values.firstOrNull()?.substringBefore('：')?.trim()
            ?: titles.values.firstOrNull()?.trim().orEmpty()
        return list.take(500).mapIndexed { index, vid ->
            DanmakuMatch(
                source = SOURCE,
                id = "v:$vid",
                title = showName.ifBlank { "爱奇艺" },
                subtitle = "第 ${index + 1} 集",
            )
        }
    }

    /** 单集 v_xxx → tvid → 逐包抓 .z 弹幕。 */
    suspend fun load(v_xxx: String, onProgress: (String) -> Unit = {}): List<Danmaku> {
        onProgress("定位弹幕库…")
        // 展开整季时接口已经下发过 tvId，命中缓存就省掉一次网页请求
        val tvid = tvidCache[v_xxx] ?: resolveTvid(v_xxx)
        if (tvid.isNullOrBlank()) throw IOException("这一集没找到爱奇艺的弹幕编号（可能是预告或花絮）")
        tvidCache[v_xxx] = tvid

        val dirA = tvid.substring(tvid.length - 4, tvid.length - 2)
        val dirB = tvid.substring(tvid.length - 2)
        val all = ArrayList<Danmaku>(2000)
        var emptyRun = 0
        for (page in 1..MAX_PAGES) {
            onProgress("加载第 $page 包 · 已 ${all.size} 条")
            val items = runCatching { fetchPack(tvid, dirA, dirB, page) }.getOrNull()
            if (items == null || items.isEmpty()) {
                // 连续两包空就认为到底了（有些集中间会缺包）
                emptyRun++
                if (emptyRun >= 2) break
                continue
            }
            emptyRun = 0
            all.addAll(items)
            if (all.size >= TOTAL_LIMIT) break
        }
        if (all.isEmpty()) throw IOException("这一集没有弹幕（也可能爱奇艺没开放该集的弹幕）")
        return all.sortedBy { it.timeMs }.take(TOTAL_LIMIT)
    }

    private suspend fun resolveTvid(v_xxx: String): String? {
        val html = DanmakuNet.get("https://m.iqiyi.com/$v_xxx.html", REFERER)
        // 优先取当前视频 loadInfo 里的 qipuId，避免拿到页面推荐位的其他剧集 id
        return loadInfoQipu.find(html)?.groupValues?.getOrNull(1)
            ?: qipuIdTag.findAll(html).map { it.groupValues[1] }.firstOrNull()
    }

    private suspend fun fetchPack(
        tvid: String,
        dirA: String,
        dirB: String,
        page: Int,
    ): List<Danmaku> {
        val url = "$BULLET_URL/$dirA/$dirB/${tvid}_300_$page.z"
        val xml = DanmakuNet.get(url, "https://www.iqiyi.com/")
        return parseXml(xml)
    }

    private fun parseXml(xml: String): List<Danmaku> {
        if (!xml.contains("<bulletInfo>")) return emptyList()
        val out = ArrayList<Danmaku>(256)
        bulletTag.findAll(xml).forEach { match ->
            val block = match.groupValues[1]
            val content = contentTag.find(block)?.groupValues?.getOrNull(1)?.let(::unescape)?.trim().orEmpty()
            if (content.isEmpty()) return@forEach
            val seconds = showTimeTag.find(block)?.groupValues?.getOrNull(1)?.trim()?.toDoubleOrNull()
                ?: return@forEach
            val color = colorTag.find(block)?.groupValues?.getOrNull(1)
                ?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16) ?: 0xFFFFFF
            val mode = when (positionTag.find(block)?.groupValues?.getOrNull(1)?.trim()) {
                "1" -> DanmakuMode.TOP
                "2" -> DanmakuMode.BOTTOM
                else -> DanmakuMode.SCROLL
            }
            out.add(Danmaku((seconds * 1000).toLong(), content, mode, color))
        }
        return out
    }

    private fun unescape(raw: String): String = raw
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

    /** 预告 / 花絮 / PV 之类的附加内容，不占正片集号。 */
    private fun isExtraTitle(name: String): Boolean =
        extraKeywords.any { k -> name.contains(k, ignoreCase = true) }

    private fun relevance(title: String, keyword: String): Float {
        val a = title.replace(Regex("\\s"), "")
        val b = keyword.replace(Regex("[\\s《》()（）·:：]"), "")
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a.contains(b)) return 1f
        val hit = b.count { ch -> a.contains(ch) }
        return hit.toFloat() / b.length
    }

    /**
     * 结果优先级打分。分档比细调数值重要：正片专辑 > 单集正片 > 衍生内容 > UGC 剪辑。
     * 返回 <= [UGC_SCORE] 的条目会被直接丢弃（自媒体剪辑短视频对弹幕没意义）。
     */
    private fun rankScore(title: String, keyword: String, expandable: Boolean, episodeTotal: Int): Int {
        val a = title.replace(Regex("\\s"), "")
        val b = keyword.replace(Regex("[\\s《》()（）·:：]"), "")
        if (a.isEmpty() || b.isEmpty()) return UGC_SCORE

        // 0) 没有集数信息的单集就是自媒体剪辑（正片单集如电影一定带 itemTotalNumber=1）
        if (!expandable && episodeTotal <= 0) return UGC_SCORE

        var score = 0

        // 1) 标题相关度：完全同名 > 以关键词开头 > 包含关键词 > 逐字命中
        when {
            a == b -> score += 100
            a.startsWith(b) -> score += 60
            a.contains(b) -> score += 40
            else -> {
                val hit = b.count { ch -> a.contains(ch) }
                val ratio = hit.toFloat() / b.length
                // 逐字命中太散的（搜“功夫”命中“精武英雄”只有 1/2）不算正片；
                // 专辑本身是完整剧集，门槛放宽一点
                if (ratio < (if (expandable) 0.5f else 0.75f)) return UGC_SCORE
                score += 10
            }
        }

        // 2) 能展开整季的专辑优先；有总集数的单集次之
        score += if (expandable) 45 else 20

        // 3) 自媒体剪辑特征：带话题标签 / 标题过长
        val hashtag = title.count { it == '#' }
        if (hashtag >= 2) score -= 120
        else if (hashtag == 1) score -= 70
        if (title.length >= 26) score -= 40

        // 4) 明确的衍生修饰词，排在正片之后
        if (Regex("(动态漫画|解说|盘点|速看|剪辑|合集篇|花絮|预告|特辑|幕后|深度解读)")
                .containsMatchIn(title)
        ) score -= 30

        return score
    }

    /** 低于等于这个分数视为 UGC 剪辑，不作为弹幕候选。 */
    private val UGC_SCORE = 0
}

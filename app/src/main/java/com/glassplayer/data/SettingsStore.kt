package com.glassplayer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 网盘视频的元信息，收藏/播放记录要靠它在本库里还原成一条条目。 */
data class CloudVideo(
    val fid: String,
    val name: String,
    val size: Long,
    val updatedAt: Long,
    val parentFid: String,
    val parentName: String,
    val durationMs: Long = 0,
    val isDir: Boolean = false,
) {
    val key: String get() = "quark://$fid"
}

/** 字幕库里的一条：SAF 文档 URI + 显示名 + 所属文件夹（相对导入目录）。 */
data class SubtitleFile(
    val uri: String,
    val name: String,
    val folder: String = "",
)

/** 导入的一个字幕文件夹：保留 tree URI，文件列表是扫描时的快照。 */
data class SubtitleFolder(
    val treeUri: String,
    val name: String,
    val addedAt: Long,
    val files: List<SubtitleFile>,
)

/**
 * 收藏的夸克分享文件夹（「文件夹盘」）。
 *
 * 只存链接与提取码，目录内容每次打开时实时读取：对方往文件夹里加文件后，
 * 这里直接就能看到新内容，不需要重新换分享链接。
 * syncedKeys 记录已经转存过的文件（`文件名|大小`），用来算「新增了哪些」。
 */
data class QuarkFolder(
    val url: String,
    val code: String,
    val name: String,
    val addedAt: Long,
    val syncedKeys: Set<String>,
) {
    val key: String get() = url
}

/** Lightweight persistent settings and playback memory. */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("glass_player_prefs", Context.MODE_PRIVATE)

    var themeMode: Int
        get() = prefs.getInt("theme_mode", 0)
        set(value) = prefs.edit().putInt("theme_mode", value).apply()

    /** 夸克网盘登录 Cookie（网页端会话）。null = 未登录。 */
    var quarkCookie: String?
        get() = prefs.getString("quark_cookie", null)
        set(value) = prefs.edit().putString("quark_cookie", value).apply()

    /** 夸克账号昵称，仅用于界面显示。 */
    var quarkUserName: String?
        get() = prefs.getString("quark_user_name", null)
        set(value) = prefs.edit().putString("quark_user_name", value).apply()

    /** 网盘视频「边下边播」：播放过的片段留在本地缓存，拖动回看不再重新下载。 */
    var quarkCachePlayback: Boolean
        get() = prefs.getBoolean("quark_cache_playback", true)
        set(value) = prefs.edit().putBoolean("quark_cache_playback", value).apply()

    /** 自定义背景图（内容 URI，持久化授权）。null = 使用内置渐变壁纸。 */
    var backgroundUri: String?
        get() = prefs.getString("background_uri", null)
        set(value) = prefs.edit().putString("background_uri", value).apply()

    var defaultSpeed: Float
        get() = prefs.getFloat("default_speed", 1.0f)
        set(value) = prefs.edit().putFloat("default_speed", value).apply()

    var resumeEnabled: Boolean
        get() = prefs.getBoolean("resume", true)
        set(value) = prefs.edit().putBoolean("resume", value).apply()

    var gesturesEnabled: Boolean
        get() = prefs.getBoolean("gestures", true)
        set(value) = prefs.edit().putBoolean("gestures", value).apply()

    var decoderFallback: Boolean
        get() = prefs.getBoolean("decoder_fallback", true)
        set(value) = prefs.edit().putBoolean("decoder_fallback", value).apply()

    var subtitleScale: Float
        get() = prefs.getFloat("subtitle_scale", 1.0f)
        set(value) = prefs.edit().putFloat("subtitle_scale", value).apply()

    var subtitleDelayMs: Long
        get() = prefs.getLong("subtitle_delay", 0L)
        set(value) = prefs.edit().putLong("subtitle_delay", value).apply()

    var subtitleAlpha: Float
        get() = prefs.getFloat("subtitle_alpha", 1.0f)
        set(value) = prefs.edit().putFloat("subtitle_alpha", value).apply()

    /**
     * 字幕垂直位置：
     * - 0 = 默认贴底（距底 12dp，基本贴着手机底部）
     * - 正数向上抬，1 = 贴顶
     * - 负数继续向下压，最小 -0.3（约向下 29dp，可让字幕压到屏幕边缘甚至切掉一点）
     * 以比例而不是固定 dp 存储，横竖屏切换、不同屏幕高度下不会跑到画面外。
     */
    var subtitlePosition: Float
        get() = prefs.getFloat("subtitle_position", 0f)
        set(value) = prefs.edit().putFloat("subtitle_position", value).apply()

    /**
     * 双语字幕语言过滤：空字符串 = 全部，否则存 SubtitleLanguage.name
     * （CHINESE / JAPANESE / ENGLISH）。切换到没有该语言的字幕时会自动回到全部。
     */
    var subtitleLanguageFilter: String
        get() = prefs.getString("subtitle_language_filter", "") ?: ""
        set(value) = prefs.edit().putString("subtitle_language_filter", value).apply()

    var autoOrientation: Boolean
        get() = prefs.getBoolean("auto_orientation", true)
        set(value) = prefs.edit().putBoolean("auto_orientation", value).apply()

    var sortMode: Int
        get() = prefs.getInt("sort_mode", 0)
        set(value) = prefs.edit().putInt("sort_mode", value).apply()

    /** 排序方向：false = 从长/大到短/小（默认），true = 反向。 */
    var sortAscending: Boolean
        get() = prefs.getBoolean("sort_ascending", false)
        set(value) = prefs.edit().putBoolean("sort_ascending", value).apply()

    /** 媒体库布局：false = 列表，true = 两列网格。 */
    var gridLayout: Boolean
        get() = prefs.getBoolean("grid_layout", false)
        set(value) = prefs.edit().putBoolean("grid_layout", value).apply()

    var autoPip: Boolean
        get() = prefs.getBoolean("auto_pip", false)
        set(value) = prefs.edit().putBoolean("auto_pip", value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", true)
        set(value) = prefs.edit().putBoolean("keep_screen_on", value).apply()

    var fpsMeter: Boolean
        get() = prefs.getBoolean("fps_meter", false)
        set(value) = prefs.edit().putBoolean("fps_meter", value).apply()

    /**
     * 播放画面比例模式：
     * 0 = 适应屏幕（按视频自身比例），1 = 缩放铺满（裁切），2 = 拉伸铺满，
     * 3 = 固定宽度，4 = 自定义宽高比。
     */
    var playerAspectMode: Int
        get() = prefs.getInt("player_aspect_mode", 0)
        set(value) = prefs.edit().putInt("player_aspect_mode", value).apply()

    /** 播放页半屏弹层的不透明度，0.35 ~ 1.0。 */
    var playerSheetOpacity: Float
        get() = prefs.getFloat("player_sheet_opacity", 0.72f)
        set(value) = prefs.edit().putFloat("player_sheet_opacity", value.coerceIn(0.35f, 1f)).apply()

    /**
     * 启动后进入哪个导航页：0 全部 / 1 文件（文件夹）/ 2 收藏 / 3 记录 / 4 网盘 / 5 资源。
     * 只在启动时读一次，设置里改完标注「下次启动生效」。
     */
    var startTab: Int
        get() = prefs.getInt("start_tab", 1).coerceIn(0, 5)
        set(value) = prefs.edit().putInt("start_tab", value.coerceIn(0, 5)).apply()

    /**
     * 视频卡片（视频框）底板的不透明度，0.15 ~ 1.0，默认 1.0 = 和原来完全一致。
     * 只作用于卡片底色，标题 / 时长 / 副标题文字的透明度不受影响。
     */
    var cardBackgroundAlpha: Float
        get() = prefs.getFloat("card_background_alpha", 1f)
        set(value) = prefs.edit().putFloat("card_background_alpha", value.coerceIn(0.15f, 1f)).apply()

    /**
     * 实验性：强制应用运行在固定刷新率。0 = 跟随系统，1 = 90Hz，2 = 120Hz。
     * 只挑设备真实支持的模式，找不到就保持跟随系统。
     */
    var refreshRateMode: Int
        get() = prefs.getInt("refresh_rate_mode", 0).coerceIn(0, 2)
        set(value) = prefs.edit().putInt("refresh_rate_mode", value.coerceIn(0, 2)).apply()

    /** 按单个视频记住的画面比例，格式 "mode|w|h"；没设置过返回 null。 */
    fun videoAspectOf(key: String): Triple<Int, Int, Int>? {
        if (key.isEmpty()) return null
        val raw = prefs.getString("va_" + key.hashCode().toUInt().toString(16), null) ?: return null
        val parts = raw.split('|')
        if (parts.size != 3) return null
        val mode = parts[0].toIntOrNull() ?: return null
        val w = parts[1].toIntOrNull() ?: return null
        val h = parts[2].toIntOrNull() ?: return null
        return Triple(mode, w, h)
    }

    /** 只给当前这个视频记住比例，其他视频不受影响。 */
    fun saveVideoAspect(key: String, mode: Int, w: Int, h: Int) {
        if (key.isEmpty()) return
        prefs.edit()
            .putString(
                "va_" + key.hashCode().toUInt().toString(16),
                "$mode|${w.coerceIn(1, 100)}|${h.coerceIn(1, 100)}",
            )
            .apply()
    }

    fun clearVideoAspect(key: String) {
        if (key.isEmpty()) return
        prefs.edit().remove("va_" + key.hashCode().toUInt().toString(16)).apply()
    }

    var playerAspectW: Int
        get() = prefs.getInt("player_aspect_w", 16)
        set(value) {
            prefs.edit().putInt("player_aspect_w", value.coerceIn(1, 100)).apply()
        }

    var playerAspectH: Int
        get() = prefs.getInt("player_aspect_h", 9)
        set(value) {
            prefs.edit().putInt("player_aspect_h", value.coerceIn(1, 100)).apply()
        }

    /**
     * 底部导航的液态玻璃动效。
     * true = 原版玻璃折射导航（默认；个别机型滑出时会露出采样方块，可在设置里关掉）；
     * false = 本地绘制胶囊导航（任何机型都不会出现色块）。
     */
    var liquidNavGlass: Boolean
        get() = prefs.getBoolean("liquid_nav_glass", true)
        set(value) = prefs.edit().putBoolean("liquid_nav_glass", value).apply()

    /** 低于该时长的视频不显示（秒），0 = 不过滤。 */
    var minDurationSec: Int
        get() = prefs.getInt("min_duration_sec", 0)
        set(value) = prefs.edit().putInt("min_duration_sec", value).apply()

    /** 用户手动隐藏的文件夹（按 MediaStore 目录名）。 */
    var hiddenFolders: Set<String>
        get() = prefs.getStringSet("hidden_folders", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("hidden_folders", value).apply()

    /** 离开应用后是否继续播放；默认关闭。 */
    var secureScreen: Boolean
        get() = prefs.getBoolean("secure_screen", false)
        set(value) = prefs.edit().putBoolean("secure_screen", value).apply()

    var backgroundPlay: Boolean
        get() = prefs.getBoolean("background_play", false)
        set(value) = prefs.edit().putBoolean("background_play", value).apply()

    /** 弹幕开关。 */
    var danmakuEnabled: Boolean
        get() = prefs.getBoolean("danmaku_enabled", true)
        set(value) = prefs.edit().putBoolean("danmaku_enabled", value).apply()

    /** 弹幕字体颜色，默认白色；使用 ARGB 低 24 位 RGB。 */
    var danmakuColor: Int
        get() = prefs.getInt("danmaku_color", 0xFFFFFF)
        set(value) = prefs.edit().putInt("danmaku_color", value).apply()

    /** 弹幕不透明度，0.15 ~ 1.0。 */
    var danmakuOpacity: Float
        get() = prefs.getFloat("danmaku_opacity", 0.9f)
        set(value) = prefs.edit().putFloat("danmaku_opacity", value).apply()

    /** 弹幕字号倍率，0.7 ~ 1.6。 */
    var danmakuScale: Float
        get() = prefs.getFloat("danmaku_scale", 1.0f)
        set(value) = prefs.edit().putFloat("danmaku_scale", value).apply()

    /** 弹幕整体偏移（毫秒），正值表示弹幕延后出现。 */
    var danmakuOffsetMs: Long
        get() = prefs.getLong("danmaku_offset", 0L)
        set(value) = prefs.edit().putLong("danmaku_offset", value).apply()

    /** 最近一次使用的弹幕源：bili / server。 */
    var danmakuSource: String
        get() = prefs.getString("danmaku_source", "bili") ?: "bili"
        set(value) = prefs.edit().putString("danmaku_source", value).apply()

    /** 自建弹幕服务器地址（弹弹play v2 兼容协议）。 */
    var danmakuServerUrl: String
        get() = prefs.getString("danmaku_server_url", "") ?: ""
        set(value) = prefs.edit().putString("danmaku_server_url", value).apply()

    /** 切到下一集时自动匹配弹幕（优先沿用当前整季列表，其次按新集标题重搜）。 */
    var danmakuAutoNext: Boolean
        get() = prefs.getBoolean("danmaku_auto_next", true)
        set(value) = prefs.edit().putBoolean("danmaku_auto_next", value).apply()

    /** 弹幕滚动速度倍率，0.5 ~ 2.0（越大滚得越快）。 */
    var danmakuSpeed: Float
        get() = prefs.getFloat("danmaku_speed", 1.0f)
        set(value) = prefs.edit().putFloat("danmaku_speed", value).apply()

    /** 弹幕显示区域占屏幕高度的比例，0.3 ~ 1.0。 */
    var danmakuArea: Float
        get() = prefs.getFloat("danmaku_area", 0.62f)
        set(value) = prefs.edit().putFloat("danmaku_area", value).apply()

    var danmakuShowScroll: Boolean
        get() = prefs.getBoolean("danmaku_show_scroll", true)
        set(value) = prefs.edit().putBoolean("danmaku_show_scroll", value).apply()

    var danmakuShowTop: Boolean
        get() = prefs.getBoolean("danmaku_show_top", true)
        set(value) = prefs.edit().putBoolean("danmaku_show_top", value).apply()

    var danmakuShowBottom: Boolean
        get() = prefs.getBoolean("danmaku_show_bottom", true)
        set(value) = prefs.edit().putBoolean("danmaku_show_bottom", value).apply()

    /** 上次弹幕搜索的关键词，下次打开面板直接填回去。 */
    /** 资源搜索上次输入的关键词，下次打开自动回填。 */
    var resourceLastQuery: String
        get() = prefs.getString("resource_last_query", "").orEmpty()
        set(value) = prefs.edit().putString("resource_last_query", value).apply()

    var danmakuLastQuery: String
        get() = prefs.getString("danmaku_last_query", "") ?: ""
        set(value) = prefs.edit().putString("danmaku_last_query", value).apply()

    /** 屏蔽词，逗号 / 空格 / 顿号分隔。 */
    var danmakuBlockWords: String
        get() = prefs.getString("danmaku_block_words", "") ?: ""
        set(value) = prefs.edit().putString("danmaku_block_words", value).apply()

    /** 正则屏蔽，一行一条，非法表达式会被忽略。 */
    var danmakuBlockRegex: String
        get() = prefs.getString("danmaku_block_regex", "") ?: ""
        set(value) = prefs.edit().putString("danmaku_block_regex", value).apply()

    fun positionOf(uri: String): Long = prefs.getLong("pos_$uri", 0L)

    /**
     * 标记「这个视频看过」，只写最近播放时间，不改进度。
     * 用来让刚点开几秒的视频也能进「记录 / 继续观看」，而不是等进度攒够 1 分钟。
     */
    fun markPlayed(uri: String) {
        if (uri.isBlank()) return
        prefs.edit().putLong("last_played_$uri", System.currentTimeMillis()).apply()
    }

    fun savePosition(uri: String, positionMs: Long) {
        if (positionMs <= 0) return
        prefs.edit().putLong("pos_$uri", positionMs).apply()
        prefs.edit().putLong("last_played_$uri", System.currentTimeMillis()).apply()
    }

    fun clearPosition(uri: String) {
        prefs.edit().remove("pos_$uri").remove("last_played_$uri").apply()
    }

    fun progressSnapshot(): Map<String, Long> {
        val result = HashMap<String, Long>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("pos_") && value is Long) {
                result[key.removePrefix("pos_")] = value
            }
        }
        return result
    }

    /** 每个视频上次播放的时间戳，用于播放记录排序。 */
    fun lastPlayedSnapshot(): Map<String, Long> {
        val result = HashMap<String, Long>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("last_played_") && value is Long) {
                result[key.removePrefix("last_played_")] = value
            }
        }
        return result
    }

    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet()) ?: emptySet()

    fun toggleFavorite(uri: String): Boolean {
        val current = favorites().toMutableSet()
        val added = if (current.contains(uri)) {
            current.remove(uri)
            false
        } else {
            current.add(uri)
            true
        }
        prefs.edit().putStringSet("favorites", current).apply()
        return added
    }

    // ---------- 网盘文件夹筛选缓存（持久化，重启不用重新扫描） ----------

    fun cloudScanCache(): Map<String, Boolean> {
        val raw = prefs.getString("cloud_scan_cache", null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { key ->
                    put(key, json.optBoolean(key))
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun saveCloudScanCache(cache: Map<String, Boolean>) {
        val json = JSONObject()
        cache.entries.take(4000).forEach { (key, value) -> json.put(key, value) }
        prefs.edit().putString("cloud_scan_cache", json.toString()).apply()
    }

    fun clearCloudScanCache() {
        prefs.edit().remove("cloud_scan_cache").apply()
    }

    /** 长按屏蔽掉的网盘文件夹 fid。 */
    fun hiddenCloudFolders(): Set<String> =
        prefs.getStringSet("hidden_cloud_folders", emptySet()) ?: emptySet()

    fun toggleHiddenCloudFolder(fid: String): Boolean {
        val current = hiddenCloudFolders().toMutableSet()
        val hidden = if (current.contains(fid)) {
            current.remove(fid)
            false
        } else {
            current.add(fid)
            true
        }
        prefs.edit().putStringSet("hidden_cloud_folders", current).apply()
        return hidden
    }

    // 网盘列表的视图/排序（和本地媒体库分开，互不影响）
    var cloudGridLayout: Boolean
        get() = prefs.getBoolean("cloud_grid_layout", false)
        set(value) = prefs.edit().putBoolean("cloud_grid_layout", value).apply()

    var cloudSortMode: Int
        get() = prefs.getInt("cloud_sort_mode", 0)
        set(value) = prefs.edit().putInt("cloud_sort_mode", value).apply()

    var cloudSortAscending: Boolean
        get() = prefs.getBoolean("cloud_sort_ascending", false)
        set(value) = prefs.edit().putBoolean("cloud_sort_ascending", value).apply()

    // ---------- 网盘视频元信息（收藏 / 播放记录） ----------

    fun cloudVideos(): List<CloudVideo> {
        val raw = prefs.getString("cloud_videos", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val fid = item.optString("fid").takeIf { it.isNotBlank() } ?: continue
                    add(
                        CloudVideo(
                            fid = fid,
                            name = item.optString("name").ifBlank { "网盘视频" },
                            size = item.optLong("size"),
                            updatedAt = item.optLong("updated_at"),
                            parentFid = item.optString("parent_fid"),
                            parentName = item.optString("parent_name"),
                            durationMs = item.optLong("duration_ms"),
                            isDir = item.optBoolean("is_dir"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun cloudVideo(fid: String): CloudVideo? = cloudVideos().firstOrNull { it.fid == fid }

    /**
     * 历史版本把网盘进度存在纯 fid 下（pos_<fid>），而列表条目用的是 quark://<fid>，
     * 两边对不上，网盘视频就不会出现在「记录」里。这里把旧记录搬一次。
     */
    fun migrateCloudPlaybackKeys() {
        if (cloudKeysMigrated) return
        cloudKeysMigrated = true
        val edits = prefs.edit()
        var changed = false
        cloudVideos().forEach { video ->
            val oldPos = "pos_${video.fid}"
            val newPos = "pos_${video.key}"
            if (prefs.contains(oldPos) && !prefs.contains(newPos)) {
                edits.putLong(newPos, prefs.getLong(oldPos, 0L))
                changed = true
            }
            val oldLast = "last_played_${video.fid}"
            val newLast = "last_played_${video.key}"
            if (prefs.contains(oldLast) && !prefs.contains(newLast)) {
                edits.putLong(newLast, prefs.getLong(oldLast, 0L))
                changed = true
            }
            // 字幕记忆也一起搬：1.4.47 的网盘 key 还是纯 fid
            val oldSubtitle = "video_subtitle_${video.fid}"
            val newSubtitle = "video_subtitle_${video.key}"
            val savedSubtitle = prefs.getString(oldSubtitle, null)
            if (savedSubtitle != null && !prefs.contains(newSubtitle)) {
                edits.putString(newSubtitle, savedSubtitle)
                changed = true
            }
        }
        if (changed) edits.apply()
    }

    fun saveCloudVideo(video: CloudVideo) {
        val current = cloudVideos().associateBy { it.fid }.toMutableMap()
        current[video.fid] = video
        val array = JSONArray()
        current.values
            .sortedByDescending { it.updatedAt }
            .take(600)
            .forEach { item ->
                array.put(
                    JSONObject()
                        .put("fid", item.fid)
                        .put("name", item.name)
                        .put("size", item.size)
                        .put("updated_at", item.updatedAt)
                        .put("parent_fid", item.parentFid)
                        .put("parent_name", item.parentName)
                        .put("duration_ms", item.durationMs)
                        .put("is_dir", item.isDir),
                )
            }
        prefs.edit().putString("cloud_videos", array.toString()).apply()
    }

    fun removeCloudVideo(fid: String) {
        val current = cloudVideos().filterNot { it.fid == fid }
        val array = JSONArray()
        current.forEach { item ->
            array.put(
                JSONObject()
                    .put("fid", item.fid)
                    .put("name", item.name)
                    .put("size", item.size)
                    .put("updated_at", item.updatedAt)
                    .put("parent_fid", item.parentFid)
                    .put("parent_name", item.parentName)
                    // 之前重写时漏了这两个字段：删掉一条网盘视频后，
                    // 其余条目的时长全变成 0、文件夹标记也丢了
                    .put("duration_ms", item.durationMs)
                    .put("is_dir", item.isDir),
            )
        }
        prefs.edit().putString("cloud_videos", array.toString()).apply()
    }

    // ---------- 收藏的夸克文件夹（文件夹盘） ----------

    fun quarkFolders(): List<QuarkFolder> {
        val raw = prefs.getString("quark_folders", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val keys = HashSet<String>()
                    item.optJSONArray("synced_keys")?.let { arr ->
                        for (k in 0 until arr.length()) {
                            arr.optString(k).takeIf { it.isNotBlank() }?.let { keys.add(it) }
                        }
                    }
                    add(
                        QuarkFolder(
                            url = url,
                            code = item.optString("code"),
                            name = item.optString("name").ifBlank { "夸克文件夹" },
                            addedAt = item.optLong("added_at"),
                            syncedKeys = keys,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** 按 url 去重保存；最多留 20 个，避免设置里无限膨胀。 */
    fun saveQuarkFolder(folder: QuarkFolder) {
        val current = quarkFolders().associateBy { it.url }.toMutableMap()
        current[folder.url] = folder
        val array = JSONArray()
        current.values
            .sortedByDescending { it.addedAt }
            .take(20)
            .forEach { item ->
                array.put(
                    JSONObject()
                        .put("url", item.url)
                        .put("code", item.code)
                        .put("name", item.name)
                        .put("added_at", item.addedAt)
                        .put("synced_keys", JSONArray(item.syncedKeys.toList())),
                )
            }
        prefs.edit().putString("quark_folders", array.toString()).apply()
    }

    fun removeQuarkFolder(url: String) {
        val rest = quarkFolders().filterNot { it.url == url }
        val array = JSONArray()
        rest.forEach { item ->
            array.put(
                JSONObject()
                    .put("url", item.url)
                    .put("code", item.code)
                    .put("name", item.name)
                    .put("added_at", item.addedAt)
                    .put("synced_keys", JSONArray(item.syncedKeys.toList())),
            )
        }
        prefs.edit().putString("quark_folders", array.toString()).apply()
    }

    // ---------- 全局字幕库（文件夹导入，所有视频可用） ----------

    /**
     * 导入的字幕文件夹列表（两级目录：文件夹 → 文件）。
     * 1.4.48 的旧版是一张扁平文件表，这里在首次读取时自动收进一个「已导入字幕」文件夹，
     * 升级后用户之前导入的字幕不会凭空消失。
     */
    fun subtitleFolders(): List<SubtitleFolder> {
        val raw = prefs.getString("subtitle_folders", null)?.takeIf { it.isNotBlank() }
        if (raw == null) {
            // 不再把旧版扁平列表伪装成「已导入字幕」文件夹
            prefs.edit().remove("subtitle_library").apply()
            return emptyList()
        }
        val parsed = runCatching { parseSubtitleFolders(raw) }.getOrDefault(emptyList())
        // 清掉 1.4.49 生成的合成文件夹，连同旧的扁平列表一起删除
        val cleaned = parsed.filterNot { it.treeUri == LEGACY_SUBTITLE_TREE }
        if (cleaned.size != parsed.size) {
            saveSubtitleFolders(cleaned)
            prefs.edit().remove("subtitle_library").apply()
        }
        return cleaned
    }

    private fun parseSubtitleFolders(raw: String): List<SubtitleFolder> {
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val treeUri = obj.optString("treeUri").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val filesArray = obj.optJSONArray("files") ?: JSONArray()
            val files = (0 until filesArray.length()).mapNotNull { fileIndex ->
                val fileObj = filesArray.optJSONObject(fileIndex) ?: return@mapNotNull null
                val uri = fileObj.optString("uri").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                SubtitleFile(
                    uri = uri,
                    name = fileObj.optString("name").ifBlank { uri.substringAfterLast('/') },
                    folder = fileObj.optString("folder"),
                )
            }
            SubtitleFolder(
                treeUri = treeUri,
                name = obj.optString("name").ifBlank { "字幕文件夹" },
                addedAt = obj.optLong("addedAt", 0L),
                files = files,
            )
        }
    }

    private fun saveSubtitleFolders(folders: List<SubtitleFolder>) {
        val array = JSONArray()
        folders.forEach { folder ->
            val filesArray = JSONArray()
            folder.files.forEach { file ->
                filesArray.put(
                    JSONObject()
                        .put("uri", file.uri)
                        .put("name", file.name)
                        .put("folder", file.folder),
                )
            }
            array.put(
                JSONObject()
                    .put("treeUri", folder.treeUri)
                    .put("name", folder.name)
                    .put("addedAt", folder.addedAt)
                    .put("files", filesArray),
            )
        }
        prefs.edit().putString("subtitle_folders", array.toString()).apply()
    }

    /** 新增或重新扫描同一个文件夹；返回 true 表示这次是覆盖已有文件夹。 */
    fun addSubtitleFolder(folder: SubtitleFolder): Boolean {
        val folders = subtitleFolders().toMutableList()
        val index = folders.indexOfFirst { it.treeUri == folder.treeUri }
        val existed = index >= 0
        val cleaned = folder.copy(
            files = folder.files.distinctBy { it.uri }.sortedBy { it.name.lowercase() }.take(300),
        )
        if (existed) {
            folders[index] = cleaned.copy(addedAt = folders[index].addedAt)
        } else {
            folders.add(cleaned)
        }
        // 最多保留 20 个文件夹；后导入的优先
        saveSubtitleFolders(folders.takeLast(20))
        return existed
    }

    fun removeSubtitleFolder(treeUri: String) {
        saveSubtitleFolders(subtitleFolders().filterNot { it.treeUri == treeUri })
    }

    fun renameSubtitleFolder(treeUri: String, name: String) {
        saveSubtitleFolders(
            subtitleFolders().map { if (it.treeUri == treeUri) it.copy(name = name) else it },
        )
    }

    fun subtitleLibrary(): List<SubtitleFile> {
        val raw = prefs.getString("subtitle_library", null)?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val uri = obj.optString("uri").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                SubtitleFile(
                    uri = uri,
                    name = obj.optString("name").ifBlank { uri.substringAfterLast('/') },
                    folder = obj.optString("folder"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveSubtitleLibrary(files: List<SubtitleFile>) {
        val array = JSONArray()
        files.forEach { file ->
            array.put(
                JSONObject()
                    .put("uri", file.uri)
                    .put("name", file.name)
                    .put("folder", file.folder),
            )
        }
        prefs.edit().putString("subtitle_library", array.toString()).apply()
    }

    /** 合并导入结果，按 URI 去重并按文件名排序；最多保留 300 条，避免面板列表过长。 */
    fun addSubtitleFiles(files: List<SubtitleFile>): Int {
        if (files.isEmpty()) return 0
        val existing = subtitleLibrary()
        val known = existing.mapTo(HashSet()) { it.uri }
        val added = files.asSequence()
            .filter { it.uri.isNotBlank() && it.uri !in known }
            .distinctBy { it.uri }
            .toList()
        if (added.isEmpty()) return 0
        val merged = (existing + added)
            .distinctBy { it.uri }
            .sortedBy { it.name.lowercase() }
            .take(300)
        saveSubtitleLibrary(merged)
        return added.size
    }

    fun removeSubtitleFile(uri: String) {
        saveSubtitleLibrary(subtitleLibrary().filterNot { it.uri == uri })
    }

    fun clearSubtitleLibrary() {
        prefs.edit().remove("subtitle_library").apply()
    }

    /** 每个视频记住上次加载的字幕（内容 URI）；空 = 没加载过，"-" = 用户主动关闭。 */
    fun videoSubtitleOf(videoKey: String): String? =
        if (videoKey.isBlank()) null else prefs.getString("video_subtitle_$videoKey", null)

    fun saveVideoSubtitle(videoKey: String, uriOrOff: String) {
        if (videoKey.isBlank()) return
        prefs.edit().putString("video_subtitle_$videoKey", uriOrOff).apply()
    }

    fun resetPlaybackMemory() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("pos_") || it.startsWith("last_played_") }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    private companion object {
        @Volatile
        private var cloudKeysMigrated = false
        const val LEGACY_SUBTITLE_TREE = "legacy://subtitle-library"
    }
}

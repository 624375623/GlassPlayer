package com.glassplayer.player

import com.glassplayer.data.QuarkApi
import com.glassplayer.ui.GlassSheet
import com.glassplayer.ui.PlayerSheet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.glassplayer.data.DebugLog
import com.glassplayer.cast.CastManager
import com.glassplayer.ui.CastPanel
import com.glassplayer.data.SettingsStore
import com.glassplayer.data.SubtitleFile
import com.glassplayer.data.SubtitleFolder
import com.glassplayer.data.SubtitleFolderScanner
import com.glassplayer.data.formatDuration
import com.glassplayer.danmaku.BiliDanmakuSource
import com.glassplayer.danmaku.Danmaku
import com.glassplayer.danmaku.DanmakuCache
import com.glassplayer.danmaku.DanmakuLocalFile
import com.glassplayer.danmaku.DanmakuMatch
import com.glassplayer.danmaku.DanmakuMerge
import com.glassplayer.danmaku.DanmakuOverlay
import com.glassplayer.danmaku.IqiyiDanmakuSource
import com.glassplayer.danmaku.ServerDanmakuSource
import com.glassplayer.danmaku.TencentDanmakuSource
import com.glassplayer.danmaku.YoukuDanmakuSource
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.GlasmioBackdropProvider
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberCanvasBackdrop
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.icons.SfIcon

import com.glassplayer.ui.SlimSlider
import com.glassplayer.ui.episodeOnlyNumber
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

private data class TrackOption(
    val label: String,
    val group: androidx.media3.common.TrackGroup,
    val index: Int,
    /** 媒体当前实际选中的那条轨；之前用 position == 0 判断，选过别的轨再打开仍标第一条。 */
    val selected: Boolean = false,
)

@Composable
fun PlayerScreen(
    controller: MediaController?,
    entries: List<PlaylistEntry>,
    initialIndex: Int,
    startPositionMs: Long,
    settings: SettingsStore,
    isInPip: Boolean,
    onBack: () -> Unit,
    onEnterPip: () -> Unit,
    onToggleOrientation: () -> Unit,
    onLockChange: (Boolean) -> Unit = {},
    onBrightnessDelta: (Float) -> Float,
    onVolumeDelta: (Float) -> Float,
    onVideoSizeChanged: (Int, Int) -> Unit,
    onSessionReady: (Int, Long) -> Unit,
    connectionError: String? = null,
    onRetryConnect: () -> Unit = {},
    initialSeries: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- 网盘按需解析：整季只带 fid 进播放页，切到哪一集才解析哪一集 ----
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    val cloudApi = remember { runCatching { QuarkApi(context, settings) }.getOrNull() }
    val resolvingIndexes = remember { mutableSetOf<Int>() }

    /**
     * 安全读取播放列表条目。
     *
     * controller 刚连上、或播放列表正在重建时，`mediaItemCount` 可能还是 0，
     * 而 `currentMediaItemIndex` 是 INDEX_UNSET(-1)；直接 getMediaItemAt 会抛
     * IndexOutOfBoundsException（线上 release 包表现为一进播放页 / 切集瞬间闪退）。
     */
    fun mediaItemAt(index: Int): androidx.media3.common.MediaItem? {
        val c = controller ?: return null
        if (index < 0 || index >= c.mediaItemCount) return null
        return runCatching { c.getMediaItemAt(index) }.getOrNull()
    }

    /** 当前条目索引；controller 未就绪（-1）时退回 Compose 记录的 currentIndex。 */
    fun liveIndexSafe(fallback: Int): Int {
        val i = controller?.currentMediaItemIndex ?: -1
        return if (i >= 0) i else fallback
    }

    fun isPendingIndex(index: Int): Boolean {
        val item = mediaItemAt(index) ?: return false
        // extras 跨进程更可靠；scheme 判断留作兜底
        if (item.mediaMetadata.extras?.getBoolean(PENDING_EXTRA) == true) return true
        return item.localConfiguration?.uri?.scheme == "glassplayer-pending"
    }

    /** 取当前播放列表第 index 条对应的网盘 fid（跟着 MediaItem 走，不依赖 entries 下标）。 */
    fun fidOf(index: Int): String? =
        mediaItemAt(index)
            ?.mediaMetadata
            ?.extras
            ?.getString(FID_EXTRA)
            ?.takeIf { it.isNotBlank() }

    /** 当前条目的稳定 key（本地是 uri、网盘是 fid），用于按视频记忆画面比例。 */
    fun liveKey(index: Int): String =
        mediaItemAt(index)?.mediaId?.takeIf { it.isNotBlank() }
            ?: entries.getOrNull(index)?.key
            ?: ""

    /** 当前条目的标题；播放列表被重排 / 移除后 entries 的下标已经不可信。 */
    fun liveTitle(index: Int): String =
        mediaItemAt(index)?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() }
            ?: entries.getOrNull(index)?.title
            ?: ""

    suspend fun ensurePlayable(index: Int) {
        // 只处理「确实是占位条目」的那一条；fid 与标题都以播放列表里的实际条目为准
        if (!isPendingIndex(index)) return
        val live = mediaItemAt(index) ?: return
        val fid = fidOf(index) ?: entries.getOrNull(index)?.fid ?: return
        if (!resolvingIndexes.add(index)) return
        val api = cloudApi
        if (api == null) {
            resolvingIndexes.remove(index)
            return
        }
        val url = withContext(Dispatchers.IO) {
            runCatching { api.streamUrl(fid) }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: runCatching { api.downloadUrl(fid) }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        resolvingIndexes.remove(index)
        if (url == null) return
        val item = androidx.media3.common.MediaItem.Builder()
            .setUri(url)
            .setMediaId(live.mediaId)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(live.mediaMetadata.title)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setExtras(
                        android.os.Bundle().apply {
                            // 清掉占位标记，但保留 fid，后面投屏切集还要用
                            putBoolean(PENDING_EXTRA, false)
                            putString(FID_EXTRA, fid)
                        },
                    )
                    .build(),
            )
            .build()
        controller?.replaceMediaItem(index, item)
        if (index == controller?.currentMediaItemIndex) controller?.prepare()
    }

    fun resolvePending(index: Int) {
        scope.launch { ensurePlayable(index) }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    // 逐帧：连续点按要能累加，不能被 180ms 一次的进度轮询把目标拉回去
    var frameAnchorMs by remember { mutableStateOf<Long?>(null) }
    var frameStepHoldUntil by remember { mutableLongStateOf(0L) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    // 记录「已经初始化过播放列表的那个 controller 实例」，而不是布尔值：
    // 服务重启 / 断线重连会拿到一个新的 controller，布尔值一旦变 true 就再也不会 enqueue，
    // 播放列表会变成空的；按实例比较才能在新连接上重新装载。
    var preparedFor by remember { mutableStateOf<MediaController?>(null) }
    var currentIndex by remember { mutableIntStateOf(initialIndex) }
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    // 锁屏按钮不只是藏控件：同时把系统方向锁在当前方向，翻手机不再转屏
    LaunchedEffect(locked) { onLockChange(locked) }
    var speed by remember { mutableFloatStateOf(settings.defaultSpeed) }
    // 画面比例：0 适应 / 1 缩放铺满 / 2 拉伸铺满 / 3 固定宽度 / 4 自定义宽高比
    // 记忆是「一个视频一份」的：当前视频设过就恢复，没设过一律回到适应屏幕
    val initialVideoKey = entries.getOrNull(initialIndex)?.key ?: ""
    val initialAspect = remember { settings.videoAspectOf(initialVideoKey) }
    var aspectMode by remember { mutableIntStateOf(initialAspect?.first ?: 0) }
    var aspectW by remember { mutableIntStateOf(initialAspect?.second ?: 16) }
    var aspectH by remember { mutableIntStateOf(initialAspect?.third ?: 9) }
    // 双指缩放出来的画面缩放倍数，只影响本次播放，不写进设置
    var videoZoom by remember { mutableFloatStateOf(1f) }
    var showRatioSheet by remember { mutableStateOf(false) }
    // 播放页半屏弹层的不透明度：0.35 ~ 1.0，越大越实、越小越透
    var sheetOpacity by remember { mutableFloatStateOf(settings.playerSheetOpacity) }
    // 换一集时画面大小回到 100%，并恢复这一集自己记住的比例（没设过就是适应屏幕）
    LaunchedEffect(currentIndex) {
        videoZoom = 1f
        val key = liveKey(liveIndexSafe(currentIndex))
        val saved = settings.videoAspectOf(key)
        aspectMode = saved?.first ?: 0
        aspectW = saved?.second ?: 16
        aspectH = saved?.third ?: 9
    }
    // 只给当前视频写比例，别影响其他视频
    fun applyAspect(mode: Int, w: Int = aspectW, h: Int = aspectH) {
        aspectMode = mode
        aspectW = w
        aspectH = h
        val key = liveKey(liveIndexSafe(currentIndex))
        // 选「适应屏幕」等于没设置，顺手把记忆清掉
        if (mode == 0) settings.clearVideoAspect(key) else settings.saveVideoAspect(key, mode, w, h)
    }
    var gestureHint by remember { mutableStateOf<String?>(null) }
    var seekPreview by remember { mutableStateOf<Long?>(null) }
    var tracksVersion by remember { mutableIntStateOf(0) }
    var hud by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var queueVersion by remember { mutableIntStateOf(0) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showCastSheet by remember { mutableStateOf(false) }
    val castState by CastManager.state.collectAsState()
    var seekBarFraction by remember { mutableStateOf<Float?>(null) }
    var boosting by remember { mutableStateOf(false) }
    var speedBeforeBoost by remember { mutableFloatStateOf(settings.defaultSpeed) }

    var aPointMs by remember { mutableLongStateOf(-1L) }
    var bPointMs by remember { mutableLongStateOf(-1L) }

    /**
     * 逐帧步进。
     * 之前直接 `positionMs ± 33ms`：连续点时 180ms 一次的进度轮询会把 positionMs 拉回
     * 播放器的旧位置，下一次点击又从旧位置算，目标始终在原地打转 ——
     * 表现就是「只有弹幕在动、画面不动」。
     * 现在用 frameAnchorMs 做累加锚点，并在 seek 完成前冻结轮询回写。
     */
    fun stepFrame(delta: Int) {
        val player = controller ?: return
        player.pause()
        val fps = selectedFormat(player, C.TRACK_TYPE_VIDEO)?.frameRate ?: 0f
        val frameStep = (1000f / (if (fps > 1f) fps else 30f)).toLong().coerceAtLeast(16L)
        // HLS 的 seek 只能落在分片边界，1 帧的位移不会换画面，只能退化成 0.5 秒
        val isHls = player.currentMediaItem?.localConfiguration?.uri?.toString()
            ?.contains(".m3u8", ignoreCase = true) == true
        val step = if (isHls) 500L else frameStep
        val base = frameAnchorMs ?: positionMs
        val limit = if (durationMs > 0) (durationMs - 40L).coerceAtLeast(0L) else Long.MAX_VALUE
        val target = (base + delta * step).coerceIn(0L, limit)
        frameAnchorMs = target
        frameStepHoldUntil = System.currentTimeMillis() + 2_000L
        player.seekTo(target)
        positionMs = target
        if (isHls) gestureHint = "流媒体按 0.5 秒步进"
    }

    fun clearFrameAnchor() {
        frameAnchorMs = null
        frameStepHoldUntil = 0L
    }

    var subtitleTrack by remember { mutableStateOf<SubtitleTrack?>(null) }
    // 当前字幕来源 URI：字幕库里高亮选中项、判断是否同一个文件
    var subtitleSourceUri by remember { mutableStateOf<String?>(null) }
    var subtitleFolders by remember { mutableStateOf(settings.subtitleFolders()) }
    var subtitleLibraryMessage by remember { mutableStateOf<String?>(null) }
    // 第一级展开文件夹列表，第二级展开选中文件夹里的字幕文件
    var showSubtitleFolders by remember { mutableStateOf(false) }
    var openedSubtitleFolder by remember { mutableStateOf<String?>(null) }
    // 自动关联用：当前字幕所属文件夹 + 系列名（去掉集号）
    var activeSubtitleFolderTree by remember { mutableStateOf<String?>(null) }
    var activeSubtitleSeriesToken by remember { mutableStateOf<String?>(null) }

    fun rememberActiveSubtitle(sourceUri: String, name: String?) {
        val folder = subtitleFolders.firstOrNull { item ->
            item.files.any { it.uri == sourceUri }
        }
        activeSubtitleFolderTree = folder?.treeUri
        activeSubtitleSeriesToken = name?.let { subtitleSeriesToken(it) }
        if (folder != null) {
            showSubtitleFolders = true
            openedSubtitleFolder = folder.treeUri
        }
    }

    /** 上下集自动关联：优先当前字幕文件夹；没有活动文件夹时只在片名高度相似时跨文件夹匹配。 */
    fun autoSubtitleFor(title: String): SubtitleFile? {
        val episode = episodeOnlyNumber(title) ?: return null
        val activeFolder = subtitleFolders.firstOrNull {
            it.treeUri == activeSubtitleFolderTree
        }
        if (activeFolder != null) {
            val candidates = activeFolder.files.filter {
                episodeOnlyNumber(it.name) == episode
            }
            if (candidates.isEmpty()) return null
            if (candidates.size == 1) return candidates.first()
            val token = activeSubtitleSeriesToken ?: return null
            val best = candidates.maxByOrNull {
                seriesSimilarity(token, subtitleSeriesToken(it.name))
            } ?: return null
            return if (seriesSimilarity(token, subtitleSeriesToken(best.name)) > 0f) best else null
        }
        val videoToken = subtitleSeriesToken(title)
        val candidates = subtitleFolders.flatMap { folder ->
            folder.files.map { folder to it }
        }.filter { episodeOnlyNumber(it.second.name) == episode }
        if (candidates.isEmpty()) return null
        val best = candidates.maxByOrNull {
            seriesSimilarity(videoToken, subtitleSeriesToken(it.second.name))
        } ?: return null
        return if (seriesSimilarity(videoToken, subtitleSeriesToken(best.second.name)) >= 0.6f) {
            best.second
        } else {
            null
        }
    }
    var subtitleDelayMs by remember { mutableStateOf(settings.subtitleDelayMs) }
    var subtitleScale by remember { mutableStateOf(settings.subtitleScale) }
    var subtitleAlpha by remember { mutableStateOf(settings.subtitleAlpha) }
    // 0 = 贴底（默认，距底部 96dp），1 = 贴顶
    var subtitlePosition by remember {
        mutableStateOf(settings.subtitlePosition.coerceIn(-0.3f, 1f))
    }
    // null = 全部；非空时只显示选中的语言。全局记忆，切到没有该语言的字幕会自动回全部。
    var subtitleOnlyLanguage by remember {
        mutableStateOf(
            settings.subtitleLanguageFilter
                .takeIf { it.isNotBlank() }
                ?.let { runCatching { SubtitleLanguage.valueOf(it) }.getOrNull() },
        )
    }

    LaunchedEffect(subtitleTrack) {
        val track = subtitleTrack ?: return@LaunchedEffect
        val selected = subtitleOnlyLanguage ?: return@LaunchedEffect
        if (selected !in track.languages) {
            subtitleOnlyLanguage = null
            settings.subtitleLanguageFilter = ""
        }
    }

    var showSpeedSheet by remember { mutableStateOf(false) }
    var showAudioSheet by remember { mutableStateOf(false) }
    var showSubtitleSheet by remember { mutableStateOf(false) }
    var showInfoSheet by remember { mutableStateOf(false) }


    // ---- 弹幕 ----
    var danmakuEnabled by remember { mutableStateOf(settings.danmakuEnabled) }
    var danmakuItems by remember { mutableStateOf<List<Danmaku>>(emptyList()) }
    var danmakuColor by remember { mutableIntStateOf(settings.danmakuColor) }
    var danmakuOpacity by remember { mutableFloatStateOf(settings.danmakuOpacity) }
    var danmakuScale by remember { mutableFloatStateOf(settings.danmakuScale) }
    var danmakuOffsetMs by remember { mutableLongStateOf(settings.danmakuOffsetMs) }
    var danmakuSource by remember { mutableStateOf(settings.danmakuSource) }
    var danmakuServerUrl by remember { mutableStateOf(settings.danmakuServerUrl) }
    var showDanmakuSheet by remember { mutableStateOf(false) }

    // 任意弹层是否打开。提前到这里定义：自动隐藏控件的协程也要用它判断，
    // 否则用户正在弹层里调参数时，底下那层控件会自己消失
    val anySheetOpen = showRatioSheet || showSpeedSheet || showAudioSheet ||
        showSubtitleSheet || showInfoSheet || showDanmakuSheet || showMoreSheet ||
        showQueueSheet || showCastSheet
    // 网盘播放时由外部传剧名进来，切集自动匹配才有正确的搜索词
    var danmakuQuery by remember {
        mutableStateOf(initialSeries?.takeIf { it.isNotBlank() } ?: settings.danmakuLastQuery)
    }
    var danmakuResults by remember { mutableStateOf<List<DanmakuMatch>>(emptyList()) }
    var danmakuEpisodesMode by remember { mutableStateOf(false) }
    var danmakuReverse by remember { mutableStateOf(false) }
    // 当前选中的弹幕集，网格里给它加边框高亮
    var danmakuSelectedKey by remember { mutableStateOf<String?>(null) }
    var danmakuBusy by remember { mutableStateOf(false) }
    var danmakuMessage by remember { mutableStateOf<String?>(null) }
    var danmakuLoadedLabel by remember { mutableStateOf<String?>(null) }
    var danmakuSpeed by remember { mutableFloatStateOf(settings.danmakuSpeed) }
    var danmakuArea by remember { mutableFloatStateOf(settings.danmakuArea) }
    var danmakuShowScroll by remember { mutableStateOf(settings.danmakuShowScroll) }
    var danmakuShowTop by remember { mutableStateOf(settings.danmakuShowTop) }
    var danmakuShowBottom by remember { mutableStateOf(settings.danmakuShowBottom) }
    var danmakuBlockWords by remember { mutableStateOf(settings.danmakuBlockWords) }
    var danmakuBlockRegex by remember { mutableStateOf(settings.danmakuBlockRegex) }
    var danmakuTab by remember { mutableIntStateOf(0) } // 0=搜索 1=设置
    // 自动匹配下一集：记住当前这部的整季列表和游标，切集时直接取对应一集
    var danmakuSeries by remember { mutableStateOf<List<DanmakuMatch>>(emptyList()) }
    var danmakuSeriesIndex by remember { mutableIntStateOf(0) }
    // 锚点：用户手动选定/搜索定位的那一集，记录它在播放列表和正片列表里的位置，
    // 之后切集就按两个位置的差值顺推，保证“下一集视频 ↔ 下一集正片弹幕”
    var danmakuAnchorPlayerIndex by remember { mutableIntStateOf(-1) }
    var danmakuAnchorSeriesIndex by remember { mutableIntStateOf(-1) }
    var danmakuAutoNext by remember { mutableStateOf(settings.danmakuAutoNext) }
    var episodeToken by remember { mutableIntStateOf(0) }

    // 退出再点开同一集：先读缓存（按内容指纹），缓存没有就看同目录有没有同名弹幕文件
    suspend fun restoreDanmaku(uri: String) {
        val cached = withContext(Dispatchers.IO) { DanmakuCache.load(context, uri) }
        if (cached != null) {
            danmakuItems = cached.second
            danmakuLoadedLabel = cached.first
            return
        }
        val local = withContext(Dispatchers.IO) { DanmakuLocalFile.load(context, uri) } ?: return
        val items = DanmakuMerge.dedupe(local.second)
        danmakuItems = items
        danmakuLoadedLabel = "${local.first} · ${items.size} 条"
        withContext(Dispatchers.IO) { DanmakuCache.save(context, uri, danmakuLoadedLabel ?: local.first, items) }
    }

    val initialUri = remember { entries.getOrNull(initialIndex)?.uri }
    LaunchedEffect(initialUri) {
        val uri = initialUri ?: return@LaunchedEffect
        // 网盘视频没有本地文件指纹，跳过按文件缓存恢复，直接进入在线搜索
        if (uri.startsWith("content://") && danmakuItems.isEmpty()) restoreDanmaku(uri)
    }

    // 屏蔽词 + 类型开关在渲染前过滤一次，改设置立即生效，不用重新拉弹幕
    val danmakuVisible = remember(
        danmakuItems,
        danmakuBlockWords,
        danmakuBlockRegex,
        danmakuShowScroll,
        danmakuShowTop,
        danmakuShowBottom,
    ) {
        val blocks = danmakuBlockWords
            .split(',', '，', '、', ' ', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        // 一行一条正则，编译失败的直接跳过，不影响其它规则
        val regexes = danmakuBlockRegex
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { pattern -> runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull() }
        danmakuItems.filter { item ->
            val modeOk = when (item.mode) {
                com.glassplayer.danmaku.DanmakuMode.SCROLL -> danmakuShowScroll
                com.glassplayer.danmaku.DanmakuMode.TOP -> danmakuShowTop
                com.glassplayer.danmaku.DanmakuMode.BOTTOM -> danmakuShowBottom
            }
            modeOk &&
                blocks.none { item.text.contains(it, ignoreCase = true) } &&
                regexes.none { it.containsMatchIn(item.text) }
        }
    }

    val subtitleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // OpenDocument 默认只给一次临时授权，进程重启后再读会 SecurityException。
            // 转成持久授权，下次打开同一个视频才能自动恢复字幕。
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val videoKey = liveKey(liveIndexSafe(currentIndex))
            scope.launch {
                val parsed = withContext(Dispatchers.IO) {
                    SubtitleParser.read(context, uri)?.let { (name, content) ->
                        SubtitleParser.parse(name, content)
                    }
                }
                if (parsed != null && parsed.cues.isNotEmpty()) {
                    subtitleTrack = parsed
                    subtitleSourceUri = uri.toString()
                    settings.saveVideoSubtitle(videoKey, uri.toString())
                    rememberActiveSubtitle(uri.toString(), parsed.name)
                }
            }
        }
    }

    // 字幕文件夹导入：一次授权整个目录，之后库里所有字幕对本地/网盘视频都可用
    val subtitleFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            scope.launch {
                subtitleLibraryMessage = "正在扫描字幕文件夹…"
                val scan = withContext(Dispatchers.IO) {
                    SubtitleFolderScanner.scan(context, treeUri)
                }
                val existed = settings.subtitleFolders()
                    .any { it.treeUri == treeUri.toString() }
                settings.addSubtitleFolder(
                    SubtitleFolder(
                        treeUri = treeUri.toString(),
                        name = scan.rootName,
                        addedAt = System.currentTimeMillis(),
                        files = scan.files,
                    ),
                )
                subtitleFolders = settings.subtitleFolders()
                showSubtitleFolders = true
                openedSubtitleFolder = treeUri.toString()
                subtitleLibraryMessage = when {
                    scan.files.isEmpty() -> "「${scan.rootName}」里没有 SRT / VTT / ASS 字幕"
                    existed -> "已更新「${scan.rootName}」，共 ${scan.files.size} 个字幕"
                    scan.truncated -> "已导入 ${scan.files.size} 个（文件夹太大，只扫描了一部分）"
                    else -> "已从「${scan.rootName}」导入 ${scan.files.size} 个字幕"
                }
            }
        }
    }

    fun loadSubtitleFromLibrary(file: SubtitleFile) {
        scope.launch {
            subtitleLibraryMessage = "正在读取「${file.name}」…"
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    SubtitleParser.read(context, Uri.parse(file.uri))?.let { (name, content) ->
                        SubtitleParser.parse(name, content)
                    }
                }.getOrNull()
            }
            if (parsed != null && parsed.cues.isNotEmpty()) {
                subtitleTrack = parsed
                subtitleSourceUri = file.uri
                rememberActiveSubtitle(file.uri, parsed.name)
                // 库里选的字幕也进入当前视频的记忆，下次打开自动恢复
                settings.saveVideoSubtitle(
                    liveKey(liveIndexSafe(currentIndex)),
                    file.uri,
                )
                subtitleLibraryMessage = null
            } else {
                subtitleLibraryMessage = "读取「${file.name}」失败，文件可能已被移动或删除"
            }
        }
    }

    // 重新点开同一个视频时，自动加载上次用的字幕文件。
    // 用 videoKey（本地 uri / 网盘 fid）而不是集数下标，播放列表重排后也能对上。
    var subtitleRestoredFor by remember { mutableStateOf<String?>(null) }
    // 切集时 onMediaItemTransition 会先清空字幕，再把这里 +1 重新触发恢复；
    // 否则「进页面先恢复 → 列表装载后又被清掉 → 守卫判定已恢复过不再加载」，
    // 表现就是本地视频的字幕记忆失效。
    var subtitleEpoch by remember { mutableIntStateOf(0) }
    LaunchedEffect(currentIndex, entries, controller, subtitleEpoch) {
        val key = liveKey(liveIndexSafe(currentIndex))
        if (key.isBlank()) return@LaunchedEffect
        if (subtitleRestoredFor == key && subtitleTrack != null) return@LaunchedEffect
        val saved = settings.videoSubtitleOf(key)
        if (saved == SUBTITLE_OFF) {
            subtitleTrack = null
            subtitleSourceUri = null
            subtitleRestoredFor = key
            return@LaunchedEffect
        }
        var isAutoMatched = false
        var source = saved?.takeIf { it.isNotBlank() }
        if (source == null) {
            // 这个视频没有单独记忆时，按集号从当前字幕文件夹里找上下集对应的字幕
            val auto = autoSubtitleFor(liveTitle(liveIndexSafe(currentIndex)))
            if (auto != null) {
                source = auto.uri
                isAutoMatched = true
            }
        }
        val uriToLoad = source
        if (uriToLoad == null) {
            subtitleTrack = null
            subtitleSourceUri = null
            subtitleRestoredFor = key
            return@LaunchedEffect
        }
        val parsed = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(uriToLoad)
                SubtitleParser.read(context, uri)?.let { (name, content) ->
                    SubtitleParser.parse(name, content)
                }
            }.getOrNull()
        }
        // 解析期间已经切到别的集，就不要把上一集字幕贴回来
        if (liveKey(liveIndexSafe(currentIndex)) != key) return@LaunchedEffect
        if (parsed != null && parsed.cues.isNotEmpty()) {
            subtitleTrack = parsed
            subtitleSourceUri = uriToLoad
            // 自动匹配到的也写入记忆，下一次打开/切回来直接恢复
            if (isAutoMatched) settings.saveVideoSubtitle(key, uriToLoad)
            rememberActiveSubtitle(uriToLoad, parsed.name)
        } else {
            // 文件被删 / 授权失效：清掉记录，避免每次进来都白读一遍
            if (!isAutoMatched) settings.saveVideoSubtitle(key, "")
            subtitleTrack = null
            subtitleSourceUri = null
        }
        subtitleRestoredFor = key
    }

    // 打开字幕面板时自动展开：有正在用的字幕就展开到它所在的文件夹（第二级），
    // 没有就展开文件夹列表（第一级），不用再点一次「展开」
    LaunchedEffect(showSubtitleSheet) {
        if (showSubtitleSheet && subtitleFolders.isNotEmpty()) {
            showSubtitleFolders = true
            val currentFolder = subtitleFolders.firstOrNull { folder ->
                folder.files.any { it.uri == subtitleSourceUri }
            }
            openedSubtitleFolder = currentFolder?.treeUri
        }
    }

    LaunchedEffect(controller) {
        if (controller != null && preparedFor !== controller) {
            // 第一次连接用外部传入的起始集数/进度；断线重连要接着当前的集数和进度装载，
            // 否则重连后会从「点进来的那一集第一秒」重新开始
            val first = preparedFor == null
            preparedFor = controller
            onSessionReady(
                if (first) initialIndex else currentIndex,
                if (first) startPositionMs else positionMs,
            )
            controller.setPlaybackSpeed(speed)
        }
    }

    // 投屏期间手机上必须停下来，否则两头同时出声；断开后按投屏前的状态恢复：
    // 投屏前就是暂停的，断开后不能突然自己播起来
    var wasCasting by remember { mutableStateOf(false) }
    var wasPlayingBeforeCast by remember { mutableStateOf(false) }
    LaunchedEffect(castState.casting) {
        if (castState.casting) {
            wasCasting = true
            wasPlayingBeforeCast = controller?.isPlaying == true
            controller?.pause()
        } else if (wasCasting) {
            wasCasting = false
            if (wasPlayingBeforeCast) controller?.play()
            wasPlayingBeforeCast = false
        }
    }

    // 投屏中切集：手机被暂停着，电视还停在上一集，得把新一集接着推过去
    LaunchedEffect(castState.casting) {
        if (!castState.casting) return@LaunchedEffect
        snapshotFlow { currentIndex }.drop(1).collect { index ->
            val device = CastManager.state.value.device ?: return@collect
            val live = mediaItemAt(index) ?: return@collect
            // 网盘集无论是否已在手机端解析过（占位 uri 或已变成完整直链），统一按 fid
            // 让投屏层现取带鉴权头的直链，否则会走到不带 Cookie 的普通 http 分支。
            // fid 从条目自身的 extras 取，避免播放列表重排后取错集。
            val fid = fidOf(index)
            val source = if (fid != null) {
                "quark://$fid"
            } else {
                live.mediaId.ifBlank { live.localConfiguration?.uri?.toString().orEmpty() }
            }
            if (source.isBlank()) return@collect
            CastManager.cast(
                context,
                device,
                source,
                live.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: "正在播放",
            )
        }
    }

    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    controlsVisible = true
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 切到还没解析直链的网盘集时，先静默解析再顶上，不算播放失败
                val idx = controller?.currentMediaItemIndex ?: -1
                if (idx >= 0 && isPendingIndex(idx)) {
                    resolvePending(idx)
                    return
                }
                // 带上底层原因（HTTP 状态码等），方便定位网盘直链的问题
                val cause = error.cause?.message?.takeIf { it.isNotBlank() }
                playbackError = listOfNotNull(error.errorCodeName, cause).joinToString("\n")
                DebugLog.record("player", "error ${error.errorCodeName} · ${cause ?: "no-cause"}")
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // 列表重建 / 清空时 currentMediaItemIndex 可能是 -1，不能直接写进 currentIndex
                val liveIdx = controller?.currentMediaItemIndex ?: -1
                if (liveIdx >= 0) currentIndex = liveIdx
                playbackError = null
                aPointMs = -1L
                bPointMs = -1L
                clearFrameAnchor()
                subtitleTrack = null
                subtitleRestoredFor = null
                subtitleEpoch++
                danmakuItems = emptyList()
                danmakuLoadedLabel = null
                danmakuResults = emptyList()
                danmakuMessage = null
                val nextUri = mediaItem?.mediaId
                if (!nextUri.isNullOrBlank()) episodeToken++
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                onVideoSizeChanged(videoSize.width, videoSize.height)
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                tracksVersion++
            }
        }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }

    // 切集时顺手把前后一集的直链解析好
    LaunchedEffect(controller, currentIndex) {
        val count = controller?.mediaItemCount ?: 0
        if (count <= 0) return@LaunchedEffect
        listOf(currentIndex + 1, currentIndex - 1).forEach { index ->
            if (index in 0 until count) resolvePending(index)
        }
    }

    // 快播完时提前解析下一集，自动连播就不会卡在未解析的条目上
    LaunchedEffect(controller) {
        while (true) {
            val player = controller
            if (player != null) {
                val duration = player.duration
                if (duration > 0 && duration - player.currentPosition in 1..60_000L) {
                    val current = player.currentMediaItemIndex
                    if (current >= 0) {
                        val next = current + 1
                        if (next in 0 until player.mediaItemCount) resolvePending(next)
                    }
                }
            }
            delay(2_500)
        }
    }

    LaunchedEffect(controller) {
        while (true) {
            controller?.let { player ->
                // 逐帧后要等 seek 真正完成，这期间不要用轮询到的旧位置覆盖目标
                if (System.currentTimeMillis() >= frameStepHoldUntil) {
                    positionMs = player.currentPosition.coerceAtLeast(0L)
                }
                durationMs = player.duration.coerceAtLeast(0L)
                bufferedMs = player.bufferedPosition.coerceAtLeast(0L)
                isPlaying = player.isPlaying
                // 恢复播放后逐帧锚点作废，否则之后手动 seek 会被冻结窗口挡住
                if (player.isPlaying && frameAnchorMs != null) clearFrameAnchor()
                if (aPointMs >= 0 && bPointMs > aPointMs && player.currentPosition >= bPointMs) {
                    player.seekTo(aPointMs)
                }
            }
            delay(180)
        }
    }

    LaunchedEffect(controlsVisible, isPlaying) {
        // 拖动进度条时不能自动隐藏：控件一消失，ThinSeekBar 的拖动会被直接截断；
        // 弹层打开时同理，不能把下面那层控件抽走
        if (controlsVisible && isPlaying && seekBarFraction == null && !anySheetOpen) {
            delay(4200)
            // 延迟期间用户可能又开始拖动或打开了弹层，再判一次才真正隐藏
            if (seekBarFraction == null && !anySheetOpen) controlsVisible = false
        }
    }

    LaunchedEffect(hud) {
        if (hud != null) {
            delay(900)
            hud = null
        }
    }

    LaunchedEffect(gestureHint) {
        if (gestureHint != null) {
            delay(1200)
            gestureHint = null
        }
    }

    // 标题 / 集数取「播放器当前真实条目」而不是进入时的 entries：
    // 播放列表里上移、移除之后，entries 的下标已经和实际播放中的条目错位
    val liveIndex = liveIndexSafe(currentIndex)
    val liveCount = controller?.mediaItemCount ?: entries.size
    val title = liveTitle(liveIndex).ifBlank { "正在播放" }
    // 4K 视频不参与玻璃采样：视频层独立绘制，玻璃控件只采样固定深色渐变，
    // 避免把高分辨率画面卷进 GraphicsLayer 采样导致掉帧甚至卡死。
    // 跟随主页配色：深色主题深墨蓝、浅色主题浅蓝渐变，布局与层级不变
    val playerIsDark = GlasmioTheme.colors.isDark
    val playerBackdrop = rememberCanvasBackdrop {
        val base = if (playerIsDark) {
            listOf(Color(0xFF070A12), Color(0xFF101828))
        } else {
            listOf(Color(0xFFF2F5FB), Color(0xFFE3EAF8))
        }
        drawRect(brush = Brush.verticalGradient(base))
        val accent = if (playerIsDark) Color(0xFF2F5CFF) else Color(0xFF9BBCFF)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.45f), Color.Transparent),
                center = Offset(size.width * 0.92f, size.height * 0.10f),
                radius = size.width * 0.85f,
            ),
            radius = size.width * 0.85f,
            center = Offset(size.width * 0.92f, size.height * 0.10f),
        )
    }

    // 画面比例的目标：自定义模式下按 宽:高 计算，其余模式交给 PlayerView 自身
    val customAspect: Float? = if (aspectMode == 4) {
        (aspectW.toFloat() / aspectH.coerceAtLeast(1).toFloat()).takeIf { it > 0f && it.isFinite() }
    } else {
        null
    }
    val viewResizeMode = when (aspectMode) {
        1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        3 -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
        4 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 用 BoxWithConstraints 拿到视频层的真实高度，字幕位置才能按比例换算成 dp；
        // 直接读屏幕高度在画中画/分屏下会和实际画面区域对不上
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val layerHeight = maxHeight
            AndroidView(
                factory = { ctx ->
                    val view = android.view.LayoutInflater.from(ctx)
                        .inflate(com.glassplayer.R.layout.view_player, null) as PlayerView
                    view
                },
                update = { view ->
                    view.player = controller
                    view.resizeMode = viewResizeMode
                },
                // 自定义比例时用 aspectRatio 容器把视频框成目标比例，内部拉伸填满；
                // 双指缩放叠加在同一个图层上，texture_view 支持这种变换。
                modifier = (
                    if (customAspect != null) {
                        Modifier.fillMaxWidth().aspectRatio(customAspect)
                    } else {
                        Modifier.fillMaxSize()
                    }
                    ).graphicsLayer {
                        scaleX = videoZoom
                        scaleY = videoZoom
                    },
            )

            if (!isInPip && danmakuEnabled && danmakuVisible.isNotEmpty()) {
                DanmakuOverlay(
                    items = danmakuVisible,
                    positionMs = (positionMs + danmakuOffsetMs).coerceAtLeast(0L),
                    playing = isPlaying,
                    opacity = danmakuOpacity,
                    fontColor = danmakuColor,
                    textScale = danmakuScale,
                    speedFactor = danmakuSpeed,
                    areaFraction = danmakuArea,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            val subtitleLines = subtitleTrack?.linesAt(
                positionMs + subtitleDelayMs,
                subtitleOnlyLanguage,
            )
            if (subtitleLines != null && subtitleLines.isNotEmpty() && !isInPip) {
                // position=0 → 贴底（距底 12dp，基本贴着手机底部）；1 → 距顶 96dp。
                // 负数继续向下压（最小 -0.3，最多下移约 29dp），允许把字幕压到屏幕边缘。
                val position = subtitlePosition.coerceIn(-0.3f, 1f)
                val baseBottom = 12.dp
                val travel = (layerHeight - baseBottom - 96.dp).coerceAtLeast(0.dp)
                val lift = travel * position.coerceAtLeast(0f)
                val down = if (position < 0f) 96.dp * (-position) else 0.dp
                val primaryFontSize = subtitleLines.maxOf { it.fontSize }.takeIf { it > 0f } ?: 0f
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .offset(y = down)
                        .padding(horizontal = 28.dp)
                        .padding(top = 12.dp, bottom = baseBottom + lift),
                ) {
                    subtitleLines.forEach { line ->
                        // ASS 里中文 80 / 日文 50 这类主次字号差按比例保留；
                        // 单语字幕或 SRT/VTT 没有样式信息时保持原字号。
                        val lineScale = if (primaryFontSize > 0f && line.fontSize > 0f) {
                            (line.fontSize / primaryFontSize).coerceIn(0.75f, 1f)
                        } else {
                            1f
                        }
                        Text(
                            text = line.text,
                            color = Color.White.copy(alpha = subtitleAlpha.coerceIn(0f, 1f)),
                            fontSize = (17f * subtitleScale * lineScale).sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            style = LocalTextStyle.current.copy(
                                // 亮场景下纯白字幕会糊成一片，加一层轻描边阴影
                                shadow = androidx.compose.ui.graphics.Shadow(
                                    color = Color.Black.copy(alpha = 0.75f),
                                    offset = Offset(0f, 2f),
                                    blurRadius = 6f,
                                ),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        GlasmioBackdropProvider(backdrop = playerBackdrop) {
            Box(Modifier.fillMaxSize()) {
                if (isInPip) return@Box

                Box(
                    Modifier
                        .fillMaxSize()
                        .playerGestures(
                            enabled = !locked && settings.gesturesEnabled,
                            durationMs = if (durationMs > 0) durationMs else 1L,
                            currentPosition = { positionMs },
                            onTap = { controlsVisible = !controlsVisible },
                            onDoubleTapCenter = {
                                controller?.let { if (it.isPlaying) it.pause() else it.play() }
                                controlsVisible = true
                                gestureHint = if (isPlaying) "已暂停" else "继续播放"
                            },
                            onDoubleTapLeft = {
                                clearFrameAnchor()
                                controller?.seekTo(max(0L, positionMs - 10_000))
                                controlsVisible = true
                                gestureHint = "快退 10 秒"
                            },
                            onDoubleTapRight = {
                                clearFrameAnchor()
                                controller?.seekTo(
                                    if (durationMs > 0) {
                                        (positionMs + 10_000).coerceAtMost(durationMs)
                                    } else {
                                        positionMs + 10_000
                                    },
                                )
                                controlsVisible = true
                                gestureHint = "快进 10 秒"
                            },
                            onSeekPreview = { target ->
                                seekPreview = target
                                gestureHint = "跳转到 ${formatDuration(target)}"
                            },
                            onSeekFinished = {
                                clearFrameAnchor()
                                seekPreview?.let { controller?.seekTo(it) }
                                seekPreview = null
                            },
                            onBrightnessDelta = { delta ->
                                hud = "亮度" to onBrightnessDelta(delta)
                                controlsVisible = false
                            },
                            onVolumeDelta = { delta ->
                                hud = "音量" to onVolumeDelta(delta)
                                controlsVisible = false
                            },
                            onLongPressStart = {
                                if (controller?.isPlaying == true && !boosting) {
                                    speedBeforeBoost = speed
                                    speed = 2f
                                    boosting = true
                                    controller?.setPlaybackSpeed(2f)
                                    gestureHint = "2× 倍速播放中"
                                }
                            },
                            onLongPressEnd = {
                                if (boosting) {
                                    boosting = false
                                    speed = speedBeforeBoost
                                    controller?.setPlaybackSpeed(speedBeforeBoost)
                                    gestureHint = null
                                }
                            },
                            onPinchZoom = { delta ->
                                videoZoom = (videoZoom * delta).coerceIn(0.5f, 3.0f)
                                controlsVisible = false
                                gestureHint = "画面 ${(videoZoom * 100).roundToInt()}%"
                            },
                        ),
                )

                gestureHint?.let { hint ->
                    GlassSurface(
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 96.dp),
                    ) {
                        Text(
                            text = hint,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                        )
                    }
                }

                hud?.let { (label, fraction) ->
                    GlassSurface(
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(8.dp))
                            Box(
                                Modifier
                                    .width(140.dp)
                                    .height(6.dp)
                                    .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(50)),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                                        .height(6.dp)
                                        .background(Color.White, RoundedCornerShape(50)),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%",
                                color = Color.White.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }

                if (controlsVisible && !locked) {
                    // 顶部：返回 + 文件名 + 画中画 / 更多（纯图标，不铺玻璃卡片）
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent),
                                ),
                            )
                            .windowInsetsPadding(WindowInsets.systemBars)
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FlatIconButton(onClick = onBack) {
                            SfIcon("chevron.left", "返回", size = 22.dp, tint = Color.White)
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { showQueueSheet = true }
                                .padding(horizontal = 6.dp, vertical = 10.dp),
                        )
                        if (liveCount > 1) {
                            Text(
                                text = "${liveIndex + 1}/$liveCount",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )
                        }
                        FlatIconButton(onClick = { showCastSheet = true }) {
                            // 电视+盒子：和画中画的「两个矩形」拉开区别
                            SfIcon(
                                if (castState.casting) "tv.fill" else "tv.and.mediabox",
                                "投屏",
                                size = 20.dp,
                                tint = Color.White,
                            )
                        }
                        FlatIconButton(onClick = onEnterPip) {
                            SfIcon("rectangle.on.rectangle", "画中画", size = 20.dp, tint = Color.White)
                        }
                        FlatIconButton(onClick = { showMoreSheet = true }) {
                            SfIcon("ellipsis", "更多", size = 20.dp, tint = Color.White)
                        }
                    }

                    // 底部：时间 + 细进度条 + 播放控制行
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                                ),
                            )
                            .windowInsetsPadding(WindowInsets.systemBars)
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    ) {
                        val displayPosition = seekBarFraction?.let { (it * durationMs).toLong() } ?: positionMs
                        Text(
                            text = "${formatDuration(displayPosition)} / ${formatDuration(durationMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                        ThinSeekBar(
                            positionMs = positionMs,
                            durationMs = durationMs,
                            bufferedMs = bufferedMs,
                            onFractionChange = { seekBarFraction = it },
                            onSeek = { fraction ->
                                if (durationMs > 0) {
                                    clearFrameAnchor()
                                    controller?.seekTo((fraction * durationMs).toLong())
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Box(Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.align(Alignment.Center),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FlatIconButton(
                                    onClick = { controller?.seekToPreviousMediaItem() },
                                    enabled = controller?.hasPreviousMediaItem() == true,
                                ) {
                                    SfIcon(
                                        "backward.fill",
                                        "上一个",
                                        size = 22.dp,
                                        tint = if (controller?.hasPreviousMediaItem() == true) {
                                            Color.White
                                        } else {
                                            Color.White.copy(alpha = 0.35f)
                                        },
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                FlatIconButton(
                                    onClick = {
                                        controller?.let { if (it.isPlaying) it.pause() else it.play() }
                                    },
                                    size = 48.dp,
                                ) {
                                    SfIcon(
                                        if (isPlaying) "pause.fill" else "play.fill",
                                        if (isPlaying) "暂停" else "播放",
                                        size = 24.dp,
                                        tint = Color.White,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                FlatIconButton(
                                    onClick = { controller?.seekToNextMediaItem() },
                                    enabled = controller?.hasNextMediaItem() == true,
                                ) {
                                    SfIcon(
                                        "forward.fill",
                                        "下一个",
                                        size = 22.dp,
                                        tint = if (controller?.hasNextMediaItem() == true) {
                                            Color.White
                                        } else {
                                            Color.White.copy(alpha = 0.35f)
                                        },
                                    )
                                }
                            }
                            // 左下角：弹幕开关（文字即开关）+ 弹幕设置入口
                            Row(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(start = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "弹幕",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = if (danmakuEnabled) 0.95f else 0.4f),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            danmakuEnabled = !danmakuEnabled
                                            settings.danmakuEnabled = danmakuEnabled
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                )
                                FlatIconButton(
                                    onClick = {
                                        danmakuTab = if (danmakuItems.isNotEmpty()) 1 else 0
                                        showDanmakuSheet = true
                                    },
                                    size = 32.dp,
                                ) {
                                    SfIcon(
                                        "gear",
                                        "弹幕设置",
                                        size = 16.dp,
                                        tint = Color.White.copy(alpha = 0.7f),
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier.align(Alignment.CenterEnd),
                                horizontalAlignment = Alignment.End,
                            ) {
                                // 旋转屏幕：和右侧锁屏键同一套半透明样式，放在倍速上方
                                FlatIconButton(
                                    onClick = onToggleOrientation,
                                    size = 36.dp,
                                ) {
                                    SfIcon(
                                        "arrow.clockwise",
                                        "旋转屏幕",
                                        size = 17.dp,
                                        tint = Color.White.copy(alpha = 0.7f),
                                    )
                                }
                                Text(
                                    text = "倍速 ${"%.2f".format(speed).trimEnd('0').trimEnd('.')}×",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.9f),
                                    modifier = Modifier
                                        .clickable { showSpeedSheet = true }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }

                if (locked || controlsVisible) {
                    // 右侧边缘的锁，和参考图一致：锁上后控件全部隐藏，只留这个解锁键
                    FlatIconButton(
                        onClick = {
                            locked = !locked
                            controlsVisible = !locked
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 10.dp),
                        size = 40.dp,
                    ) {
                        SfIcon(
                            if (locked) "lock.fill" else "lock",
                            if (locked) "解锁" else "锁定",
                            size = 18.dp,
                            tint = Color.White.copy(alpha = if (locked) 1f else 0.7f),
                        )
                    }
                }

                if (controller == null && connectionError != null) {
                    GlassSurface(
                        shape = GlasmioTheme.shapes.medium,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    ) {
                        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "播放服务连接失败",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = connectionError,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(12.dp))
                            GlassPill(text = "重试连接", icon = "arrow.clockwise", onClick = onRetryConnect)
                        }
                    }
                }

                playbackError?.let { message ->
                    GlassSurface(
                        shape = GlasmioTheme.shapes.medium,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    ) {
                        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "无法播放该文件",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(12.dp))
                            GlassPill(
                                text = "用其他应用打开",
                                icon = "arrow.up.right.square",
                                onClick = {
                                    entries.getOrNull(currentIndex)?.let { entry ->
                                        // 云盘占位条目的 uri 是空串 / pending scheme，直接丢给
                                        // 外部应用会崩；优先用播放器里的真实地址
                                        val realUri = controller?.currentMediaItem?.mediaId
                                            ?.takeIf { it.startsWith("content://") || it.startsWith("http") }
                                            ?: entry.uri
                                        if (realUri.isBlank()) return@GlassPill
                                        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(Uri.parse(realUri), "video/*")
                                            addFlags(
                                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                                    Intent.FLAG_ACTIVITY_NEW_TASK,
                                            )
                                        }
                                        runCatching {
                                            context.startActivity(Intent.createChooser(viewIntent, "选择播放器"))
                                        }
                                    }
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                            GlassPill(text = "重试", icon = "arrow.clockwise", onClick = {
                                playbackError = null
                                controller?.prepare()
                                controller?.play()
                            })
                            Spacer(Modifier.height(8.dp))
                            GlassPill(
                                text = "复制调试日志",
                                icon = "doc.on.doc",
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    clipboard?.setPrimaryClip(
                                        ClipData.newPlainText("GlassPlayer 调试日志", DebugLog.snapshot()),
                                    )
                                    gestureHint = "调试日志已复制"
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    fun loadDanmakuMatch(match: DanmakuMatch, forceOrder: Int? = null) {
        if (danmakuBusy) return
        scope.launch {
            danmakuBusy = true
            danmakuMessage = null
            var target = match
            // B 站番剧：点开先展开整季集数；能按当前文件名认出集号就直接取那一集
            if (match.source == BiliDanmakuSource.SOURCE && match.id.startsWith("season:")) {
                val seasonId = match.id.removePrefix("season:")
                danmakuMessage = "正在读取番剧集数…"
                val episodes = runCatching { BiliDanmakuSource.episodes(seasonId) }
                    .getOrElse { e ->
                        danmakuMessage = e.message ?: "读取集数失败"
                        danmakuBusy = false
                        return@launch
                    }
                if (episodes.size > 1) danmakuSeries = episodes
                if (episodes.isEmpty()) {
                    danmakuMessage = "这部番剧没有可用的正片集数"
                    danmakuBusy = false
                    return@launch
                }
                if (forceOrder != null) {
                    target = episodes[forceOrder.coerceIn(0, episodes.lastIndex)]
                    danmakuMessage = "已定位到正片第 ${forceOrder + 1} 集"
                } else {
                    val hint = guessEpisodeIndex(liveTitle(currentIndex))
                    val exact = hint?.let { index ->
                        episodes.firstOrNull { it.subtitle.startsWith("第 $index 集") }
                    }
                    when {
                        exact != null -> {
                            target = exact
                            danmakuMessage = "已按文件名匹配到第 $hint 集"
                        }

                        episodes.size > 1 -> {
                            danmakuResults = episodes
                            danmakuMessage = "${match.title} · 共 ${episodes.size} 集，选择要抓取的一集"
                            danmakuBusy = false
                            return@launch
                        }

                        else -> target = episodes.first()
                    }
                }
            }
            // 优酷：选集结果先展开整季集数，能按文件名认出集号就直接取那一集
            if (match.source == YoukuDanmakuSource.SOURCE && match.id.startsWith("show:")) {
                val showId = match.id.removePrefix("show:")
                danmakuMessage = "正在读取优酷剧集…"
                val episodes = runCatching {
                    YoukuDanmakuSource.episodes(showId) { progress -> danmakuMessage = progress }
                }.getOrElse { e ->
                    danmakuMessage = e.message ?: "读取集数失败"
                    danmakuBusy = false
                    return@launch
                }
                if (episodes.size > 1) danmakuSeries = episodes
                if (episodes.isEmpty()) {
                    danmakuMessage = "没有解析到这部剧的集数，可以直接粘贴某集的 youku.com/v_show 链接"
                    danmakuBusy = false
                    return@launch
                }
                if (forceOrder != null) {
                    target = episodes[forceOrder.coerceIn(0, episodes.lastIndex)]
                    danmakuMessage = "已定位到正片第 ${forceOrder + 1} 集"
                } else {
                    val hint = guessEpisodeIndex(liveTitle(currentIndex))
                    val exact = hint?.let { index ->
                        episodes.firstOrNull { it.subtitle.startsWith("第 $index 集") }
                    }
                    when {
                        exact != null -> {
                            target = exact
                            danmakuMessage = "已按文件名匹配到第 $hint 集"
                        }
                        episodes.size > 1 -> {
                            danmakuResults = episodes
                            danmakuEpisodesMode = true
                            danmakuMessage = "${match.title} · 共 ${episodes.size} 集，选择要抓取的一集"
                            danmakuBusy = false
                            return@launch
                        }
                        else -> target = episodes.first()
                    }
                }
            }
            // 爱奇艺：专辑先展开整季集数，能按文件名认出集号就直接取那一集
            if (match.source == IqiyiDanmakuSource.SOURCE && match.id.startsWith("album:")) {
                val album = match.id.removePrefix("album:")
                danmakuMessage = "正在读取剧集…"
                val episodes = runCatching {
                    IqiyiDanmakuSource.episodes(album) { progress -> danmakuMessage = progress }
                }.getOrElse { e ->
                    danmakuMessage = e.message ?: "读取集数失败"
                    danmakuBusy = false
                    return@launch
                }
                if (episodes.size > 1) danmakuSeries = episodes
                if (episodes.isEmpty()) {
                    danmakuMessage = "没有解析到这部剧的集数，可以直接粘贴某集的 iqiyi.com/v_xxx 链接"
                    danmakuBusy = false
                    return@launch
                }
                if (forceOrder != null) {
                    target = episodes[forceOrder.coerceIn(0, episodes.lastIndex)]
                    danmakuMessage = "已定位到正片第 ${forceOrder + 1} 集"
                } else {
                    val hint = guessEpisodeIndex(liveTitle(currentIndex))
                    val exact = hint?.let { index ->
                        episodes.firstOrNull { it.subtitle.startsWith("第 $index 集") }
                    }
                    when {
                        exact != null -> {
                            target = exact
                            danmakuMessage = "已按文件名匹配到第 $hint 集"
                        }
                        episodes.size > 1 -> {
                            danmakuResults = episodes
                            danmakuEpisodesMode = true
                            danmakuMessage = "${match.title} · 共 ${episodes.size} 集，选择要抓取的一集"
                            danmakuBusy = false
                            return@launch
                        }
                        else -> target = episodes.first()
                    }
                }
            }
            // 腾讯：点开封面先展开整季集数，让用户自己选是哪一集
            if (match.source == TencentDanmakuSource.SOURCE && match.id.contains('|')) {
                val cid = match.id.substringBefore('|')
                val vid = match.id.substringAfter('|')
                danmakuMessage = "正在读取集数…"
                val episodes = runCatching {
                    TencentDanmakuSource.episodes(cid, vid) { progress -> danmakuMessage = progress }
                }
                    .getOrElse { e ->
                        danmakuMessage = e.message ?: "取集数失败"
                        danmakuBusy = false
                        return@launch
                    }
                if (episodes.size > 1) danmakuSeries = episodes
                if (forceOrder != null && episodes.isNotEmpty()) {
                    target = episodes[forceOrder.coerceIn(0, episodes.lastIndex)]
                    danmakuMessage = "已定位到正片第 ${forceOrder + 1} 集"
                } else if (episodes.size > 1) {
                    danmakuResults = episodes
                    danmakuEpisodesMode = true
                    danmakuMessage = "${match.title} · 共 ${episodes.size} 集，选择要抓取的一集"
                    danmakuBusy = false
                    return@launch
                } else {
                    // 取不到集数列表时退回单集，避免整条链路失败
                    target = DanmakuMatch(TencentDanmakuSource.SOURCE, vid, match.title, "单集")
                }
            }
            runCatching {
                when (target.source) {
                    ServerDanmakuSource.SOURCE ->
                        target.title to ServerDanmakuSource.load(danmakuServerUrl, target.id)
                    TencentDanmakuSource.SOURCE ->
                        target.title to TencentDanmakuSource.load(target.id) { progress ->
                            danmakuMessage = progress
                        }
                    IqiyiDanmakuSource.SOURCE ->
                        target.title to IqiyiDanmakuSource.load(target.id.removePrefix("v:")) { progress ->
                            danmakuMessage = progress
                        }
                    YoukuDanmakuSource.SOURCE ->
                        target.title to YoukuDanmakuSource.load(target.id.removePrefix("v:")) { progress ->
                            danmakuMessage = progress
                        }
                    else -> if (target.id.startsWith("cid:")) {
                        BiliDanmakuSource.loadByCid(target.id.removePrefix("cid:"), target.title)
                    } else {
                        BiliDanmakuSource.load(target.id)
                    }
                }
            }.onSuccess { (label, list) ->
                val merged = DanmakuMerge.dedupe(list)
                danmakuItems = merged
                danmakuLoadedLabel = buildString {
                    append(target.title.ifBlank { label })
                    if (target.subtitle.isNotBlank()) append(" · ").append(target.subtitle)
                    append(" · ").append(merged.size).append(" 条")
                }
                danmakuEnabled = true
                settings.danmakuEnabled = true
                danmakuMessage = null
                danmakuSeries.indexOfFirst { it.id == target.id }
                    .takeIf { it >= 0 }
                    ?.let {
                        danmakuSeriesIndex = it
                        // 以当前选定集为锚点：记录播放位置与正片位置，切集按差值顺推
                        danmakuAnchorPlayerIndex = currentIndex
                        danmakuAnchorSeriesIndex = it
                    }
                val uri = controller?.currentMediaItem?.mediaId
                if (!uri.isNullOrBlank()) {
                    val savedLabel = danmakuLoadedLabel ?: label
                    withContext(Dispatchers.IO) {
                        DanmakuCache.save(context, uri, savedLabel, merged)
                    }
                }
                showDanmakuSheet = false
            }.onFailure { e ->
                danmakuMessage = e.message ?: "加载失败"
            }
            danmakuBusy = false
        }
    }

    // ---- 弹幕检索 / 加载 ----
    fun runDanmakuSearch() {
        if (danmakuBusy) return
        val typed = danmakuQuery.trim()
        if (typed.isNotBlank()) settings.danmakuLastQuery = typed
        val fullKeyword = typed.ifBlank { title }
        // 搜索词末尾带集数时（完美世界19），拆成剧名 + 集数，只用剧名去搜，集数稍后定位
        val (keyword, wantedEpisode) = splitQueryEpisode(fullKeyword)
        scope.launch {
            danmakuBusy = true
            danmakuMessage = null
            val primary = runCatching {
                when (danmakuSource) {
                    ServerDanmakuSource.SOURCE -> ServerDanmakuSource.search(danmakuServerUrl, keyword)
                    TencentDanmakuSource.SOURCE -> TencentDanmakuSource.search(keyword) { progress ->
                        danmakuMessage = progress
                    }
                    IqiyiDanmakuSource.SOURCE -> IqiyiDanmakuSource.search(keyword)
                    YoukuDanmakuSource.SOURCE -> YoukuDanmakuSource.search(keyword)
                    else -> BiliDanmakuSource.search(keyword)
                }
            }.getOrElse { e ->
                if (danmakuSource != BiliDanmakuSource.SOURCE) {
                    danmakuMessage = (e.message ?: "这个源没搜到") + "，换个源再试…"
                } else {
                    danmakuMessage = e.message ?: "搜索失败"
                }
                emptyList()
            }
            if (primary.isNotEmpty()) {
                danmakuResults = primary
                danmakuEpisodesMode = false
                danmakuMessage = null
                // 搜索词带集数：展开第一个可整季的结果，直接定位到正片那一集（去掉预告片后的顺序）
                if (wantedEpisode != null) {
                    val expandable = primary.firstOrNull { isExpandableMatch(it) }
                    if (expandable != null) {
                        danmakuBusy = false
                        loadDanmakuMatch(expandable, forceOrder = wantedEpisode - 1)
                        return@launch
                    }
                }
            } else if (danmakuSource != BiliDanmakuSource.SOURCE) {
                // 其他源没搜到时自动兜到 B 站，避免“明明有资源却提示搜不到”
                danmakuMessage = "当前源没搜到，正在试 B 站…"
                val bili = runCatching { BiliDanmakuSource.search(keyword) }.getOrDefault(emptyList())
                danmakuResults = bili
                danmakuEpisodesMode = false
                danmakuMessage = if (bili.isEmpty()) {
                    "两个源都没搜到，可以只留剧名，或粘贴 v.qq.com 播放页链接"
                } else {
                    null
                }
            } else if (danmakuSource == BiliDanmakuSource.SOURCE) {
                // B 站正片没结果（无版权或接口被限流）：自动改查腾讯，别让用户对着空列表
                val biliError = danmakuMessage
                danmakuMessage = "B 站没搜到正片，正在试腾讯…"
                val tencent = runCatching {
                    TencentDanmakuSource.search(keyword) { progress -> danmakuMessage = progress }
                }.getOrDefault(emptyList())
                danmakuResults = tencent
                danmakuEpisodesMode = false
                danmakuMessage = when {
                    tencent.isNotEmpty() -> "B 站没搜到正片，已列出腾讯源结果"
                    biliError != null -> biliError
                    else -> "B 站和腾讯都没搜到正片，可粘贴番剧链接或换个关键词"
                }
            } else {
                danmakuResults = emptyList()
                if (danmakuMessage == null) danmakuMessage = "没有搜到，换个关键词试试"
            }
            danmakuBusy = false
        }
    }

    // 网盘视频没有本地文件指纹，进播放页自动按文件名找一次弹幕
    LaunchedEffect(initialUri) {
        if (initialUri?.startsWith("http") == true && danmakuItems.isEmpty() && !danmakuBusy) {
            runDanmakuSearch()
        }
    }

    // 切集：先恢复这一集自己的弹幕（缓存 / 同目录弹幕文件），没有就自动匹配下一集
    LaunchedEffect(episodeToken) {
        if (episodeToken == 0) return@LaunchedEffect
        val entryTitle = liveTitle(currentIndex)
        // 本地集用 mediaId（就是 content uri）；网盘占位条的 mediaId 是 fid，不能当 uri 用
        val uri = controller?.currentMediaItem?.mediaId
            ?.takeIf { it.startsWith("content://") }
            ?: entries.getOrNull(currentIndex)?.uri?.takeIf { it.startsWith("content://") }
        if (uri == initialUri) return@LaunchedEffect
        if (uri != null) {
            val cached = withContext(Dispatchers.IO) { DanmakuCache.load(context, uri) }
            if (cached != null) {
                danmakuItems = cached.second
                danmakuLoadedLabel = cached.first
                return@LaunchedEffect
            }
            val local = withContext(Dispatchers.IO) { DanmakuLocalFile.load(context, uri) }
            if (local != null) {
                val items = DanmakuMerge.dedupe(local.second)
                danmakuItems = items
                danmakuLoadedLabel = "${local.first} · ${items.size} 条"
                withContext(Dispatchers.IO) {
                    DanmakuCache.save(context, uri, danmakuLoadedLabel ?: local.first, items)
                }
                return@LaunchedEffect
            }
        }
        if (!danmakuAutoNext || danmakuBusy) return@LaunchedEffect
        // 有锚点：按“当前播放位置相对锚点的偏移”取正片列表对应集，
        // 文件名集号与弹幕源集号不一致也能正确顺推（第19集 → 第20集）
        if (danmakuAnchorPlayerIndex >= 0 && danmakuSeries.isNotEmpty()) {
            val targetOrder = (
                danmakuAnchorSeriesIndex + (currentIndex - danmakuAnchorPlayerIndex)
                ).coerceIn(0, danmakuSeries.lastIndex)
            val anchorHit = danmakuSeries.getOrNull(targetOrder)
            if (anchorHit != null) {
                loadDanmakuMatch(anchorHit)
                return@LaunchedEffect
            }
        }
        // 已经匹配过整季时，直接取同一部剧的对应集：先按文件名集号，认不出就顺推一格
        val seriesTitle = danmakuSeries.firstOrNull()?.title.orEmpty()
        val sameShow = seriesTitle.isNotEmpty() && entryTitle.contains(seriesTitle.take(4))
        if (danmakuSeries.isNotEmpty() && (sameShow || guessEpisodeIndex(entryTitle) != null)) {
            val hint = guessEpisodeIndex(entryTitle)
            val byNumber = hint?.let { number ->
                danmakuSeries.firstOrNull { it.subtitle.startsWith("第 $number 集") }
            }
            val cursor = (danmakuSeriesIndex + 1).coerceIn(0, danmakuSeries.lastIndex)
            val byCursor = danmakuSeries.getOrNull(cursor)
            val hit = byNumber ?: byCursor
            if (hit != null) {
                loadDanmakuMatch(hit)
                return@LaunchedEffect
            }
        }
        // 没有整季上下文：清掉旧关键词，按新一集的文件名重新搜一次
        danmakuQuery = ""
        runDanmakuSearch()
    }

    // ---- 返回键优先关弹层，不要连带退出播放 ----
    // anySheetOpen 在上面统一定义（自动隐藏控件也要用），这里只负责关闭优先级
    BackHandler(enabled = anySheetOpen) {
        when {
            showDanmakuSheet -> showDanmakuSheet = false
            showCastSheet -> showCastSheet = false
            showMoreSheet -> showMoreSheet = false
            showQueueSheet -> showQueueSheet = false
            showInfoSheet -> showInfoSheet = false
            showRatioSheet -> showRatioSheet = false
            showSubtitleSheet -> showSubtitleSheet = false
            showAudioSheet -> showAudioSheet = false
            showSpeedSheet -> showSpeedSheet = false
        }
    }

    // ---- Sheets ----
    // 弹层默认从 LocalBlurBackdrop 取采样源，必须由 Provider 包裹，否则会抛异常
    val sheetBackdrop = rememberCanvasBackdrop {
        val base = if (playerIsDark) {
            listOf(Color(0xFF0B0D12), Color(0xFF1B2029))
        } else {
            listOf(Color(0xFFF7FAFE), Color(0xFFE7EEF9))
        }
        drawRect(brush = Brush.verticalGradient(base))
        val accent = if (playerIsDark) Color(0xFF3A66FF) else Color(0xFFAFCBFF)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.5f), Color.Transparent),
                center = Offset(size.width * 0.9f, size.height * 0.05f),
                radius = size.width * 0.7f,
            ),
            radius = size.width * 0.7f,
            center = Offset(size.width * 0.9f, size.height * 0.05f),
        )
    }
    GlasmioBackdropProvider(backdrop = sheetBackdrop) {

    PlayerSheet(
        panelAlpha = sheetOpacity,
        visible = showSpeedSheet,
        onDismissRequest = { showSpeedSheet = false },
        title = "播放速度",
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).forEach { value ->
                SheetRow(
                    title = "${"%.2f".format(value).trimEnd('0').trimEnd('.')}×",
                    selected = speed == value,
                ) {
                    speed = value
                    settings.defaultSpeed = value
                    controller?.setPlaybackSpeed(value)
                    showSpeedSheet = false
                }
            }
        }
    }

    PlayerSheet(
        panelAlpha = sheetOpacity,
        visible = showAudioSheet,
        onDismissRequest = { showAudioSheet = false },
        title = "音轨",
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            val audioOptions = remember(tracksVersion) { audioTracks(controller) }
            if (audioOptions.isEmpty()) {
                SheetRow(title = "该文件只有一条音轨", selected = false) { showAudioSheet = false }
            } else {
                audioOptions.forEach { option ->
                    SheetRow(title = option.label, selected = option.selected) {
                        controller?.trackSelectionParameters = controller.trackSelectionParameters
                            .buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                            .addOverride(TrackSelectionOverride(option.group, option.index))
                            .build()
                        showAudioSheet = false
                    }
                }
            }
        }
    }

    PlayerSheet(
        panelAlpha = sheetOpacity,
        visible = showSubtitleSheet,
        onDismissRequest = { showSubtitleSheet = false },
        title = "字幕",
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 当前字幕文件名：加载成功/自动恢复后都能在这里确认用的是哪个文件
            Text(
                text = subtitleTrack?.let { "字幕文件 · ${it.name}" } ?: "字幕文件 · 未加载",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = if (subtitleTrack != null) 0.7f else 0.42f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitleTrack?.let { track ->
                when {
                    track.languages.size >= 2 -> {
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "字幕语言",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.7f),
                                )
                                Text(
                                    "识别到 ${track.languages.joinToString(" + ") { it.label }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.48f),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                GlassPill(
                                    text = "全部",
                                    emphasized = subtitleOnlyLanguage == null,
                                    onClick = {
                                        subtitleOnlyLanguage = null
                                        settings.subtitleLanguageFilter = ""
                                    },
                                )
                                track.languages.forEach { language ->
                                    GlassPill(
                                        text = language.label,
                                        emphasized = subtitleOnlyLanguage == language,
                                        onClick = {
                                            subtitleOnlyLanguage = language
                                            settings.subtitleLanguageFilter = language.name
                                        },
                                    )
                                }
                            }
                        }
                    }

                    track.languages.size == 1 -> {
                        Text(
                            text = "字幕语言 · ${track.languages.first().label}（单语）",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.42f),
                        )
                    }
                }
            }
            SheetRow(title = "选择字幕文件（SRT / VTT / ASS）", selected = false) {
                subtitleLauncher.launch(
                    arrayOf(
                        "application/x-subrip",
                        "text/vtt",
                        "text/plain",
                        "application/octet-stream",
                        "*/*",
                    ),
                )
            }
            SheetRow(title = "导入字幕文件夹", selected = false) {
                subtitleFolderLauncher.launch(null)
            }
            if (subtitleTrack != null) {
                SheetRow(title = "关闭字幕", selected = false) {
                    subtitleTrack = null
                    subtitleSourceUri = null
                    activeSubtitleFolderTree = null
                    activeSubtitleSeriesToken = null
                    // 记住「这个视频主动关过字幕」，下次点开不要又自动加载回来
                    settings.saveVideoSubtitle(
                        liveKey(liveIndexSafe(currentIndex)),
                        SUBTITLE_OFF,
                    )
                }
                // 紧凑滑杆：标签 / 滑杆 / 数值并排，视觉上只有一条 2dp 细轨 + 5dp 圆点，
                // 不再用大号的 Glasmio 自适应滑杆（之前那版把面板撑得很高）
                SubtitleSliderRow(
                    label = "字号",
                    value = "${(subtitleScale * 100).roundToInt()}%",
                ) {
                    SlimSlider(
                        value = ((subtitleScale - 0.6f) / 1.2f).coerceIn(0f, 1f),
                        onValueChange = {
                            subtitleScale = 0.6f + it * 1.2f
                            settings.subtitleScale = subtitleScale
                        },
                        modifier = Modifier.weight(1f),
                        trackHeight = 20.dp,
                        thumbRadius = 5.dp,
                        barHeight = 2.dp,
                    )
                }
                // 「透明」按字面语义：右边越拖越透明；存的是不透明度，所以这里是 1 - alpha
                SubtitleSliderRow(
                    label = "透明",
                    value = "${((1f - subtitleAlpha).coerceIn(0f, 1f) * 100).roundToInt()}%",
                ) {
                    SlimSlider(
                        value = (1f - subtitleAlpha).coerceIn(0f, 1f),
                        onValueChange = {
                            subtitleAlpha = (1f - it).coerceIn(0f, 1f)
                            settings.subtitleAlpha = subtitleAlpha
                        },
                        modifier = Modifier.weight(1f),
                        trackHeight = 20.dp,
                        thumbRadius = 5.dp,
                        barHeight = 2.dp,
                    )
                }
                // 位置：0 = 贴底（默认），正数向上抬到贴顶；负数继续往下压到屏幕边缘
                SubtitleSliderRow(
                    label = "位置",
                    value = "${(subtitlePosition * 100).roundToInt()}%",
                ) {
                    SlimSlider(
                        value = ((subtitlePosition + 0.3f) / 1.3f).coerceIn(0f, 1f),
                        onValueChange = {
                            subtitlePosition = (-0.3f + it * 1.3f).coerceIn(-0.3f, 1f)
                            settings.subtitlePosition = subtitlePosition
                        },
                        modifier = Modifier.weight(1f),
                        trackHeight = 20.dp,
                        thumbRadius = 5.dp,
                        barHeight = 2.dp,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "字幕延迟 ${subtitleDelayMs} ms",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlasmioTheme.colors.content.copy(alpha = 0.8f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPill(text = "-100ms", onClick = {
                            subtitleDelayMs -= 100
                            settings.subtitleDelayMs = subtitleDelayMs
                        })
                        GlassPill(text = "+100ms", onClick = {
                            subtitleDelayMs += 100
                            settings.subtitleDelayMs = subtitleDelayMs
                        })
                    }
                }
            }

            subtitleLibraryMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.62f),
                )
            }

            if (subtitleFolders.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showSubtitleFolders = !showSubtitleFolders
                            if (!showSubtitleFolders) openedSubtitleFolder = null
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "字幕文件夹 · ${subtitleFolders.size} 个（所有视频通用）",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (showSubtitleFolders) "收起" else "展开",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.45f),
                    )
                }
                if (showSubtitleFolders) {
                    val opened = subtitleFolders.firstOrNull {
                        it.treeUri == openedSubtitleFolder
                    }
                    if (opened == null) {
                        subtitleFolders.forEach { folder ->
                            SubtitleFolderRow(
                                folder = folder,
                                selected = folder.files.any { it.uri == subtitleSourceUri },
                                onClick = { openedSubtitleFolder = folder.treeUri },
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openedSubtitleFolder = null }
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "‹ 返回文件夹",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.55f),
                            )
                        }
                        if (opened.files.isEmpty()) {
                            Text(
                                text = "「${opened.name}」里没有字幕",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.4f),
                            )
                        } else {
                            opened.files.forEach { file ->
                                SubtitleLibraryRow(
                                    file = file,
                                    selected = subtitleSourceUri == file.uri,
                                    onClick = { loadSubtitleFromLibrary(file) },
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "导入一个文件夹，里面的字幕会留在这里，本地和网盘视频都能直接选。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.4f),
                )
            }
        }
    }

    PlayerSheet(
        panelAlpha = sheetOpacity,
        visible = showDanmakuSheet,
        onDismissRequest = { showDanmakuSheet = false },
        title = "弹幕",
        maxContentHeight = 560,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 搜索 / 设置分成两页，避免全挤在一起
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(
                    text = "搜索",
                    emphasized = danmakuTab == 0,
                    onClick = { danmakuTab = 0 },
                )
                GlassPill(
                    text = "设置",
                    emphasized = danmakuTab == 1,
                    onClick = { danmakuTab = 1 },
                )
            }

            if (danmakuTab == 0) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    GlassPill(
                        text = "B站",
                        emphasized = danmakuSource == BiliDanmakuSource.SOURCE,
                        onClick = {
                            danmakuSource = BiliDanmakuSource.SOURCE
                            settings.danmakuSource = danmakuSource
                            danmakuResults = emptyList()
                        danmakuEpisodesMode = false
                            danmakuMessage = null
                        },
                    )
                    GlassPill(
                        text = "腾讯",
                        emphasized = danmakuSource == TencentDanmakuSource.SOURCE,
                        onClick = {
                            danmakuSource = TencentDanmakuSource.SOURCE
                            settings.danmakuSource = danmakuSource
                            danmakuResults = emptyList()
                        danmakuEpisodesMode = false
                            danmakuMessage = null
                        },
                    )
                    GlassPill(
                        text = "爱奇艺",
                        emphasized = danmakuSource == IqiyiDanmakuSource.SOURCE,
                        onClick = {
                            danmakuSource = IqiyiDanmakuSource.SOURCE
                            settings.danmakuSource = danmakuSource
                            danmakuResults = emptyList()
                            danmakuEpisodesMode = false
                            danmakuMessage = null
                        },
                    )
                    GlassPill(
                        text = "优酷",
                        emphasized = danmakuSource == YoukuDanmakuSource.SOURCE,
                        onClick = {
                            danmakuSource = YoukuDanmakuSource.SOURCE
                            settings.danmakuSource = danmakuSource
                            danmakuResults = emptyList()
                            danmakuEpisodesMode = false
                            danmakuMessage = null
                        },
                    )
                    GlassPill(
                        text = "自建服务器",
                        emphasized = danmakuSource == ServerDanmakuSource.SOURCE,
                        onClick = {
                            danmakuSource = ServerDanmakuSource.SOURCE
                            settings.danmakuSource = danmakuSource
                            danmakuResults = emptyList()
                        danmakuEpisodesMode = false
                            danmakuMessage = null
                        },
                    )
                }

                if (danmakuSource == IqiyiDanmakuSource.SOURCE) {
                    Text(
                        "搜索剧名会列出爱奇艺专辑，点进去按集选；也可以直接粘贴 iqiyi.com/v_xxx 某一集的链接。",
                        color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                if (danmakuSource == YoukuDanmakuSource.SOURCE) {
                    Text(
                        "搜剧名会列出优酷剧集并展开整季；也可以直接粘贴 youku.com/v_show/id_xxx 的单集链接。",
                        color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                if (danmakuSource == TencentDanmakuSource.SOURCE) {
                    Text(
                        "搜索剧名会列出整季集数，点进去选哪一集；也可直接把 v.qq.com 播放页链接粘到搜索框。",
                        color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                if (danmakuSource == ServerDanmakuSource.SOURCE) {
                    OutlinedTextField(
                        value = danmakuServerUrl,
                        onValueChange = {
                            danmakuServerUrl = it
                            settings.danmakuServerUrl = it
                        },
                        singleLine = true,
                        placeholder = { Text("https://你的弹幕服务器地址", maxLines = 1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = danmakuQuery,
                        onValueChange = { danmakuQuery = it },
                        singleLine = true,
                        placeholder = { Text(title, maxLines = 1) },
                        modifier = Modifier.weight(1f),
                    )
                    GlassPill(
                        text = if (danmakuBusy) "请稍候" else "搜索",
                        emphasized = true,
                        onClick = { runDanmakuSearch() },
                    )
                }

                danmakuMessage?.let { message ->
                    Text(
                        text = message,
                        color = Color(0xFFFFB86B),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (danmakuResults.isNotEmpty()) {
                    // 只有副标题是“第 N 集”开头才算单集网格；“点开选择集数”这类专辑结果
                    // 虽然也含“集”字，但不是单集，必须走普通列表、点开再展开
                    val looksLikeEpisodes = danmakuEpisodesMode || (
                        danmakuResults.size >= 2 &&
                            danmakuResults.count {
                                Regex("^第 \\d+ 集").containsMatchIn(it.subtitle)
                            } >= danmakuResults.size * 0.6
                        )
                    val shownResults = if (danmakuReverse) danmakuResults.reversed() else danmakuResults
                    if (looksLikeEpisodes) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "共 ${danmakuResults.size} 集",
                                color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            GlassPill(
                                text = if (danmakuReverse) "倒序" else "正序",
                                icon = "arrow.up.arrow.down",
                                emphasized = danmakuReverse,
                                onClick = { danmakuReverse = !danmakuReverse },
                            )
                        }
                        // 正/倒序或新搜索结果变化时，用 key 让 grid 拿到全新滚动状态、从 index 0
                        // 开始；shownResults 已排好序，index 0 正好是正序第 1 集 / 倒序最后一集，
                        // 不再手动 scrollToItem，规避数据切换那一帧的滚动时序竞争
                        key(danmakuReverse, danmakuResults) {
                            val episodeGridState = rememberLazyGridState()
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 120.dp),
                                state = episodeGridState,
                                modifier = Modifier.heightIn(max = 360.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                gridItems(shownResults, key = { "${it.source}-${it.id}" }) { match ->
                                    val itemKey = "${match.source}-${match.id}"
                                    val isSelected = danmakuSelectedKey == itemKey
                                    Text(
                                        text = match.subtitle.ifBlank { match.title },
                                        color = GlasmioTheme.colors.content,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(GlasmioTheme.colors.content.copy(alpha = 0.06f))
                                            .border(
                                                width = if (isSelected) 1.5.dp else 0.dp,
                                                color = if (isSelected) {
                                                    Color(0xFF0A84FF)
                                                } else {
                                                    Color.Transparent
                                                },
                                                shape = RoundedCornerShape(10.dp),
                                            )
                                            .clickable {
                                                danmakuSelectedKey = itemKey
                                                loadDanmakuMatch(match)
                                            }
                                            .padding(horizontal = 6.dp, vertical = 9.dp),
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 300.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(shownResults, key = { "${it.source}-${it.id}" }) { match ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(GlasmioTheme.colors.content.copy(alpha = 0.05f))
                                        .clickable { loadDanmakuMatch(match) }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        text = match.title,
                                        color = GlasmioTheme.colors.content,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (match.subtitle.isNotBlank()) {
                                        Text(
                                            text = match.subtitle,
                                            color = GlasmioTheme.colors.content.copy(alpha = 0.6f),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = danmakuLoadedLabel ?: "还没有加载弹幕",
                        color = GlasmioTheme.colors.content.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    GlassPill(
                        text = if (danmakuEnabled) "已开启" else "已关闭",
                        emphasized = danmakuEnabled,
                        onClick = {
                            danmakuEnabled = !danmakuEnabled
                            settings.danmakuEnabled = danmakuEnabled
                        },
                    )
                }

                Text(
                    "字体颜色",
                    style = MaterialTheme.typography.labelMedium,
                    color = GlasmioTheme.colors.content.copy(alpha = 0.7f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "白色" to Color(0xFFFFFFFF),
                        "黄色" to Color(0xFFFFD60A),
                        "红色" to Color(0xFFFF3B30),
                        "蓝色" to Color(0xFF0A84FF),
                        "绿色" to Color(0xFF34C759),
                        "紫色" to Color(0xFFBF5AF2),
                    ).forEach { (label, color) ->
                        val selected = danmakuColor == color.toArgb()
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(50))
                                .background(color)
                                .border(
                                    width = if (selected) 2.dp else 0.dp,
                                    color = GlasmioTheme.colors.content,
                                    shape = RoundedCornerShape(50),
                                )
                                .clickable {
                                    danmakuColor = color.toArgb()
                                    settings.danmakuColor = danmakuColor
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                SfIcon("checkmark", label, size = 13.dp, tint = Color.Black.copy(alpha = 0.75f))
                            }
                        }
                    }
                }
                Text(
                    "不透明度 ${(danmakuOpacity * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = GlasmioTheme.colors.content.copy(alpha = 0.7f),
                )
                SlimSlider(
                    value = ((danmakuOpacity - 0.15f) / 0.85f).coerceIn(0f, 1f),
                    onValueChange = {
                        danmakuOpacity = 0.15f + it * 0.85f
                        settings.danmakuOpacity = danmakuOpacity
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "字号 ${"%.2f".format(danmakuScale).trimEnd('0').trimEnd('.')}×",
                    style = MaterialTheme.typography.labelMedium,
                    color = GlasmioTheme.colors.content.copy(alpha = 0.7f),
                )
                SlimSlider(
                    value = ((danmakuScale - 0.7f) / 0.9f).coerceIn(0f, 1f),
                    onValueChange = {
                        danmakuScale = 0.7f + it * 0.9f
                        settings.danmakuScale = danmakuScale
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "滚动速度 ${"%.2f".format(danmakuSpeed).trimEnd('0').trimEnd('.')}×",
                    style = MaterialTheme.typography.labelMedium,
                    color = GlasmioTheme.colors.content.copy(alpha = 0.7f),
                )
                SlimSlider(
                    value = ((danmakuSpeed - 0.5f) / 1.5f).coerceIn(0f, 1f),
                    onValueChange = {
                        danmakuSpeed = 0.5f + it * 1.5f
                        settings.danmakuSpeed = danmakuSpeed
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "显示区域 ${(danmakuArea * 100).toInt()}%（屏幕高度）",
                    style = MaterialTheme.typography.labelMedium,
                    color = GlasmioTheme.colors.content.copy(alpha = 0.7f),
                )
                SlimSlider(
                    value = ((danmakuArea - 0.3f) / 0.7f).coerceIn(0f, 1f),
                    onValueChange = {
                        danmakuArea = 0.3f + it * 0.7f
                        settings.danmakuArea = danmakuArea
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "自动匹配",
                    style = MaterialTheme.typography.labelMedium,
                    color = GlasmioTheme.colors.content.copy(alpha = 0.7f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill(
                        text = if (danmakuAutoNext) "切集自动匹配 · 开" else "切集自动匹配 · 关",
                        emphasized = danmakuAutoNext,
                        onClick = {
                            danmakuAutoNext = !danmakuAutoNext
                            settings.danmakuAutoNext = danmakuAutoNext
                        },
                    )
                }
                Text(
                    "显示类型",
                    style = MaterialTheme.typography.labelMedium,
                    color = GlasmioTheme.colors.content.copy(alpha = 0.7f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill(
                        text = "滚动",
                        emphasized = danmakuShowScroll,
                        onClick = {
                            danmakuShowScroll = !danmakuShowScroll
                            settings.danmakuShowScroll = danmakuShowScroll
                        },
                    )
                    GlassPill(
                        text = "顶部",
                        emphasized = danmakuShowTop,
                        onClick = {
                            danmakuShowTop = !danmakuShowTop
                            settings.danmakuShowTop = danmakuShowTop
                        },
                    )
                    GlassPill(
                        text = "底部",
                        emphasized = danmakuShowBottom,
                        onClick = {
                            danmakuShowBottom = !danmakuShowBottom
                            settings.danmakuShowBottom = danmakuShowBottom
                        },
                    )
                }
                OutlinedTextField(
                    value = danmakuBlockWords,
                    onValueChange = {
                        danmakuBlockWords = it
                        settings.danmakuBlockWords = it
                    },
                    singleLine = true,
                    placeholder = { Text("屏蔽词，用逗号分隔，如：前排,打卡", maxLines = 1) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = danmakuBlockRegex,
                    onValueChange = {
                        danmakuBlockRegex = it
                        settings.danmakuBlockRegex = it
                    },
                    singleLine = false,
                    minLines = 1,
                    maxLines = 3,
                    placeholder = { Text("正则屏蔽，一行一条，如：^\\d+$", maxLines = 1) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "已加载 ${danmakuItems.size} 条 · 当前显示 ${danmakuVisible.size} 条",
                    color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.labelSmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "时间偏移 ${danmakuOffsetMs} ms",
                        color = GlasmioTheme.colors.content.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPill(
                            text = "-500ms",
                            onClick = {
                                danmakuOffsetMs -= 500
                                settings.danmakuOffsetMs = danmakuOffsetMs
                            },
                        )
                        GlassPill(
                            text = "+500ms",
                            onClick = {
                                danmakuOffsetMs += 500
                                settings.danmakuOffsetMs = danmakuOffsetMs
                            },
                        )
                    }
                }
                if (danmakuItems.isNotEmpty()) {
                    SheetRow("清空当前弹幕", false) {
                        danmakuItems = emptyList()
                        danmakuLoadedLabel = null
                        danmakuResults = emptyList()
                        danmakuEpisodesMode = false
                        val uri = controller?.currentMediaItem?.mediaId
                        if (!uri.isNullOrBlank()) DanmakuCache.remove(context, uri)
                    }
                }
            }
        }
    }

    PlayerSheet(
        panelAlpha = sheetOpacity,
        visible = showInfoSheet,
        onDismissRequest = { showInfoSheet = false },
        title = "播放信息",
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val videoFormat = selectedFormat(controller, C.TRACK_TYPE_VIDEO)
            InfoRow("文件", title)
            InfoRow("时长", formatDuration(durationMs))
            InfoRow(
                "视频",
                videoFormat?.let {
                    "${it.width.coerceAtLeast(0)} × ${it.height.coerceAtLeast(0)} · ${it.codecs ?: it.sampleMimeType ?: "未知编码"}"
                } ?: "解析中",
            )
            InfoRow("帧率", videoFormat?.frameRate?.takeIf { it > 0f }?.let { "%.2f fps".format(it) } ?: "未知")
            InfoRow("码率", videoFormat?.bitrate?.takeIf { it > 0 }?.let { "${it / 1000} kbps" } ?: "未知")
            InfoRow("播放倍速", "${"%.2f".format(speed).trimEnd('0').trimEnd('.')}×")
            InfoRow("已缓存", formatDuration(bufferedMs))
            InfoRow("字幕", subtitleTrack?.let { "${it.name}（${it.cues.size} 条）" } ?: "未加载")
        }
    }

    PlayerSheet(
        panelAlpha = sheetOpacity,
        visible = showCastSheet,
        onDismissRequest = { showCastSheet = false },
        title = "投屏",
    ) {
        CastPanel(
            castState = castState,
            onCast = { device ->
                val index = liveIndexSafe(currentIndex)
                val fid = fidOf(index)
                // 网盘集无论是否已在手机端解析过，统一按 fid 让投屏层现取带鉴权头的
                // 直链（要带 Cookie/客户端 UA）；已解析的完整直链不能直接用，会丢鉴权
                val source = if (fid != null) {
                    "quark://$fid"
                } else {
                    entries.getOrNull(index)?.uri.orEmpty()
                }
                if (source.isNotBlank()) {
                    val castTitle = liveTitle(index).ifBlank { "正在播放" }
                    // 花屏诊断：把素材编码规格留在日志里，便于判断电视解码器是否认这个格式
                    runCatching {
                        val group = controller?.currentTracks?.groups
                            ?.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.mediaTrackGroup
                        val fmt = group?.let { g ->
                            (0 until g.length).mapNotNull { i -> g.getFormat(i) }.firstOrNull()
                        }
                        DebugLog.record(
                            "cast",
                            "投屏素材 · 编码=${fmt?.sampleMimeType ?: "未知"} · " +
                                "分辨率=${fmt?.width ?: 0}x${fmt?.height ?: 0} · 码率=${fmt?.bitrate ?: 0} · 设备=${device.name}",
                        )
                    }
                    scope.launch { CastManager.cast(context, device, source, castTitle) }
                }
            },
            onTogglePlay = { CastManager.setPaused(castState.playing) },
            onSeek = { CastManager.seekTo(it) },
            onDisconnect = { CastManager.stop() },
        )
    }

    PlayerSheet(
        panelAlpha = sheetOpacity,
        visible = showMoreSheet,
        onDismissRequest = { showMoreSheet = false },
        title = "更多",
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            // 面板透明度：用细滑杆，不占地方，调的时候能实时看到另一侧画面
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "面板透明度",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlasmioTheme.colors.content,
                )
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    SlimSlider(
                        value = ((sheetOpacity - 0.35f) / 0.65f).coerceIn(0f, 1f),
                        onValueChange = { v ->
                            sheetOpacity = 0.35f + v * 0.65f
                            settings.playerSheetOpacity = sheetOpacity
                            controlsVisible = false
                        },
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "${(sheetOpacity * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = GlasmioTheme.colors.content.copy(alpha = 0.65f),
                    modifier = Modifier.width(40.dp),
                )
            }
            SheetRow("播放速度 · ${"%.2f".format(speed).trimEnd('0').trimEnd('.')}×", false) {
                showMoreSheet = false
                showSpeedSheet = true
            }
            SheetRow(
                "字幕 · ${subtitleTrack?.name?.substringBeforeLast('.') ?: "未加载"}",
                subtitleTrack != null,
            ) {
                showMoreSheet = false
                showSubtitleSheet = true
            }
            SheetRow(
                "弹幕 · ${
                    when {
                        !danmakuEnabled -> "已关闭"
                        danmakuItems.isNotEmpty() -> "${danmakuItems.size} 条"
                        else -> "未加载"
                    }
                }",
                danmakuItems.isNotEmpty() && danmakuEnabled,
            ) {
                showMoreSheet = false
                danmakuTab = if (danmakuItems.isNotEmpty()) 1 else 0
                showDanmakuSheet = true
            }
            SheetRow("音轨 · 选择音轨", false) {
                showMoreSheet = false
                showAudioSheet = true
            }
            SheetRow(
                "画面比例 · ${
                    when (aspectMode) {
                        1 -> "缩放铺满"
                        2 -> "拉伸铺满"
                        3 -> "固定宽度"
                        4 -> "${aspectW}:${aspectH}"
                        else -> "适应屏幕"
                    }
                }",
                false,
            ) {
                showMoreSheet = false
                showRatioSheet = true
            }
            SheetRow("旋转屏幕", false) {
                showMoreSheet = false
                onToggleOrientation()
            }
            SheetRow(
                "A-B 循环 · ${
                    when {
                        aPointMs >= 0 && bPointMs > aPointMs -> "循环中"
                        aPointMs >= 0 -> "A 点已设"
                        else -> "未启用"
                    }
                }",
                aPointMs >= 0,
            ) {
                when {
                    aPointMs < 0 -> aPointMs = positionMs
                    bPointMs <= aPointMs -> bPointMs = positionMs.coerceAtLeast(aPointMs + 1000)
                    else -> {
                        aPointMs = -1L
                        bPointMs = -1L
                    }
                }
            }
            SheetRow("锁定屏幕", locked) {
                showMoreSheet = false
                locked = true
                controlsVisible = false
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "逐帧",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlasmioTheme.colors.content,
                    modifier = Modifier.weight(1f),
                )
                GlassPill(text = "上一帧", onClick = { stepFrame(-1) })
                Spacer(Modifier.width(8.dp))
                GlassPill(text = "下一帧", onClick = { stepFrame(1) })
            }
            SheetRow("视频信息", false) {
                showMoreSheet = false
                showInfoSheet = true
            }
            SheetRow("播放列表 · ${liveIndex + 1}/$liveCount", false) {
                showMoreSheet = false
                showQueueSheet = true
            }
        }
    }

    // 画面比例：预设 + 自定义宽高 + 画面大小（双指也可以调）
    PlayerSheet(
        panelAlpha = sheetOpacity,
        visible = showRatioSheet,
        onDismissRequest = { showRatioSheet = false },
        title = "画面比例",
        maxContentHeight = 600,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                "画面大小 ${(videoZoom * 100).roundToInt()}%",
                style = MaterialTheme.typography.titleSmall,
                color = GlasmioTheme.colors.content,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "播放时也可以直接在画面上双指捏合缩放：收缩变小、放开变大。",
                style = MaterialTheme.typography.labelSmall,
                color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(
                    text = "缩小",
                    onClick = { videoZoom = (videoZoom - 0.1f).coerceIn(0.5f, 3f) },
                )
                GlassPill(
                    text = "放大",
                    onClick = { videoZoom = (videoZoom + 0.1f).coerceIn(0.5f, 3f) },
                )
                GlassPill(
                    text = "100%",
                    emphasized = kotlin.math.abs(videoZoom - 1f) < 0.01f,
                    onClick = { videoZoom = 1f },
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "显示模式",
                style = MaterialTheme.typography.labelMedium,
                color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
            )
            Spacer(Modifier.height(6.dp))
            val modeOptions = listOf(
                0 to "适应屏幕",
                1 to "缩放铺满",
                2 to "拉伸铺满",
                3 to "固定宽度",
                4 to "自定义比例",
            )
            modeOptions.chunked(3).forEach { chunk ->
                Row(
                    modifier = Modifier.padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    chunk.forEach { (mode, label) ->
                        GlassPill(
                            text = label,
                            emphasized = aspectMode == mode,
                            onClick = { applyAspect(mode) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "自定义比例",
                style = MaterialTheme.typography.labelMedium,
                color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = aspectW.toString(),
                    onValueChange = { text ->
                        val v = text.filter { it.isDigit() }.take(3).toIntOrNull() ?: 1
                        applyAspect(4, v.coerceIn(1, 100), aspectH)
                    },
                    singleLine = true,
                    label = { Text("宽") },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    " : ",
                    color = GlasmioTheme.colors.content,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                OutlinedTextField(
                    value = aspectH.toString(),
                    onValueChange = { text ->
                        val v = text.filter { it.isDigit() }.take(3).toIntOrNull() ?: 1
                        applyAspect(4, aspectW, v.coerceIn(1, 100))
                    },
                    singleLine = true,
                    label = { Text("高") },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            listOf(16 to 9, 4 to 3, 21 to 9, 1 to 1, 9 to 16).chunked(3).forEach { chunk ->
                Row(
                    modifier = Modifier.padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    chunk.forEach { (w, h) ->
                        GlassPill(
                            text = "$w:$h",
                            emphasized = aspectMode == 4 && aspectW == w && aspectH == h,
                            onClick = { applyAspect(4, w, h) },
                        )
                    }
                }
            }
            Text(
                "自定义比例会按宽高把画面拉伸填满屏幕，适合纠正变形片源或对齐老片比例。",
                style = MaterialTheme.typography.labelSmall,
                color = GlasmioTheme.colors.content.copy(alpha = 0.45f),
            )
        }
    }

    PlayerSheet(
        panelAlpha = sheetOpacity,
        visible = showQueueSheet,
        onDismissRequest = { showQueueSheet = false },
        title = "播放列表 · ${controller?.mediaItemCount ?: entries.size} 个视频",
        maxContentHeight = 520,
    ) {
        val liveQueue: List<PlaylistEntry> = remember(queueVersion, controller) {
            val c = controller
            if (c == null) {
                entries
            } else {
                (0 until c.mediaItemCount).mapNotNull { i ->
                    // 列表可能正在被增删，count 与 getMediaItemAt 之间仍可能变化
                    val item = runCatching { c.getMediaItemAt(i) }.getOrNull()
                        ?: return@mapNotNull null
                    PlaylistEntry(
                        uri = item.mediaId,
                        title = item.mediaMetadata.title?.toString() ?: "",
                    )
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                "v$versionName · 识别 ${entries.size} 集 · 已就绪 ${controller?.mediaItemCount ?: 0} 集\n" +
                    "右侧按钮上移 / 下移 / 移出列表；点条目直接跳过去播放。",
                style = MaterialTheme.typography.labelSmall,
                color = GlasmioTheme.colors.content.copy(alpha = 0.5f),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.heightIn(max = 430.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(liveQueue, key = { i, e -> "$i-${e.uri}" }) { index, entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (index == currentIndex) GlasmioTheme.colors.content.copy(alpha = 0.10f)
                                else GlasmioTheme.colors.content.copy(alpha = 0.03f),
                            )
                            .clickable {
                                if (isPendingIndex(index)) {
                                    scope.launch {
                                        ensurePlayable(index)
                                        controller?.seekToDefaultPosition(index)
                                    }
                                } else {
                                    controller?.seekToDefaultPosition(index)
                                }
                                showQueueSheet = false
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = GlasmioTheme.colors.content.copy(alpha = 0.6f),
                            modifier = Modifier.width(26.dp),
                        )
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = GlasmioTheme.colors.content,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (index == currentIndex) {
                            SfIcon("speaker.wave.2.fill", "正在播放", size = 13.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        FlatIconButton(
                            onClick = {
                                controller?.moveMediaItem(index, index - 1)
                                if (index == currentIndex) currentIndex = index - 1
                                queueVersion++
                            },
                            enabled = index > 0,
                            size = 30.dp,
                        ) {
                            SfIcon(
                                "chevron.up",
                                "上移",
                                size = 15.dp,
                                tint = GlasmioTheme.colors.content.copy(alpha = if (index > 0) 0.75f else 0.25f),
                            )
                        }
                        FlatIconButton(
                            onClick = {
                                controller?.moveMediaItem(index, index + 1)
                                if (index == currentIndex) currentIndex = index + 1
                                queueVersion++
                            },
                            enabled = index < liveQueue.size - 1,
                            size = 30.dp,
                        ) {
                            SfIcon(
                                "chevron.down",
                                "下移",
                                size = 15.dp,
                                tint = GlasmioTheme.colors.content.copy(alpha = if (index < liveQueue.size - 1) 0.75f else 0.25f),
                            )
                        }
                        FlatIconButton(
                            onClick = {
                                controller?.removeMediaItem(index)
                                if (index < currentIndex) currentIndex--
                                queueVersion++
                            },
                            size = 30.dp,
                        ) {
                            SfIcon("xmark", "移出列表", size = 13.dp, tint = GlasmioTheme.colors.content.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun FlatIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 44.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun ThinSeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onFractionChange: (Float?) -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction
        ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Box(
        modifier = modifier
            .height(30.dp)
            .pointerInput(durationMs) {
                if (durationMs <= 0) return@pointerInput
                detectTapGestures { offset ->
                    if (size.width > 0) {
                        onSeek((offset.x / size.width).coerceIn(0f, 1f))
                    }
                }
            }
            .pointerInput(durationMs) {
                if (durationMs <= 0) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (size.width > 0) {
                            val value = (offset.x / size.width).coerceIn(0f, 1f)
                            dragFraction = value
                            onFractionChange(value)
                        }
                    },
                    onDragEnd = {
                        dragFraction?.let { onSeek(it) }
                        dragFraction = null
                        onFractionChange(null)
                    },
                    onDragCancel = {
                        dragFraction = null
                        onFractionChange(null)
                    },
                ) { change, _ ->
                    if (size.width > 0) {
                        change.consume()
                        val value = (change.position.x / size.width).coerceIn(0f, 1f)
                        dragFraction = value
                        onFractionChange(value)
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val centerY = size.height / 2f
            val trackHeight = 2.dp.toPx()
            drawLine(
                color = Color.White.copy(alpha = 0.28f),
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = trackHeight,
            )
            if (durationMs > 0 && bufferedMs > 0) {
                val bufferedX = size.width * (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)
                drawLine(
                    color = Color.White.copy(alpha = 0.45f),
                    start = Offset(0f, centerY),
                    end = Offset(bufferedX, centerY),
                    strokeWidth = trackHeight,
                )
            }
            drawLine(
                color = Color.White,
                start = Offset(0f, centerY),
                end = Offset(size.width * fraction, centerY),
                strokeWidth = trackHeight,
            )
            drawCircle(
                color = Color.White,
                radius = 6.dp.toPx(),
                center = Offset(size.width * fraction, centerY),
            )
        }
    }
}

@Composable
private fun GlassPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    emphasized: Boolean = false,
) {
    GlassSurface(
        modifier = modifier.height(34.dp),
        shape = RoundedCornerShape(50),
        emphasis = if (emphasized) GlassEmphasis.Prominent else GlassEmphasis.Regular,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                SfIcon(icon, null, size = 15.dp)
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SheetRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            modifier = Modifier.weight(1f),
            maxLines = 2,
        )
        if (selected) {
            SfIcon("checkmark", "已选", size = 18.dp)
        }
    }
}

@Composable
private fun SubtitleFolderRow(
    folder: SubtitleFolder,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${folder.files.size} 个字幕",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.4f),
            )
        }
        if (selected) {
            SfIcon("checkmark", "已选", size = 16.dp, tint = Color.White.copy(alpha = 0.9f))
        }
        SfIcon("chevron.right", "进入", size = 14.dp, tint = Color.White.copy(alpha = 0.35f))
    }
}

@Composable
private fun SubtitleLibraryRow(
    file: SubtitleFile,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (file.folder.isNotBlank()) {
                Text(
                    text = file.folder,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            SfIcon("checkmark", "已选", size = 16.dp, tint = Color.White.copy(alpha = 0.9f))
        }
    }
}

/** 字幕文件名去掉集号后的系列标识：Show.S01E02.ass → show.s#e#。 */
private fun subtitleSeriesToken(name: String): String {
    val stem = name.substringBeforeLast('.', name)
    return stem.lowercase()
        .replace(Regex("\\d+"), "#")
        .replace(Regex("[\\s._\\-\\[\\]()【】]+"), " ")
        .trim()
}

/** 0~1 的粗相似度：完全一致最强，包含次之，再退回按词重合。 */
private fun seriesSimilarity(left: String?, right: String): Float {
    if (left.isNullOrBlank() || right.isBlank()) return 0f
    if (left == right) return 1f
    if (left.contains(right) || right.contains(left)) return 0.7f
    val leftWords = left.split(' ').filter { it.isNotBlank() }.toSet()
    val rightWords = right.split(' ').filter { it.isNotBlank() }.toSet()
    if (leftWords.isEmpty() || rightWords.isEmpty()) return 0f
    val common = leftWords.count { it in rightWords }
    val union = leftWords.size + rightWords.size - common
    return if (union > 0) 0.5f * common / union else 0f
}

/** 字幕记忆里的「用户主动关闭」标记：下次打开同一个视频不要又自动加载。 */
private const val SUBTITLE_OFF = "-"

@Composable
private fun SubtitleSliderRow(
    label: String,
    value: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = GlasmioTheme.colors.content.copy(alpha = 0.7f),
            modifier = Modifier.width(28.dp),
        )
        content()
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.labelSmall,
                color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                textAlign = TextAlign.End,
                modifier = Modifier.width(40.dp),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.6f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 24.dp),
        )
    }
}

private fun audioTracks(controller: MediaController?): List<TrackOption> {
    val groups = controller?.currentTracks?.groups ?: return emptyList()
    val options = ArrayList<TrackOption>()
    groups.forEach { group ->
        if (group.type == C.TRACK_TYPE_AUDIO) {
            for (index in 0 until group.length) {
                val format = group.mediaTrackGroup.getFormat(index)
                val label = format.label
                    ?: format.language?.uppercase()
                    ?: "音轨 ${options.size + 1}"
                options += TrackOption(
                    label = label,
                    group = group.mediaTrackGroup,
                    index = index,
                    selected = group.isTrackSelected(index),
                )
            }
        }
    }
    return options
}

private fun selectedFormat(
    controller: MediaController?,
    trackType: Int,
): androidx.media3.common.Format? {
    val groups = controller?.currentTracks?.groups ?: return null
    groups.forEach { group ->
        if (group.type == trackType) {
            for (index in 0 until group.length) {
                if (group.isTrackSelected(index)) {
                    return group.mediaTrackGroup.getFormat(index)
                }
            }
            if (group.length > 0) {
                return group.mediaTrackGroup.getFormat(0)
            }
        }
    }
    return null
}


/** 从本地文件名猜集号：支持「第05集」「第5话」「EP05」「S01E05」「xxx-05」。 */
private fun guessEpisodeIndex(title: String?): Int? {
    if (title.isNullOrBlank()) return null
    val patterns = listOf(
        Regex("第\\s*(\\d{1,4})\\s*[集话話]"),
        Regex("(?i)S\\d{1,2}E(\\d{1,3})"),
        Regex("(?i)(?:^|[^A-Za-z])E(\\d{1,3})"),
        Regex("[_\\-\\s.](\\d{1,4})\\s*$"),
    )
    for (pattern in patterns) {
        val value = pattern.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
        if (value in 1..999) return value
    }
    return null
}

/** 把“完美世界19”拆成剧名“完美世界”和正片集数 19；没有末尾数字就返回 null。 */
private fun splitQueryEpisode(raw: String): Pair<String, Int?> {
    val match = Regex("^(.*?)(\\d{1,4})$").find(raw.trim()) ?: return raw.trim() to null
    val base = match.groupValues[1].trim()
    val episode = match.groupValues[2].toIntOrNull()
    if (base.isBlank() || episode == null || episode !in 1..999) return raw.trim() to null
    return base to episode
}

/** 这个搜索结果能不能展开成整季（B站 season / 优酷 show / 爱奇艺 album / 腾讯带 cid|vid）。 */
private fun isExpandableMatch(match: DanmakuMatch): Boolean {
    if (match.source == BiliDanmakuSource.SOURCE) return match.id.startsWith("season:")
    if (match.source == YoukuDanmakuSource.SOURCE) return match.id.startsWith("show:")
    if (match.source == IqiyiDanmakuSource.SOURCE) return match.id.startsWith("album:")
    if (match.source == TencentDanmakuSource.SOURCE) return match.id.contains('|')
    return false
}

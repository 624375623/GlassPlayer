package com.glassplayer.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.content.ContextCompat
import com.glassplayer.data.Diagnostics
import com.glassplayer.data.MediaRepository
import com.glassplayer.data.SettingsStore
import com.glassplayer.data.VideoFolder
import com.glassplayer.data.VideoItem
import com.glassplayer.data.formatAgo
import com.glassplayer.data.formatDuration
import com.glassplayer.data.formatSize
import kotlinx.coroutines.launch
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.glass.GlasmioBackdropHost
import io.github.neoruaa.glasmio.glass.trackBackdropPosition
import io.github.neoruaa.glasmio.glass.render.backdrops.layerBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberCombinedBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.ui.GlasmioAdaptiveSlider
import io.github.neoruaa.glasmio.ui.GlasmioNavigationBar
import io.github.neoruaa.glasmio.ui.GlasmioTabItem
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioIconButton

private const val TAB_ALL = 0
/** 把网盘条目伪装成媒体库条目，收藏/记录/详情页就能直接复用。 */
private fun com.glassplayer.data.CloudVideo.toVideoItem(): VideoItem {
    val ext = name.substringAfterLast('.', "mp4").lowercase()
    return VideoItem(
        id = fid.hashCode().toLong(),
        uri = key,
        title = name,
        durationMs = durationMs,
        sizeBytes = size,
        dateAddedSec = updatedAt,
        width = 0,
        height = 0,
        bucketId = -1L,
        folderName = parentName,
        relativePath = "夸克网盘 / $parentName",
        mimeType = "video/$ext",
        cloudDir = isDir,
    )
}

private val VideoItem.isCloud: Boolean get() = uri.startsWith("quark://")

private const val TAB_FOLDERS = 1
private const val TAB_FAVORITES = 2
private const val TAB_HISTORY = 3
private const val TAB_CLOUD = 4
private const val TAB_RESOURCE = 5

@Composable
fun LibraryScreen(
    settings: SettingsStore,
    isDark: Boolean,
    themeMode: Int,
    refreshKey: Int,
    onThemeModeChange: (Int) -> Unit,
    onOpenVideo: (List<VideoItem>, Int) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { MediaRepository(context) }

    var hasPermission by remember { mutableStateOf(hasVideoPermission(context)) }
    var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var settingsVersion by remember { mutableIntStateOf(0) }
    // 自定义背景图：内容 URI + 持久化读权限，重启后仍然有效
    var backgroundUri by remember { mutableStateOf(settings.backgroundUri) }
    val backgroundImage = rememberBackgroundImage(backgroundUri)
    val backgroundScope = rememberCoroutineScope()
    val backgroundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            // 有的相册不支持持久授权，所以另存一份到应用私有目录，重启后仍能读到。
            // 解码 + 压缩是大图重活，必须离开主线程，否则选完图会卡住半秒甚至 OOM。
            backgroundScope.launch {
                val saved = saveBackgroundImage(context, uri)
                settings.backgroundUri = if (saved) uri.toString() else null
                backgroundUri = settings.backgroundUri
                settingsVersion++
            }
        }
    }
    var allFolders by remember { mutableStateOf<List<VideoFolder>>(emptyList()) }
    var showFolderSheet by remember { mutableStateOf(false) }
    var minDurationPreview by remember { mutableFloatStateOf(settings.minDurationSec.toFloat()) }
    var sortAscending by remember { mutableStateOf(settings.sortAscending) }
    var gridLayout by remember { mutableStateOf(settings.gridLayout) }
    // 8000+ 条播放进度/播放记录放在 IO 线程读，避免回到媒体库时主线程一次性拷贝 prefs
    var positions by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var lastPlayed by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var tab by remember { mutableIntStateOf(TAB_FOLDERS) }
    // 网盘内嵌在主页面：状态放在这一层，切 tab 回来不用重新登录/重新拉列表
    val cloudState = rememberCloudState(settings)
    var sortMode by remember { mutableIntStateOf(settings.sortMode) }
    var searchActive by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selectedFolder by remember { mutableStateOf<VideoFolder?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    var detailVideo by remember { mutableStateOf<VideoItem?>(null) }
    var detailList by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var showSortSheet by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var diagnosticsText by remember { mutableStateOf("正在检测…") }
    val diagnosticsScope = rememberCoroutineScope()
    var favorites by remember { mutableStateOf(settings.favorites()) }
    var cloudItems by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var localRefresh by remember { mutableIntStateOf(0) }
    // 多选模式：仅用于收藏、清除播放进度/记录，不修改或删除源文件
    var selectionMode by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 系统返回键在当前页面内的优先级：先退选择态 → 再关搜索 → 再退出文件夹 → 最后才交给系统
    BackHandler(enabled = selectionMode || searchActive || selectedFolder != null) {
        when {
            selectionMode -> {
                selectionMode = false
                selectedUris = emptySet()
            }
            searchActive -> {
                searchActive = false
                query = ""
            }
            selectedFolder != null -> selectedFolder = null
        }
    }

    var cloudSheetOpen by remember { mutableStateOf(false) }
    var resourceSheetOpen by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasPermission = hasVideoPermission(context)
    }

    LaunchedEffect(hasPermission, refreshKey, localRefresh) {
        if (!hasPermission) return@LaunchedEffect
        loading = true
        // 先把历史版本存在纯 fid 下的网盘进度搬成 quark://fid，记录页才能显示出来
        withContext(Dispatchers.IO) { settings.migrateCloudPlaybackKeys() }
        val loaded = repository.loadVideos()
        allFolders = repository.foldersOf(loaded)
        val hidden = settings.hiddenFolders
        val minDurationMs = settings.minDurationSec * 1000L
        videos = loaded.filter { item ->
            item.durationMs >= minDurationMs && item.folderName !in hidden
        }
        favorites = settings.favorites()
        positions = withContext(Dispatchers.IO) { settings.progressSnapshot() }
        lastPlayed = withContext(Dispatchers.IO) { settings.lastPlayedSnapshot() }
        cloudItems = withContext(Dispatchers.IO) {
            settings.cloudVideos().map { it.toVideoItem() }
        }
        loading = false
    }

    LaunchedEffect(tab, refreshKey, localRefresh) {
        withContext(Dispatchers.IO) { settings.migrateCloudPlaybackKeys() }
        favorites = settings.favorites()
        positions = withContext(Dispatchers.IO) { settings.progressSnapshot() }
        lastPlayed = withContext(Dispatchers.IO) { settings.lastPlayedSnapshot() }
        cloudItems = withContext(Dispatchers.IO) {
            settings.cloudVideos().map { it.toVideoItem() }
        }
    }

    LaunchedEffect(showDiagnostics) {
        if (showDiagnostics) {
            diagnosticsText = Diagnostics.run(context)
        }
    }

    val folders = remember(videos) { repository.foldersOf(videos) }

    val filterAndSort: (List<VideoItem>) -> List<VideoItem> = { source ->
        val filtered = if (query.isBlank()) source else source.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.folderName.contains(query, ignoreCase = true)
        }
        sortVideos(filtered, sortMode, sortAscending)
    }

    val allFiltered = remember(videos, query, sortMode, sortAscending) { filterAndSort(videos) }
    val favoriteList = remember(videos, cloudItems, favorites, query, sortMode, sortAscending) {
        filterAndSort((videos + cloudItems).filter { favorites.contains(it.uri) })
    }
    val continueWatching = remember(videos, positions) {
        videos.filter { item ->
            val progress = positions[item.uri] ?: 0L
            progress > 60_000 && item.durationMs > 0 && progress < item.durationMs - 15_000
        }.sortedByDescending { positions[it.uri] ?: 0L }.take(8)
    }
    // 排序选项集中在这里。原来 sortLabel 只列了 4 项再 coerceIn(0, 3)，
    // 选「分辨率」「路径」时会错显示成「大小」。
    val sortOptions = listOf(
        0 to "日期",
        1 to "标题",
        2 to "时长",
        3 to "大小",
        4 to "分辨率",
        5 to "路径",
        6 to "数字优先",
        7 to "首字母",
    )
    val sortLabel = sortOptions.firstOrNull { it.first == sortMode }?.second ?: "日期"
    val historyList = remember(videos, cloudItems, lastPlayed, positions) {
        (videos + cloudItems).filter { item ->
            lastPlayed.containsKey(item.uri) && (positions[item.uri] ?: 0L) > 0
        }.sortedByDescending { lastPlayed[it.uri] ?: 0L }
    }

    fun shiftTab(delta: Int) {
        val order = listOf(TAB_ALL, TAB_FOLDERS, TAB_CLOUD, TAB_RESOURCE, TAB_FAVORITES, TAB_HISTORY)
        val current = order.indexOf(tab).coerceAtLeast(0)
        val next = (current + delta).coerceIn(0, order.lastIndex)
        if (next != current) {
            tab = order[next]
            selectedFolder = null
        }
    }

    fun toggleSelection(uri: String) {
        selectedUris = if (uri in selectedUris) selectedUris - uri else selectedUris + uri
        if (selectedUris.isEmpty()) selectionMode = false
    }

    fun handleItemClick(list: List<VideoItem>, index: Int, uri: String) {
        if (selectionMode) {
            toggleSelection(uri)
            return
        }
        val item = list.getOrNull(index)
        if (item != null && item.cloudDir) {
            cloudState.crumbs = listOf(
                "0" to "我的网盘",
                item.uri.removePrefix("quark://") to item.title,
            )
            tab = TAB_CLOUD
            selectedFolder = null
            return
        }
        onOpenVideo(list, index)
    }

    fun handleItemLong(item: VideoItem) {
        if (selectionMode) {
            toggleSelection(item.uri)
        } else {
            selectionMode = true
            selectedUris = setOf(item.uri)
        }
    }

    val anySheetOpen = showFolderSheet || showSettings || showSortSheet || showDiagnostics ||
        detailVideo != null || cloudSheetOpen || resourceSheetOpen

    // 列表内容单独记录一层，供底部玻璃采样——这样玻璃折射的是「正在滚动的真实内容」，
    // 而不是只折射一层静态壁纸。
    val contentBackdrop = rememberLayerBackdrop()

    GlasmioBackdropHost(
        modifier = Modifier.fillMaxSize(),
        sampledContent = { LibraryWallpaper(isDark, backgroundImage) },
        overlayContent = { hostBackdrop ->
            Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(contentBackdrop)
                    .trackBackdropPosition(contentBackdrop)
                    .pointerInput(Unit) {
                        // 子控件自己处理点击；只有空白处冒泡到这里，用于收键盘/清焦点。
                        detectTapGestures { focusManager.clearFocus() }
                    },
            ) {
                // 壁纸必须画进同一层：没有内容的区域是透明的，玻璃采样到「空」会被判成暗环境，
                // 底框就整块发灰。垫上壁纸后底框颜色恒定，列表卡片仍然会被透镜弯折。
                LibraryWallpaper(isDark, backgroundImage)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars),
                ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 14.dp, top = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (tab == TAB_RESOURCE) "资源搜索" else "视频库",
                            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp),
                            fontWeight = FontWeight.Bold,
                            color = GlasmioTheme.colors.content,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = when {
                                tab == TAB_CLOUD -> "夸克网盘 · 仅显示视频"
                                tab == TAB_RESOURCE -> "全网公开分享 · 默认只看夸克"
                                else ->
                                "${videos.size} 个视频 · ${folders.size} 个文件夹"
                            },
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                            color = GlasmioTheme.colors.content.copy(alpha = 0.5f),
                        )
                    }
                    if (tab != TAB_CLOUD && tab != TAB_RESOURCE) {
                        GlasmioIconButton(onClick = { showSortSheet = true }, size = 38.dp) {
                            SfIcon("arrow.up.arrow.down", "排序方式", size = 17.dp)
                        }
                        Spacer(Modifier.width(2.dp))
                        GlasmioIconButton(
                            onClick = {
                                searchActive = !searchActive
                                if (!searchActive) query = ""
                            },
                            size = 38.dp,
                        ) {
                            SfIcon("magnifyingglass", "搜索", size = 17.dp)
                        }
                        Spacer(Modifier.width(2.dp))
                    }
                    GlasmioIconButton(onClick = {
                        focusManager.clearFocus()
                        showSettings = true
                    }, size = 38.dp) {
                        SfIcon("gear", "设置", size = 17.dp)
                    }
                    if (settings.fpsMeter) {
                        Spacer(Modifier.width(6.dp))
                        FpsBadge()
                    }
                }


                if (searchActive) {
                    GlassSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp)
                            .height(42.dp),
                        shape = RoundedCornerShape(50),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SfIcon("magnifyingglass", null, size = 16.dp)
                            Spacer(Modifier.width(8.dp))
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = GlasmioTheme.colors.content,
                                    fontSize = 15.sp,
                                ),
                                cursorBrush = SolidColor(GlasmioTheme.colors.accent),
                                modifier = Modifier.weight(1f),
                                decorationBox = { inner ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (query.isEmpty()) {
                                            Text(
                                                text = "搜索视频或文件夹",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = GlasmioTheme.colors.content.copy(alpha = 0.45f),
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                            if (query.isNotEmpty()) {
                                GlasmioIconButton(onClick = { query = "" }, size = 30.dp) {
                                    SfIcon("xmark.circle.fill", "清除搜索", size = 15.dp)
                                }
                            }
                        }
                    }
                }

                if (selectionMode) {
                    val folderSelectionList = selectedFolder?.let { folder ->
                        videos.filter { it.bucketId == folder.bucketId }
                    } ?: emptyList()
                    val allVisible = when {
                        selectedFolder != null -> folderSelectionList
                        tab == TAB_FAVORITES -> favoriteList
                        tab == TAB_HISTORY -> historyList
                        else -> allFiltered
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "已选 ${selectedUris.size} 项",
                            style = MaterialTheme.typography.titleSmall,
                            color = GlasmioTheme.colors.content,
                            modifier = Modifier.weight(1f),
                        )
                        GlassPill(
                            text = "全选",
                            onClick = {
                                selectedUris = if (selectedUris.size >= allVisible.size) {
                                    emptySet()
                                } else {
                                    allVisible.map { it.uri }.toSet()
                                }
                                selectionMode = selectedUris.isNotEmpty()
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        GlassPill(
                            text = "取消",
                            onClick = {
                                selectionMode = false
                                selectedUris = emptySet()
                            },
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val selectedItems = allVisible.filter { it.uri in selectedUris }
                        GlassPill(
                            text = "收藏",
                            icon = "heart",
                            onClick = {
                                selectedItems.forEach { settings.toggleFavorite(it.uri) }
                                favorites = settings.favorites()
                            },
                        )
                        GlassPill(
                            text = "移出记录",
                            icon = "arrow.clockwise",
                            onClick = {
                                selectedItems.forEach { settings.clearPosition(it.uri) }
                                positions = positions - selectedUris
                                lastPlayed = lastPlayed - selectedUris
                                localRefresh++
                            },
                        )
                        if (selectedItems.size == 1) {
                            GlassPill(
                                text = "详情",
                                icon = "info.circle",
                                onClick = {
                                    detailVideo = selectedItems.first()
                                    detailList = allVisible
                                },
                            )
                        }
                    }
                }

                // 切换 tab / 进入文件夹时的过渡：整块内容轻量滑入 + 淡入。
                // 进度只在 graphicsLayer 里读，动画过程不重组；36px 的位移足够表达方向感，
                // 又不会让上千条列表做长距离平移。
                val contentProgress = remember { Animatable(1f) }
                var contentDir by remember { mutableIntStateOf(1) }
                var lastTab by remember { mutableIntStateOf(tab) }
                var suppressEnter by remember { mutableStateOf(false) }
                LaunchedEffect(tab, selectedFolder?.bucketId) {
                    if (suppressEnter) {
                        // 这次切换是左右滑动触发的，内容位移由下面 settle 动画负责，淡入淡出跳过
                        suppressEnter = false
                        contentProgress.snapTo(1f)
                        return@LaunchedEffect
                    }
                    if (tab != lastTab) {
                        contentDir = if (tab > lastTab) 1 else -1
                        lastTab = tab
                    } else {
                        contentDir = -1
                    }
                    contentProgress.snapTo(0f)
                    contentProgress.animateTo(1f, tween(190, easing = FastOutSlowInEasing))
                }

                // 左右滑动手势的跟手位移：拖动时内容跟着手指走，松手后按速度/距离决定翻页还是回弹。
                // 整个流程只改 graphicsLayer.translationX，列表本身不重组、不重新布局。
                val swipeScope = rememberCoroutineScope()
                val swipeWidthPx = with(LocalDensity.current) {
                    LocalConfiguration.current.screenWidthDp.dp.toPx()
                }
                val settle = remember { Animatable(0f) }
                // 换页瞬间的透明度过渡：内容替换的那一帧轻微压暗再快速恢复，
                // 让「旧页消失 / 新页出现」的几何跳变被一层柔和的明暗变化盖住。
                val swapFade = remember { Animatable(1f) }
                var dragOffset by remember { mutableFloatStateOf(0f) }
                var dragging by remember { mutableStateOf(false) }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .graphicsLayer {
                            val v = contentProgress.value
                            val dx = if (dragging) dragOffset else settle.value
                            // 拖动到两端时轻微压暗，强化「页面被推走」的感觉
                            val dragDim = 1f - (kotlin.math.abs(dx) / swipeWidthPx) * 0.1f
                            alpha = v * dragDim.coerceIn(0.9f, 1f) * swapFade.value
                            translationX = (1f - v) * 36f * contentDir + dx
                        }
                        // 左右滑动切换「全部 / 文件夹 / 收藏 / 记录」；纵向滚动不受影响
                        .pointerInput(tab, selectionMode, swipeWidthPx) {
                            val limit = swipeWidthPx * 0.42f
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    dragging = true
                                    dragOffset = settle.value
                                },
                                onHorizontalDrag = { _, dragAmount ->
                                    dragOffset = (dragOffset + dragAmount).coerceIn(-limit, limit)
                                },
                                onDragCancel = {
                                    dragging = false
                                    swipeScope.launch {
                                        settle.snapTo(dragOffset)
                                        settle.animateTo(0f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
                                    }
                                },
                                onDragEnd = {
                                    dragging = false
                                    val threshold = 64.dp.toPx()
                                    val dist = dragOffset
                                    if (!selectionMode && abs(dist) > threshold) {
                                        // 手指往右 → 上一个 tab；往左 → 下一个 tab。
                                        // 松手立刻换内容，新页面从反侧一小段位移滑入：只有一段动画，
                                        // 中间不再有「滑出→停顿→滑入」的空档。
                                        val dir = if (dist > 0) -1 else 1
                                        suppressEnter = true
                                        shiftTab(dir)
                                        val entry = -dist * 0.7f
                                        swipeScope.launch {
                                            swapFade.snapTo(0.7f)
                                            swapFade.animateTo(1f, tween(140))
                                        }
                                        swipeScope.launch {
                                            settle.snapTo(entry)
                                            settle.animateTo(
                                                0f,
                                                spring(
                                                    dampingRatio = 0.9f,
                                                    stiffness = Spring.StiffnessMedium,
                                                ),
                                            )
                                        }
                                    } else {
                                        swipeScope.launch {
                                            settle.snapTo(dist)
                                            settle.animateTo(
                                            0f,
                                            spring(
                                                dampingRatio = 0.88f,
                                                stiffness = Spring.StiffnessMedium,
                                            ),
                                        )
                                        }
                                    }
                                    dragOffset = 0f
                                },
                            )
                        },
                ) {
                    when {
                        tab == TAB_RESOURCE -> {
                            ResourcePane(
                                settings = settings,
                                modifier = Modifier.fillMaxSize(),
                                onSaved = {
                                    cloudState.crumbs = listOf("0" to "我的网盘")
                                    tab = TAB_CLOUD
                                },
                                onSheetVisibleChange = { resourceSheetOpen = it },
                            )
                        }
                        tab == TAB_CLOUD -> {
                            CloudPane(
                                state = cloudState,
                                settings = settings,
                                isDark = isDark,
                                modifier = Modifier.fillMaxSize(),
                                onSheetVisibleChange = { cloudSheetOpen = it },
                            )
                        }
                        !hasPermission -> {
                            PermissionCard(
                                onOpenSettings = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.parse("package:${context.packageName}"),
                                            ),
                                        )
                                    }
                                },
                                onRequest = {
                                    permissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.READ_MEDIA_VIDEO,
                                            Manifest.permission.POST_NOTIFICATIONS,
                                        ),
                                    )
                                },
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }

                        loading -> {
                            Text(
                                text = "正在扫描本地视频…",
                                modifier = Modifier.align(Alignment.Center),
                                style = MaterialTheme.typography.bodyMedium,
                                color = GlasmioTheme.colors.content.copy(alpha = 0.7f),
                            )
                        }

                        videos.isEmpty() -> {
                            EmptyState(
                                title = "还没有找到视频",
                                subtitle = "把视频放进手机相册、下载目录或 Movies 文件夹，再刷新一次。",
                                action = "重新扫描",
                                onAction = { localRefresh++ },
                            )
                        }

                        tab == TAB_FOLDERS && selectedFolder != null -> {
                            val folder = selectedFolder!!
                            val folderVideos = remember(videos, folder.bucketId, query, sortMode, sortAscending) {
                                filterAndSort(videos.filter { it.bucketId == folder.bucketId })
                            }
                            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    GlassPill("返回文件夹", icon = "chevron.left", onClick = { selectedFolder = null })
                                    Text(
                                        text = folder.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = GlasmioTheme.colors.content,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (gridLayout) {
                                    LazyVerticalGrid(
                                        columns = GridCells.Adaptive(minSize = 170.dp),
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 100.dp),
                                    ) {
                                        gridItemsIndexed(folderVideos, key = { _, item -> item.uri }) { index, item ->
                                            VideoTile(
                                                item = item,
                                                progressMs = positions[item.uri] ?: 0L,
                                                selected = item.uri in selectedUris,
                                                onClick = { handleItemClick(folderVideos, index, item.uri) },
                                                onLongClick = { handleItemLong(item) },
                                                modifier = Modifier.animateItem(),
                                            )
                                        }
                                    }
                                } else {
                                    LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 100.dp),
                                    ) {
                                        itemsIndexed(folderVideos, key = { _, item -> item.uri }) { index, item ->
                                            VideoRow(
                                                item = item,
                                                progressMs = positions[item.uri] ?: 0L,
                                                favorite = favorites.contains(item.uri),
                                                selected = item.uri in selectedUris,
                                                onClick = { handleItemClick(folderVideos, index, item.uri) },
                                                onLongClick = { handleItemLong(item) },
                                                modifier = Modifier.animateItem(),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        tab == TAB_FOLDERS -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 100.dp),
                            ) {
                                itemsIndexed(folders, key = { _, folder -> folder.bucketId }) { _, folder ->
                                    FolderRow(
                                        folder = folder,
                                        onClick = { selectedFolder = folder },
                                        modifier = Modifier.animateItem(),
                                    )
                                }
                            }
                        }

                        else -> {
                            val list = when (tab) {
                                TAB_FAVORITES -> favoriteList
                                TAB_HISTORY -> historyList
                                else -> allFiltered
                            }
                            if (list.isEmpty()) {
                                val emptyTitle = when (tab) {
                                    TAB_FAVORITES -> "还没有收藏"
                                    TAB_HISTORY -> "还没有播放记录"
                                    else -> "没有匹配的视频"
                                }
                                val emptySubtitle = when (tab) {
                                    TAB_FAVORITES -> "长按任意视频，在详情里点“收藏”，它就会出现在这里。"
                                    TAB_HISTORY -> "播放过的视频会按时间倒序出现在这里，并保留上次看到的位置。"
                                    else -> "换个关键词，或切换排序方式试试。"
                                }
                                EmptyState(
                                    title = emptyTitle,
                                    subtitle = emptySubtitle,
                                )
                            } else {
                                if (gridLayout) {
                                    LazyVerticalGrid(
                                        columns = GridCells.Adaptive(minSize = 170.dp),
                                        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 100.dp),
                                    ) {
                                        if (tab == TAB_HISTORY && list.isNotEmpty()) {
                                            item(key = "history-actions", span = { GridItemSpan(maxLineSpan) }) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(
                                                        text = "共 ${list.size} 条播放记录",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                    GlassPill("清空记录", onClick = {
                                                        settings.resetPlaybackMemory()
                                                        positions = emptyMap()
                                                        lastPlayed = emptyMap()
                                                        localRefresh++
                                                    })
                                                }
                                            }
                                        }
                                        if (tab == TAB_ALL && continueWatching.isNotEmpty()) {
                                            item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
                                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    SectionTitle("继续观看")
                                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                        items(continueWatching, key = { it.uri }) { item ->
                                                            ResumeCard(
                                                                item = item,
                                                                progressMs = positions[item.uri] ?: 0L,
                                                                onClick = {
                                                                    // 搜索把这条过滤掉时 allFiltered 里找不到它，
                                                                    // 退化成单条播放列表，不能让「继续观看」点了没反应
                                                                    val index = allFiltered.indexOfFirst { it.uri == item.uri }
                                                                    if (index >= 0) {
                                                                        onOpenVideo(allFiltered, index)
                                                                    } else {
                                                                        onOpenVideo(listOf(item), 0)
                                                                    }
                                                                },
                                                                modifier = Modifier.animateItem(),
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        gridItemsIndexed(list, key = { _, item -> item.uri }) { index, item ->
                                            VideoTile(
                                                item = item,
                                                progressMs = positions[item.uri] ?: 0L,
                                                selected = item.uri in selectedUris,
                                                onClick = { handleItemClick(list, index, item.uri) },
                                                onLongClick = { handleItemLong(item) },
                                                modifier = Modifier.animateItem(),
                                            )
                                        }
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 100.dp),
                                    ) {
                                        if (tab == TAB_HISTORY && list.isNotEmpty()) {
                                            item(key = "history-actions") {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(
                                                        text = "共 ${list.size} 条播放记录",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                    GlassPill("清空记录", onClick = {
                                                        settings.resetPlaybackMemory()
                                                        positions = emptyMap()
                                                        lastPlayed = emptyMap()
                                                        localRefresh++
                                                    })
                                                }
                                            }
                                        }
                                        if (tab == TAB_ALL && continueWatching.isNotEmpty()) {
                                            item(key = "continue") {
                                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    SectionTitle("继续观看")
                                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                        items(continueWatching, key = { it.uri }) { item ->
                                                            ResumeCard(
                                                                item = item,
                                                                progressMs = positions[item.uri] ?: 0L,
                                                                onClick = {
                                                                    // 搜索把这条过滤掉时 allFiltered 里找不到它，
                                                                    // 退化成单条播放列表，不能让「继续观看」点了没反应
                                                                    val index = allFiltered.indexOfFirst { it.uri == item.uri }
                                                                    if (index >= 0) {
                                                                        onOpenVideo(allFiltered, index)
                                                                    } else {
                                                                        onOpenVideo(listOf(item), 0)
                                                                    }
                                                                },
                                                                modifier = Modifier.animateItem(),
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        itemsIndexed(list, key = { _, item -> item.uri }) { index, item ->
                                            VideoRow(
                                                item = item,
                                                progressMs = positions[item.uri] ?: 0L,
                                                favorite = favorites.contains(item.uri),
                                                selected = item.uri in selectedUris,
                                                onClick = { handleItemClick(list, index, item.uri) },
                                                onLongClick = { handleItemLong(item) },
                                                metaExtra = if (tab == TAB_HISTORY) {
                                                    formatAgo(lastPlayed[item.uri] ?: 0L)
                                                } else {
                                                    null
                                                },
                                                modifier = Modifier.animateItem(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }

            val navGlassShape = RoundedCornerShape(28.dp)
            // 弹层打开时导航让位：走弹簧位移做回弹，但样式全程只用一个导航实现，
            // 不再像旧版那样在动画中途切到 StableBottomBar —— 那才是「跳变」的来源。
            // 弹簧欠阻尼（0.58）在回到原位时会轻微冲过头再落定，这就是要的回弹手感。
            val navLift by animateDpAsState(
                targetValue = if (anySheetOpen) 140.dp else 0.dp,
                animationSpec = spring(
                    dampingRatio = 0.58f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "navBounce",
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset { IntOffset(0, navLift.roundToPx()) }
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (settings.liquidNavGlass) {
                    GlasmioNavigationBar(
                        items = listOf(
                            GlasmioTabItem(TAB_ALL, "全部", SfSymbol.Layers, "全部"),
                            GlasmioTabItem(TAB_FOLDERS, "文件", SfSymbol.Download, "文件"),
                            GlasmioTabItem(TAB_CLOUD, "网盘", SfSymbol.Cloud, "网盘"),
                            GlasmioTabItem(TAB_RESOURCE, "资源", SfSymbol.Search, "资源"),
                            GlasmioTabItem(TAB_FAVORITES, "收藏", SfSymbol.Heart, "收藏"),
                            GlasmioTabItem(TAB_HISTORY, "记录", SfSymbol.Clock, "记录"),
                        ),
                        selectedKey = tab,
                        onSelected = { target ->
                            if (target != TAB_FOLDERS) selectedFolder = null
                            tab = target
                        },
                        onExpand = {},
                        compactProgress = remember { mutableStateOf(0f) },
                        compactSize = 56.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                clip = true
                                shape = navGlassShape
                            },
                        backdrop = contentBackdrop,
                    )
                } else StableBottomBar(
                    items = listOf(
                        BottomBarItem(TAB_ALL, "全部", SfSymbol.Layers),
                        BottomBarItem(TAB_FOLDERS, "文件", SfSymbol.Download),
                        BottomBarItem(TAB_CLOUD, "网盘", SfSymbol.Cloud),
                        BottomBarItem(TAB_RESOURCE, "资源", SfSymbol.Search),
                        BottomBarItem(TAB_FAVORITES, "收藏", SfSymbol.Heart),
                        BottomBarItem(TAB_HISTORY, "记录", SfSymbol.Clock),
                    ),
                    selectedKey = tab,
                    onSelected = { target ->
                        if (target != TAB_FOLDERS) selectedFolder = null
                        tab = target
                    },
                    isDark = isDark,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            GlassSheet(
                visible = showSortSheet,
                onDismissRequest = { showSortSheet = false },
                title = "视图与排序",
                maxContentHeight = 560,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "布局",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPill(
                            text = "列表",
                            icon = "list.bullet",
                            selected = !gridLayout,
                            onClick = {
                                gridLayout = false
                                settings.gridLayout = false
                            },
                        )
                        GlassPill(
                            text = "网格",
                            icon = "square.grid.2x2",
                            selected = gridLayout,
                            onClick = {
                                gridLayout = true
                                settings.gridLayout = true
                            },
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "排序",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                    )
                    sortOptions.chunked(3).forEach { rowOptions ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowOptions.forEach { (mode, label) ->
                                GlassPill(
                                    text = label,
                                    selected = sortMode == mode,
                                    onClick = {
                                        sortMode = mode
                                        settings.sortMode = mode
                                    },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "方向",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                    )
                    // 名称类排序用正序/倒序，数值类才有"短长"的概念
                    val nameMode = sortMode == 1 || sortMode == 5 || sortMode == 6 || sortMode == 7
                    val ascLabel = if (nameMode) "正序 A→Z" else "由小到大"
                    val descLabel = if (nameMode) "倒序 Z→A" else "由大到小"
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPill(
                            text = ascLabel,
                            selected = sortAscending,
                            onClick = {
                                sortAscending = true
                                settings.sortAscending = true
                            },
                        )
                        GlassPill(
                            text = descLabel,
                            selected = !sortAscending,
                            onClick = {
                                sortAscending = false
                                settings.sortAscending = false
                            },
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "过滤",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "低于 X 秒的视频不显示",
                            style = MaterialTheme.typography.bodyMedium,
                            color = GlasmioTheme.colors.content,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (minDurationPreview <= 0f) "不过滤" else "${minDurationPreview.toInt()} 秒",
                            style = MaterialTheme.typography.labelMedium,
                            color = GlasmioTheme.colors.accent,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "0",
                            style = MaterialTheme.typography.labelSmall,
                            color = GlasmioTheme.colors.content.copy(alpha = 0.45f),
                        )
                        SlimSlider(
                            value = (minDurationPreview / 300f).coerceIn(0f, 1f),
                            onValueChange = { minDurationPreview = (it * 300f).coerceIn(0f, 300f) },
                            onValueChangeFinished = {
                                settings.minDurationSec = minDurationPreview.toInt()
                                settingsVersion++
                                localRefresh++
                            },
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        )
                        Text(
                            text = "300 秒",
                            style = MaterialTheme.typography.labelSmall,
                            color = GlasmioTheme.colors.content.copy(alpha = 0.45f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0, 10, 30, 60, 120).forEach { preset ->
                            GlassPill(
                                text = if (preset == 0) "不限" else "${preset}s",
                                selected = settings.minDurationSec == preset,
                                onClick = {
                                    minDurationPreview = preset.toFloat()
                                    settings.minDurationSec = preset
                                    settingsVersion++
                                    localRefresh++
                                },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (settings.hiddenFolders.isEmpty()) {
                                "扫描目录 · 全部 ${allFolders.size} 个"
                            } else {
                                "扫描目录 · 已隐藏 ${settings.hiddenFolders.size} 个"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = GlasmioTheme.colors.content,
                            modifier = Modifier.weight(1f),
                        )
                        GlassPill("选择", onClick = { showFolderSheet = true })
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        GlassPill(
                            text = "重置",
                            onClick = {
                                sortMode = 0
                                settings.sortMode = 0
                                sortAscending = false
                                settings.sortAscending = false
                                gridLayout = false
                                settings.gridLayout = false
                                minDurationPreview = 0f
                                settings.minDurationSec = 0
                                settingsVersion++
                                localRefresh++
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        GlassPill(
                            text = "完成",
                            selected = true,
                            onClick = { showSortSheet = false },
                        )
                    }
                }
            }

            GlassSheet(
                visible = showSettings,
                onDismissRequest = { showSettings = false },
                title = "设置",
            ) {
                // 读一下版本号：设置项不是 Compose 状态，靠它触发重绘
                @Suppress("UNUSED_EXPRESSION")
                settingsVersion
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // 读一下版本号：设置项不是 Compose 状态，靠它触发重绘
                    @Suppress("UNUSED_EXPRESSION")
                    settingsVersion

                    Text(
                        "外观",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPill("跟随系统", selected = themeMode == 0, onClick = { onThemeModeChange(0) })
                        GlassPill("深色", selected = themeMode == 1, onClick = { onThemeModeChange(1) })
                        GlassPill("浅色", selected = themeMode == 2, onClick = { onThemeModeChange(2) })
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        "背景图",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    Text(
                        if (backgroundUri == null) "当前使用内置渐变，选一张照片当壁纸，液态玻璃会折射它。"
                        else "已使用自定义背景图，液态玻璃折射的就是这张图。",
                        style = MaterialTheme.typography.labelSmall,
                        color = GlasmioTheme.colors.content.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPill(
                            text = "选择图片",
                            icon = "photo",
                            onClick = { backgroundPicker.launch(arrayOf("image/*")) },
                        )
                        GlassPill(
                            text = "恢复默认",
                            icon = "arrow.clockwise",
                            selected = backgroundUri == null,
                            onClick = {
                                settings.backgroundUri = null
                                backgroundUri = null
                                runCatching {
                                    File(context.filesDir, "custom_background.img").delete()
                                }
                                settingsVersion++
                            },
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "液态动效",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                    ToggleRow(
                        title = "底部导航液态玻璃",
                        description = if (settings.liquidNavGlass) {
                            "已开启：导航固定使用真实玻璃折射；若机型出现采样色块可关闭。"
                        } else {
                            "已关闭：导航为本地绘制的胶囊，任何机型都不会出现色块。"
                        },
                        checked = settings.liquidNavGlass,
                        onToggle = {
                            settings.liquidNavGlass = !settings.liquidNavGlass
                            settingsVersion++
                        },
                    )

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "网盘",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Text(
                        if (settings.quarkCookie.isNullOrBlank()) {
                            "未登录。登录后可直接播放夸克网盘里的视频，并自动匹配弹幕。"
                        } else {
                            "已登录" + (settings.quarkUserName?.let { "：$it" } ?: "") +
                                "，点击进入网盘目录。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = GlasmioTheme.colors.content.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPill(
                            text = if (settings.quarkCookie.isNullOrBlank()) "登录夸克网盘" else "打开夸克网盘",
                            icon = "icloud",
                            onClick = {
                                showSettings = false
                                tab = TAB_CLOUD
                            },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPill(
                            text = "直接播放",
                            selected = !settings.quarkCachePlayback,
                            onClick = {
                                settings.quarkCachePlayback = false
                                settingsVersion++
                            },
                        )
                        GlassPill(
                            text = "边下边播",
                            selected = settings.quarkCachePlayback,
                            onClick = {
                                settings.quarkCachePlayback = true
                                settingsVersion++
                            },
                        )
                    }
                    Text(
                        if (settings.quarkCachePlayback) {
                            "边下边播：边看边存，拖动回看不用重新下载，本地最多占 2GB。"
                        } else {
                            "直接播放：走网盘转码流（m3u8），不占本地空间、速度更稳，画质是转码后的版本。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = GlasmioTheme.colors.content.copy(alpha = 0.45f),
                        modifier = Modifier.padding(top = 6.dp),
                    )

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "播放",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                    ToggleRow(
                        title = "断点续播",
                        description = "再次打开视频时从上次的位置继续",
                        checked = settings.resumeEnabled,
                        onToggle = { settings.resumeEnabled = !settings.resumeEnabled; settingsVersion++ },
                    )
                    ToggleRow(
                        title = "后台播放",
                        description = "默认关闭：离开应用就暂停；开启后切到后台继续播放",
                        checked = settings.backgroundPlay,
                        onToggle = { settings.backgroundPlay = !settings.backgroundPlay; settingsVersion++ },
                    )
                    ToggleRow(
                        title = "跟随视频自动横竖屏",
                        description = "横屏视频自动横过来，竖屏视频保持竖屏",
                        checked = settings.autoOrientation,
                        onToggle = { settings.autoOrientation = !settings.autoOrientation; settingsVersion++ },
                    )
                    ToggleRow(
                        title = "播放时保持常亮",
                        description = "播放过程中不自动熄屏",
                        checked = settings.keepScreenOn,
                        onToggle = { settings.keepScreenOn = !settings.keepScreenOn; settingsVersion++ },
                    )

                    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)) {
                        Text("默认播放倍速", style = MaterialTheme.typography.bodyLarge, color = GlasmioTheme.colors.content)
                        Text(
                            "进入播放页时的初始速度",
                            style = MaterialTheme.typography.labelSmall,
                            color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0.5f, 1f, 1.5f, 2f).forEach { value ->
                                GlassPill(
                                    text = if (value == 1f) "1×" else "${value}×",
                                    selected = kotlin.math.abs(settings.defaultSpeed - value) < 0.01f,
                                    onClick = { settings.defaultSpeed = value; settingsVersion++ },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "手势与显示",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                    ToggleRow(
                        title = "手势控制",
                        description = "左右滑动快进，左右半屏调节亮度与音量",
                        checked = settings.gesturesEnabled,
                        onToggle = { settings.gesturesEnabled = !settings.gesturesEnabled; settingsVersion++ },
                    )
                    ToggleRow(
                        title = "离开自动画中画",
                        description = "切到其它应用时自动进入小窗",
                        checked = settings.autoPip,
                        onToggle = { settings.autoPip = !settings.autoPip; settingsVersion++ },
                    )
                    ToggleRow(
                        title = "显示帧率",
                        description = "右上角显示实时帧率，用来确认是否跑满屏幕刷新率",
                        checked = settings.fpsMeter,
                        onToggle = { settings.fpsMeter = !settings.fpsMeter; settingsVersion++ },
                    )

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "媒体库",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("重新扫描", style = MaterialTheme.typography.bodyLarge, color = GlasmioTheme.colors.content)
                            Text(
                                "新增或删除文件后，点一下重新读取媒体库",
                                style = MaterialTheme.typography.labelSmall,
                                color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                            )
                        }
                        GlassPill("刷新", onClick = { localRefresh++ })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("播放记录", style = MaterialTheme.typography.bodyLarge, color = GlasmioTheme.colors.content)
                            Text(
                                "共 ${positions.size} 条播放记录与进度",
                                style = MaterialTheme.typography.labelSmall,
                                color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                            )
                        }
                        GlassPill("清除", onClick = {
                            settings.resetPlaybackMemory()
                            positions = emptyMap()
                            lastPlayed = emptyMap()
                            localRefresh++
                        })
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "隐私",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                    ToggleRow(
                        title = "禁止截屏与录屏",
                        description = "播放时阻止系统截图和录屏（部分机型仍有系统级录屏）",
                        checked = settings.secureScreen,
                        onToggle = { settings.secureScreen = !settings.secureScreen; settingsVersion++ },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("隐藏目录", style = MaterialTheme.typography.bodyLarge, color = GlasmioTheme.colors.content)
                            Text(
                                if (settings.hiddenFolders.isEmpty()) "当前没有隐藏任何目录"
                                else "已隐藏 ${settings.hiddenFolders.size} 个目录",
                                style = MaterialTheme.typography.labelSmall,
                                color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                            )
                        }
                        GlassPill("管理", onClick = {
                            showSettings = false
                            showFolderSheet = true
                        })
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "诊断与关于",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                    ToggleRow(
                        title = "解码回退",
                        description = "主解码器不支持时自动尝试其它解码器（重启后生效）",
                        checked = settings.decoderFallback,
                        onToggle = { settings.decoderFallback = !settings.decoderFallback; settingsVersion++ },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("运行自检", style = MaterialTheme.typography.bodyLarge, color = GlasmioTheme.colors.content)
                            Text(
                                "检查 Camera 目录访问、解码器与崩溃记录",
                                style = MaterialTheme.typography.labelSmall,
                                color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                            )
                        }
                        GlassPill("开始", onClick = {
                            showSettings = false
                            showDiagnostics = true
                        })
                    }
                    Text(
                        "GlassPlayer 3.0 · 播放内核 Media3 1.11.1 · 界面 Glasmio 0.1.2",
                        style = MaterialTheme.typography.labelSmall,
                        color = GlasmioTheme.colors.content.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                    Text(
                        text = "崩溃日志：Android/data/com.glassplayer/files/logs/last_crash.txt",
                        style = MaterialTheme.typography.labelSmall,
                        color = GlasmioTheme.colors.content.copy(alpha = 0.5f),
                    )
                }
            }

            GlassSheet(
                visible = showFolderSheet,
                onDismissRequest = { showFolderSheet = false },
                title = "扫描目录",
                maxContentHeight = 460,
            ) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        "取消勾选的目录不会出现在媒体库里，随时可以恢复。",
                        style = MaterialTheme.typography.labelSmall,
                        color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    allFolders.forEach { folder ->
                        val hiddenNow = folder.name in settings.hiddenFolders
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    folder.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                                    color = GlasmioTheme.colors.content,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${folder.count} 个视频",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                                )
                            }
                            GlassPill(
                                text = if (hiddenNow) "已隐藏" else "已启用",
                                selected = !hiddenNow,
                                onClick = {
                                    val next = settings.hiddenFolders.toMutableSet()
                                    if (hiddenNow) next.remove(folder.name) else next.add(folder.name)
                                    settings.hiddenFolders = next
                                    settingsVersion++
                                    localRefresh++
                                },
                            )
                        }
                    }
                    if (allFolders.isEmpty()) {
                        Text(
                            "还没有扫描到目录",
                            style = MaterialTheme.typography.labelMedium,
                            color = GlasmioTheme.colors.content.copy(alpha = 0.5f),
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }

            GlassSheet(
                visible = showDiagnostics,
                onDismissRequest = { showDiagnostics = false },
                title = "自检结果",
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        text = diagnosticsText,
                        style = MaterialTheme.typography.bodySmall,
                        color = GlasmioTheme.colors.content,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPill("复制全部", onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(ClipData.newPlainText("GlassPlayer 自检", diagnosticsText))
                        })
                        GlassPill("重新检测", onClick = {
                            diagnosticsScope.launch {
                                diagnosticsText = "正在检测…"
                                diagnosticsText = Diagnostics.run(context)
                            }
                        })
                    }
                }
            }

            val detail = detailVideo
            GlassSheet(
                visible = detail != null,
                onDismissRequest = { detailVideo = null },
                title = detail?.title ?: "",
            ) {
                if (detail != null) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("文件夹", color = GlasmioTheme.colors.content.copy(alpha = 0.55f))
                            Text(detail.folderName, color = GlasmioTheme.colors.content)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("格式", color = GlasmioTheme.colors.content.copy(alpha = 0.55f))
                            Text(detail.mimeType, color = GlasmioTheme.colors.content)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("分辨率", color = GlasmioTheme.colors.content.copy(alpha = 0.55f))
                            Text(detail.resolutionText, color = GlasmioTheme.colors.content)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("时长 / 大小", color = GlasmioTheme.colors.content.copy(alpha = 0.55f))
                            Text(
                                "${formatDuration(detail.durationMs)} / ${formatSize(detail.sizeBytes)}",
                                color = GlasmioTheme.colors.content,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("路径", color = GlasmioTheme.colors.content.copy(alpha = 0.55f))
                            Text(
                                detail.relativePath.ifBlank { "媒体库" },
                                color = GlasmioTheme.colors.content,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (detail.cloudDir) {
                                GlassPill(
                                    text = "打开文件夹",
                                    icon = "folder",
                                    selected = true,
                                    onClick = {
                                        cloudState.crumbs = listOf(
                                            "0" to "我的网盘",
                                            detail.uri.removePrefix("quark://") to detail.title,
                                        )
                                        tab = TAB_CLOUD
                                        detailVideo = null
                                    },
                                )
                            } else GlassPill(
                                text = "播放",
                                icon = "play.fill",
                                selected = true,
                                onClick = {
                                    val list = detailList.ifEmpty {
                                        if (tab == TAB_FAVORITES) favoriteList else allFiltered
                                    }
                                    val index = list.indexOfFirst { it.uri == detail.uri }
                                    detailVideo = null
                                    if (index >= 0) onOpenVideo(list, index)
                                },
                            )
                            GlassPill(
                                text = if (favorites.contains(detail.uri)) "取消收藏" else "收藏",
                                icon = "heart",
                                selected = favorites.contains(detail.uri),
                                onClick = {
                                    settings.toggleFavorite(detail.uri)
                                    favorites = settings.favorites()
                                },
                            )
                            if (!detail.isCloud) GlassPill(
                                text = "分享",
                                icon = "square.and.arrow.up",
                                onClick = {
                                    val share = Intent(Intent.ACTION_SEND).apply {
                                        type = "video/*"
                                        putExtra(Intent.EXTRA_STREAM, Uri.parse(detail.uri))
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    runCatching {
                                        context.startActivity(Intent.createChooser(share, "分享视频"))
                                    }
                                },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!detail.cloudDir) GlassPill(
                                text = if (tab == TAB_HISTORY) "移除记录" else "清除进度",
                                icon = "arrow.clockwise",
                                onClick = {
                                    settings.clearPosition(detail.uri)
                                    positions = positions - detail.uri
                                    lastPlayed = lastPlayed - detail.uri
                                    localRefresh++
                                },
                            )
                        }
                    }
                }
            }
            }
        },
    )

}

@Composable
private fun PermissionCard(
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        modifier = modifier.padding(28.dp),
        shape = GlasmioTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SfIcon("folder", null, size = 30.dp)
            Text(
                text = "需要读取本地视频",
                style = MaterialTheme.typography.titleSmall,
                color = GlasmioTheme.colors.content,
            )
            Text(
                text = "只在本机读取视频与字幕文件，不会上传任何内容。",
                style = MaterialTheme.typography.bodySmall,
                color = GlasmioTheme.colors.content.copy(alpha = 0.65f),
            )
            Text(
                text = "如果已经拒绝过授权，系统不会再弹窗，需要到设置里手动开启「照片和视频」权限。",
                style = MaterialTheme.typography.labelSmall,
                color = GlasmioTheme.colors.content.copy(alpha = 0.45f),
            )
            GlasmioButton(
                onClick = onRequest,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                SfIcon("play.fill", null, size = 15.dp)
                Spacer(Modifier.width(6.dp))
                Text("允许访问视频")
            }
            GlassPill(text = "去系统设置开启", onClick = onOpenSettings)
        }
    }
}

private suspend fun saveBackgroundImage(context: Context, uri: Uri): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            while (bounds.outWidth / sample > 1440 || bounds.outHeight / sample > 1440) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return@runCatching false
            File(context.filesDir, "custom_background.img").outputStream().use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            bmp.recycle()
            true
        }.getOrDefault(false)
    }

/**
 * 本地库排序。
 *
 * 名称类排序（标题 / 路径 / 数字优先 / 首字母）先把每条记录的 CollationKey 算一次，
 * 比较阶段只比二进制；原来是在比较器里对每个元素反复调用 SortKeys.natural()，
 * 首字母模式还会每次比较都做一次 Han-Latin 转写 + NFD，8000 条视频时主线程直接卡住。
 */
private fun sortVideos(
    source: List<VideoItem>,
    mode: Int,
    ascending: Boolean,
): List<VideoItem> {
    fun keyed(transform: (VideoItem) -> String): List<VideoItem> {
        // 排序键 → 原始记录，避免把 CollationKey 和 VideoItem 拆成两个数组还要来回索引
        val keyed = source.map { it to SortKeys.collationKey(transform(it)) }
        val ordered = keyed.sortedWith(
            Comparator { a, b ->
                val c = a.second.compareTo(b.second)
                // 完全同名时用标题收尾，保证结果稳定（等价于原来 SortKeys.compare 的兜底）
                if (c != 0) c else a.first.title.compareTo(b.first.title)
            },
        )
        return if (ascending) ordered.map { it.first } else ordered.asReversed().map { it.first }
    }
    val numericComparator: Comparator<VideoItem> = when (mode) {
        2 -> compareBy { it.durationMs }
        3 -> compareBy { it.sizeBytes }
        4 -> compareBy { it.width.toLong() * it.height.toLong() }
        else -> compareBy { it.dateAddedSec }
    }
    return when (mode) {
        1 -> keyed { SortKeys.natural(it.title) }
        5 -> keyed { SortKeys.path(it.folderName, it.title) }
        6 -> keyed { SortKeys.numeric(it.title) }
        7 -> keyed { SortKeys.initial(it.title) }
        // 降序用 comparator.reversed() 而不是 list.reversed()：后者会把相等元素的顺序也倒过来
        else -> source.sortedWith(
            if (ascending) numericComparator else numericComparator.reversed(),
        )
    }
}

@Composable
private fun rememberBackgroundImage(uriString: String?): ImageBitmap? {
    val context = LocalContext.current
    var bitmap by remember(uriString) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uriString) {
        bitmap = null
        if (uriString == null) return@LaunchedEffect
        // decodeFile 在 2K 壁纸上会占几十毫秒，放主线程会在进入主页时掉帧
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val saved = File(context.filesDir, "custom_background.img")
                val opts = BitmapFactory.Options()
                val decoded = if (saved.exists()) {
                    BitmapFactory.decodeFile(saved.absolutePath, opts)
                } else {
                    val uri = Uri.parse(uriString)
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    }
                }
                decoded?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

@Composable
private fun LibraryWallpaper(isDark: Boolean, image: ImageBitmap? = null) {
    Canvas(Modifier.fillMaxSize()) {
        val colors = if (isDark) {
            listOf(Color(0xFF070A12), Color(0xFF101828))
        } else {
            listOf(Color(0xFFF2F5FB), Color(0xFFDCE6F7))
        }
        drawRect(Brush.verticalGradient(colors))

        if (image != null) {
            // centerCrop 铺满整屏，再压一层遮罩保证文字可读、玻璃亮度稳定
            val scale = maxOf(size.width / image.width, size.height / image.height)
            val w = image.width * scale
            val h = image.height * scale
            drawImage(
                image = image,
                dstOffset = IntOffset(
                    ((size.width - w) / 2f).roundToInt(),
                    ((size.height - h) / 2f).roundToInt(),
                ),
                dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            )
            drawRect(if (isDark) Color(0xB3151820) else Color(0x8CF5F8FC))
            return@Canvas
        }

        val accent = if (isDark) Color(0xFF2F5CFF) else Color(0xFF9BBCFF)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.55f), Color.Transparent),
                center = Offset(size.width * 0.88f, size.height * 0.12f),
                radius = size.width * 0.9f,
            ),
            radius = size.width * 0.9f,
            center = Offset(size.width * 0.88f, size.height * 0.12f),
        )

        val warm = if (isDark) Color(0xFFFF6B6B) else Color(0xFFFFB4A8)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(warm.copy(alpha = 0.4f), Color.Transparent),
                center = Offset(size.width * 0.05f, size.height * 0.92f),
                radius = size.width * 0.8f,
            ),
            radius = size.width * 0.8f,
            center = Offset(size.width * 0.05f, size.height * 0.92f),
        )

        val teal = if (isDark) Color(0xFF16C8B0) else Color(0xFF8BE3D6)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(teal.copy(alpha = 0.35f), Color.Transparent),
                center = Offset(size.width * 0.18f, size.height * 0.35f),
                radius = size.width * 0.55f,
            ),
            radius = size.width * 0.55f,
            center = Offset(size.width * 0.18f, size.height * 0.35f),
        )
    }
}

private fun hasVideoPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) ==
        PackageManager.PERMISSION_GRANTED

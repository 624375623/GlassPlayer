package com.glassplayer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassplayer.data.DebugLog
import com.glassplayer.data.QuarkApi
import com.glassplayer.data.SettingsStore
import com.glassplayer.player.PlayerActivity
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/** 内嵌在主页的网盘状态：切 tab 时保留登录态、目录和列表，不用每次重拉。 */
class CloudState(val api: QuarkApi, private val settings: SettingsStore) {
    var loggedIn by mutableStateOf(api.isLoggedIn)
    var loading by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var files by mutableStateOf<List<QuarkApi.QuarkFile>>(emptyList())
    var hasMore by mutableStateOf(false)
    var page by mutableIntStateOf(1)
    var crumbs by mutableStateOf(listOf("0" to "我的网盘"))

    val currentFolder: Pair<String, String> get() = crumbs.last()

    // fid -> 该目录（含任意深层子目录）里是否存在视频；持久化，重启后不用重新扫描
    private val folderHasVideo = mutableMapOf<String, Boolean>().apply {
        putAll(settings.cloudScanCache())
    }

    fun refresh() {
        folderHasVideo.clear()
        settings.clearCloudScanCache()
    }

    suspend fun load(folderId: String, pageNo: Int, append: Boolean) {
        loading = true
        message = null
        if (!append) files = emptyList()
        val result = runCatching { withContext(Dispatchers.IO) { api.list(folderId, pageNo) } }
        if (result.isFailure) {
            val error = result.exceptionOrNull()
            message = "读取目录失败：" + (error?.message ?: "未知错误")
            if (!api.isLoggedIn) loggedIn = false
            loading = false
            return
        }
        val (list, more) = result.getOrThrow()
        val hiddenFolderIds = settings.hiddenCloudFolders()
        val visible = list.filter { item ->
            if (item.isDir) item.fid !in hiddenFolderIds else isVideoName(item.name)
        }
        val dirs = visible.filter { it.isDir }
        if (!append && dirs.isNotEmpty()) message = "正在筛选含视频的文件夹…"

        // 进哪个目录就探哪一层：文件夹里（含深层子目录）没视频就不展示
        val keep = if (append) {
            dirs.associate { it.fid to (folderHasVideo[it.fid] ?: true) }
        } else {
            val budget = AtomicInteger(120)
            val semaphore = Semaphore(6)
            val done = AtomicInteger(0)
            val total = dirs.size
            coroutineScope {
                dirs.map { dir ->
                    async {
                        val known = folderHasVideo[dir.fid]
                        val has = known ?: probeFolder(dir.fid, 0, budget, semaphore)
                            .also { folderHasVideo[dir.fid] = it }
                        val n = done.incrementAndGet()
                        message = "正在筛选含视频的文件夹（$n/$total）…"
                        dir.fid to has
                    }
                }.awaitAll().toMap()
            }
        }
        val shown = visible.filter { !it.isDir || keep[it.fid] != false }
        files = if (append) files + shown else shown
        settings.saveCloudScanCache(folderHasVideo)
        hasMore = more
        page = pageNo
        message = null
        loading = false
    }

    private suspend fun probeFolder(
        fid: String,
        depth: Int,
        budget: AtomicInteger,
        semaphore: Semaphore,
    ): Boolean {
        if (budget.get() <= 0 || depth > 10) return true
        budget.decrementAndGet()
        val listing = runCatching {
            semaphore.withPermit {
                withContext(Dispatchers.IO) { api.list(fid, 1, 100).first }
            }
        }.getOrElse { return true } // 探测失败时宁可保留，不误删目录
        val hasDirectVideo = listing.any { !it.isDir && isVideoName(it.name) }
        if (hasDirectVideo) {
            folderHasVideo[fid] = true
            return true
        }
        val subs = listing.filter { it.isDir }
        if (subs.isEmpty()) {
            folderHasVideo[fid] = false
            return false
        }
        // 视频可能在更深一层：递归往下找，直到没有子目录或预算用完
        for (sub in subs) {
            val cached = folderHasVideo[sub.fid]
            if (cached == true) return true
            if (cached == false) continue
            if (probeFolder(sub.fid, depth + 1, budget, semaphore)) return true
        }
        folderHasVideo[fid] = false
        return false
    }
}

@Composable
fun rememberCloudState(settings: SettingsStore): CloudState {
    val context = LocalContext.current
    val api = remember { QuarkApi(context, settings) }
    return remember(api) { CloudState(api, settings) }
}

@Composable
fun CloudPane(
    state: CloudState,
    settings: SettingsStore,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    onSheetVisibleChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = state.api

    var showLogin by remember { mutableStateOf(false) }
    var favVersion by remember { mutableIntStateOf(0) }
    var cloudGrid by remember { mutableStateOf(settings.cloudGridLayout) }
    var cloudSort by remember { mutableIntStateOf(settings.cloudSortMode) }
    var cloudAsc by remember { mutableStateOf(settings.cloudSortAscending) }
    var showSortSheet by remember { mutableStateOf(false) }
    var actionFile by remember { mutableStateOf<QuarkApi.QuarkFile?>(null) }
    var showHiddenSheet by remember { mutableStateOf(false) }
    val favorites = remember(favVersion) { settings.favorites() }
    var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var qrUrl by remember { mutableStateOf<String?>(null) }
    var loginStatus by remember { mutableStateOf("正在生成二维码…") }
    var loginJob by remember { mutableStateOf<Job?>(null) }

    val cloudSheetOpen = showLogin || showSortSheet || actionFile != null || showHiddenSheet
    LaunchedEffect(cloudSheetOpen) { onSheetVisibleChange(cloudSheetOpen) }

    LaunchedEffect(state.currentFolder.first, state.loggedIn) {
        if (state.loggedIn) state.load(state.currentFolder.first, 1, append = false)
    }

    BackHandler(enabled = state.crumbs.size > 1) {
        state.crumbs = state.crumbs.dropLast(1)
    }

    fun startQrLogin() {
        loginJob?.cancel()
        showLogin = true
        qrBitmap = null
        qrUrl = null
        loginStatus = "正在生成二维码…"
        loginJob = scope.launch {
            val session = runCatching { withContext(Dispatchers.IO) { api.requestQrSession() } }
                .getOrElse {
                    loginStatus = it.message ?: "二维码获取失败"
                    return@launch
                }
            qrUrl = session.url
            loginStatus = "用夸克 App 扫一扫，扫完自动登录"
            val bitmap = runCatching { withContext(Dispatchers.IO) { loadQrBitmap(session.url) } }.getOrNull()
            qrBitmap = bitmap
            if (bitmap == null) loginStatus = "二维码图片加载失败，可用下面的按钮在浏览器打开"

            var waited = 0
            while (waited < 150) {
                delay(2_000)
                waited += 2
                val result = runCatching { withContext(Dispatchers.IO) { api.pollQrTicket(session.token) } }
                if (result.isFailure) {
                    loginStatus = result.exceptionOrNull()?.message ?: "二维码已失效，请重新获取"
                    return@launch
                }
                val ticket = result.getOrNull() ?: continue
                loginStatus = "已扫码，正在换取登录凭证…"
                val cookie = runCatching { withContext(Dispatchers.IO) { api.exchangeCookie(ticket) } }
                    .getOrElse {
                        loginStatus = it.message ?: "登录失败，请重试"
                        return@launch
                    }
                withContext(Dispatchers.IO) { api.fetchUserName() }
                state.loggedIn = true
                showLogin = false
                loginStatus = "登录成功"
                state.message = "登录成功，正在读取网盘…"
                return@launch
            }
            loginStatus = "二维码已过期，点「重新获取」再试"
        }
    }

    // 未登录时自动拉起二维码登录；但要区分「第一次进来还没登录」和「用户刚点了退出登录」。
    // 之前无条件 if (!loggedIn) startQrLogin()，点退出后立刻又弹扫码框，用户根本退不出去。
    var autoLoginDone by remember { mutableStateOf(false) }
    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn) {
            // 登录过（含退出前的状态）就不再自动拉起
            autoLoginDone = true
        } else if (!autoLoginDone) {
            autoLoginDone = true
            startQrLogin()
        }
    }

    fun openFile(file: QuarkApi.QuarkFile) {
        settings.saveCloudVideo(
            com.glassplayer.data.CloudVideo(
                fid = file.fid,
                name = file.name,
                size = file.size,
                updatedAt = file.updatedAt,
                parentFid = state.currentFolder.first,
                parentName = state.currentFolder.second,
            ),
        )
        scope.launch {
            val cacheMode = settings.quarkCachePlayback
            state.message = "正在准备上下集…"

            // 先把分页补齐：目录里后面的页没加载完，整季列表就会缺集
            var allFiles = state.files
            var pageNo = state.page
            var guard = 0
            while (state.hasMore && guard < 8) {
                val more = runCatching {
                    withContext(Dispatchers.IO) {
                        api.list(state.currentFolder.first, pageNo + 1)
                    }
                }.getOrNull() ?: break
                val hiddenIds = settings.hiddenCloudFolders()
                val visible = more.first.filter { item ->
                    if (item.isDir) item.fid !in hiddenIds else isVideoName(item.name)
                }
                allFiles = allFiles + visible
                state.hasMore = more.second
                pageNo += 1
                guard += 1
            }
            state.page = pageNo
            state.files = allFiles

            // 整个目录的视频全部收录，再按集号排序，不按剧名筛选
            val pool = allFiles.filter { !it.isDir }.distinctBy { it.fid }
            val sortedPool = pool.sortedWith(
                compareBy(
                    { episodeOnlyNumber(it.name) ?: Int.MAX_VALUE },
                    { cloudSortKey(it.name) },
                ),
            )
            val ordered = if (sortedPool.size <= 80) {
                sortedPool
            } else {
                val cursor = sortedPool.indexOfFirst { it.fid == file.fid }.coerceAtLeast(0)
                val from = (cursor - 40)
                    .coerceIn(0, (sortedPool.size - 80).coerceAtLeast(0))
                sortedPool.subList(from, (from + 80).coerceAtMost(sortedPool.size)).toList()
            }.let { list ->
                if (list.any { it.fid == file.fid }) list else list + file
            }
            val total = ordered.size
            val index = ordered.indexOfFirst { it.fid == file.fid }.coerceAtLeast(0)
            DebugLog.record(
                "cloud",
                "files=${allFiles.size} 目录视频=${pool.size} 收录=$total 起始=$index " +
                    "首集=${ordered.firstOrNull()?.name}",
            )

            // 只解析当前这一集，其余集带 fid 进播放页，切到哪一集再解析哪一集
            state.message = "正在解析播放地址…"
            var playUrl = if (cacheMode) {
                null
            } else {
                runCatching {
                    withContext(Dispatchers.IO) { api.streamUrl(file.fid) }
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }
            if (playUrl == null) {
                delay(300)
                playUrl = runCatching {
                    withContext(Dispatchers.IO) { api.downloadUrl(file.fid) }
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }
            if (playUrl == null) {
                delay(700)
                playUrl = runCatching {
                    withContext(Dispatchers.IO) { api.downloadUrl(file.fid) }
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }
            if (playUrl == null) {
                state.message = "解析播放地址失败：这一集可能被限制或需要会员"
                if (!api.isLoggedIn) state.loggedIn = false
                return@launch
            }
            state.message = if (!cacheMode && !playUrl.contains(".m3u8")) {
                "转码不可用，已改用下载直链（可能较慢），其余集会在播放时自动补齐"
            } else {
                null
            }

            val uris = ArrayList<String>(total)
            repeat(total) { uris.add("") }
            uris[index] = playUrl

            val intent = Intent(context, PlayerActivity::class.java).apply {
                putStringArrayListExtra(PlayerActivity.EXTRA_URIS, uris)
                putStringArrayListExtra(
                    PlayerActivity.EXTRA_TITLES,
                    ArrayList(ordered.map { displayTitle(it.name) }),
                )
                putStringArrayListExtra(
                    PlayerActivity.EXTRA_KEYS,
                    // 记录/进度用 quark://fid 作稳定 key；纯 fid 会和列表里的 uri 对不上，
                    // 导致网盘视频播完不进「记录」，时长也回写不到元信息
                    ArrayList(ordered.map { "quark://${it.fid}" }),
                )
                putStringArrayListExtra(
                    PlayerActivity.EXTRA_FIDS,
                    ArrayList(ordered.map { it.fid }),
                )
                putExtra(PlayerActivity.EXTRA_INDEX, index)
                putExtra(
                    PlayerActivity.EXTRA_SERIES,
                    cloudSeriesTitle(file.name, state.currentFolder.second),
                )
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            runCatching { context.startActivity(intent) }.onFailure {
                DebugLog.record("player", "启动播放页失败：${it.message}")
                state.message = "启动播放页失败：" + (it.message ?: "未知错误")
            }
        }
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (state.loggedIn) state.crumbs.joinToString(" / ") { it.second } else "夸克网盘 · 未登录",
                    style = MaterialTheme.typography.titleSmall,
                    color = GlasmioTheme.colors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (state.loggedIn) "仅显示视频文件" else "扫码登录后可直接播放网盘里的视频",
                    style = MaterialTheme.typography.labelSmall,
                    color = GlasmioTheme.colors.content.copy(alpha = 0.5f),
                )
            }
            if (state.crumbs.size > 1) {
                GlasmioIconButton(onClick = { state.crumbs = state.crumbs.dropLast(1) }, size = 34.dp) {
                    SfIcon("chevron.left", "返回上级", size = 15.dp)
                }
            }
            GlasmioIconButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("GlassPlayer 调试日志", DebugLog.snapshot()))
                state.message = "调试日志已复制到剪贴板"
            }, size = 34.dp) {
                SfIcon("doc.on.doc", "复制调试日志", size = 15.dp)
            }
            if (state.loggedIn) {
                // 视图和排序合并成一个入口，面板里同时调布局、排序字段和方向。
                GlasmioIconButton(onClick = { showSortSheet = true }, size = 34.dp) {
                    SfIcon("arrow.up.arrow.down", "视图与排序", size = 15.dp)
                }
            }
            GlasmioIconButton(onClick = {
                if (state.loggedIn) {
                    state.refresh()
                    scope.launch { state.load(state.currentFolder.first, 1, append = false) }
                } else {
                    startQrLogin()
                }
            }, size = 34.dp) {
                SfIcon("arrow.clockwise", "刷新（重新扫描）", size = 15.dp)
            }
        }

        state.message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = GlasmioTheme.colors.content.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            )
        }

        val sorted = remember(state.files, cloudSort, cloudAsc) {
            sortCloudFiles(state.files, cloudSort, cloudAsc)
        }

        LazyVerticalGrid(
                columns = GridCells.Fixed(if (cloudGrid) 2 else 1),
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 104.dp),
            ) {
                val hiddenCount = settings.hiddenCloudFolders().size
                if (hiddenCount > 0) {
                    item(key = "hidden", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.CenterStart) {
                            GlassPill(
                                text = "已屏蔽 $hiddenCount 个文件夹（点此管理）",
                                icon = "eye.slash",
                                onClick = { showHiddenSheet = true },
                            )
                        }
                    }
                }
                gridItems(sorted, key = { it.fid }, contentType = { "file" }) { file ->
                    CloudCard(
                        file = file,
                        grid = cloudGrid,
                        isDark = isDark,
                        onClick = {
                            if (file.isDir) {
                                state.crumbs = state.crumbs + (file.fid to file.name)
                            } else {
                                openFile(file)
                            }
                        },
                        onLongClick = { actionFile = file },
                    )
                }

                if (state.loading && state.files.isEmpty()) {
                    item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "正在读取目录…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = GlasmioTheme.colors.content.copy(alpha = 0.5f),
                            )
                        }
                    }
                }

                if (state.hasMore) {
                    item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                            GlassPill(
                                text = if (state.loading) "加载中…" else "加载更多",
                                onClick = {
                                    if (!state.loading) {
                                        scope.launch { state.load(state.currentFolder.first, state.page + 1, append = true) }
                                    }
                                },
                            )
                        }
                    }
                }

                if (state.files.isEmpty() && !state.loading) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (state.loggedIn) "这个文件夹里没有视频" else "扫码登录后查看网盘视频",
                                style = MaterialTheme.typography.bodyMedium,
                                color = GlasmioTheme.colors.content.copy(alpha = 0.5f),
                            )
                        }
                    }
                }

                if (state.loggedIn) {
                    item(key = "logout", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                            GlassPill(
                                text = "退出登录",
                                danger = true,
                                onClick = {
                                    api.logout()
                                    state.loggedIn = false
                                    state.files = emptyList()
                                    state.crumbs = listOf("0" to "我的网盘")
                                },
                            )
                        }
                    }
                } else {
                    item(key = "login", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                            GlassPill(
                                text = "扫码登录",
                                selected = true,
                                onClick = { startQrLogin() },
                            )
                        }
                    }
                }
            }
        }

    if (showLogin) {
        GlassSheet(
            visible = true,
            onDismissRequest = { showLogin = false },
            title = "扫码登录夸克网盘",
        ) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                qrBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "登录二维码",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(220.dp),
                    )
                } ?: Box(
                    Modifier.size(220.dp).background(Color(0x22FFFFFF), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        loginStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = GlasmioTheme.colors.content.copy(alpha = 0.6f),
                    )
                }
                Text(
                    loginStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = GlasmioTheme.colors.content.copy(alpha = 0.65f),
                )
                if (qrUrl != null) {
                    GlassPill(
                        text = "在浏览器中打开扫码页",
                        icon = "arrow.up.right.square",
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(qrUrl)))
                            }
                        },
                    )
                }
                GlassPill("重新获取二维码", icon = "arrow.clockwise", onClick = { startQrLogin() })
            }
        }
    }

    if (showHiddenSheet) {
        val hiddenIds = settings.hiddenCloudFolders()
        val videos = settings.cloudVideos().associateBy { it.fid }
        GlassSheet(
            visible = true,
            onDismissRequest = { showHiddenSheet = false },
            title = "已屏蔽的网盘文件夹",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hiddenIds.isEmpty()) {
                    Text(
                        "没有屏蔽任何文件夹",
                        style = MaterialTheme.typography.bodySmall,
                        color = GlasmioTheme.colors.content.copy(alpha = 0.6f),
                    )
                }
                hiddenIds.forEach { fid ->
                    val name = videos[fid]?.name ?: fid
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = GlasmioTheme.colors.content,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        GlassPill(
                            text = "恢复",
                            onClick = {
                                settings.toggleHiddenCloudFolder(fid)
                                state.message = "已恢复：$name"
                                showHiddenSheet = false
                                scope.launch { state.load(state.currentFolder.first, 1, append = false) }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showSortSheet) {
        GlassSheet(
            visible = true,
            onDismissRequest = { showSortSheet = false },
            title = "视图与排序",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "布局",
                    style = MaterialTheme.typography.labelMedium,
                    color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill(
                        text = "列表",
                        selected = !cloudGrid,
                        onClick = {
                            cloudGrid = false
                            settings.cloudGridLayout = false
                        },
                    )
                    GlassPill(
                        text = "网格",
                        selected = cloudGrid,
                        onClick = {
                            cloudGrid = true
                            settings.cloudGridLayout = true
                        },
                    )
                }
                Text(
                    "排序",
                    style = MaterialTheme.typography.labelMedium,
                    color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("最近更新" to 0, "名称" to 1, "大小" to 2).forEach { (label, mode) ->
                        GlassPill(
                            text = label,
                            selected = cloudSort == mode,
                            onClick = {
                                cloudSort = mode
                                settings.cloudSortMode = mode
                            },
                        )
                    }
                }
                Text(
                    "方向",
                    style = MaterialTheme.typography.labelMedium,
                    color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill(
                        text = "升序",
                        selected = cloudAsc,
                        onClick = {
                            cloudAsc = true
                            settings.cloudSortAscending = true
                        },
                    )
                    GlassPill(
                        text = "降序",
                        selected = !cloudAsc,
                        onClick = {
                            cloudAsc = false
                            settings.cloudSortAscending = false
                        },
                    )
                }
                GlassPill("完成", selected = true, onClick = { showSortSheet = false })
            }
        }
    }

    actionFile?.let { file ->
        val favKey = "quark://${file.fid}"
        val favorite = favorites.contains(favKey)
        val hidden = file.isDir && settings.hiddenCloudFolders().contains(file.fid)
        GlassSheet(
            visible = true,
            onDismissRequest = { actionFile = null },
            title = file.name,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (file.isDir) "文件夹 · 长按操作" else formatSize(file.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                )
                GlassPill(
                    text = if (favorite) "取消收藏" else "收藏",
                    icon = "heart",
                    selected = favorite,
                    onClick = {
                        settings.saveCloudVideo(
                            com.glassplayer.data.CloudVideo(
                                fid = file.fid,
                                name = file.name,
                                size = if (file.isDir) 0 else file.size,
                                updatedAt = file.updatedAt,
                                parentFid = state.currentFolder.first,
                                parentName = state.currentFolder.second,
                                isDir = file.isDir,
                            ),
                        )
                        val added = settings.toggleFavorite(favKey)
                        favVersion++
                        state.message = if (added) "已收藏：${file.name}" else "已取消收藏"
                        actionFile = null
                    },
                )
                if (file.isDir) {
                    GlassPill(
                        text = if (hidden) "取消屏蔽（重新显示）" else "屏蔽此文件夹",
                        icon = if (hidden) "eye" else "eye.slash",
                        danger = !hidden,
                        onClick = {
                            settings.saveCloudVideo(
                                com.glassplayer.data.CloudVideo(
                                    fid = file.fid,
                                    name = file.name,
                                    size = 0,
                                    updatedAt = file.updatedAt,
                                    parentFid = state.currentFolder.first,
                                    parentName = state.currentFolder.second,
                                    isDir = true,
                                ),
                            )
                            settings.toggleHiddenCloudFolder(file.fid)
                            state.message = if (hidden) "已取消屏蔽：${file.name}" else "已屏蔽：${file.name}"
                            actionFile = null
                            scope.launch {
                                state.load(state.currentFolder.first, 1, append = false)
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun sortCloudFiles(
    files: List<QuarkApi.QuarkFile>,
    mode: Int,
    ascending: Boolean,
): List<QuarkApi.QuarkFile> {
    // 名称排序和本地库保持一致：数字补零 + 中文拼音，否则「第10集」会排在「第2集」前面
    val comparator: Comparator<QuarkApi.QuarkFile> = when (mode) {
        1 -> Comparator { a, b -> SortKeys.compare(SortKeys.natural(a.name), SortKeys.natural(b.name)) }
        2 -> compareBy { it.size }
        else -> compareBy { it.updatedAt }
    }
    // 降序用 comparator.reversed() 而不是 list.reversed()，避免相等元素顺序被打乱
    val ordered = if (ascending) comparator else comparator.reversed()
    // 目录永远排在文件前面
    return files.filter { it.isDir }.sortedWith(ordered) +
        files.filterNot { it.isDir }.sortedWith(ordered)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CloudCard(
    file: QuarkApi.QuarkFile,
    grid: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // 故意不用 GlassSurface：几百行玻璃做实时采样会掉帧，这里用纯色卡片就够了
    val background = if (isDark) Color.White.copy(alpha = 0.09f) else Color.White.copy(alpha = 0.62f)
    val subtitle = if (file.isDir) "文件夹" else formatSize(file.size)
    if (grid) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(background)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            SfIcon(
                systemName = if (file.isDir) "folder" else "play.rectangle",
                contentDescription = null,
                size = 18.dp,
            )
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = GlasmioTheme.colors.content,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = GlasmioTheme.colors.content.copy(alpha = 0.45f),
            )
        }
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(background)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SfIcon(
                systemName = if (file.isDir) "folder" else "play.rectangle",
                contentDescription = null,
                size = 16.dp,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = GlasmioTheme.colors.content,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = GlasmioTheme.colors.content.copy(alpha = 0.45f),
                )
            }
            SfIcon(
                systemName = if (file.isDir) "chevron.right" else "play.fill",
                contentDescription = null,
                size = 14.dp,
            )
        }
    }
}

/** 网盘里只认视频文件，其余文档/图片/压缩包一律不展示（文件夹照常可进）。 */
private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "mov", "flv", "wmv", "m4v", "ts", "m2ts", "mts",
    "rmvb", "rm", "webm", "3gp", "mpg", "mpeg", "ogv", "asf", "vob",
)

internal fun isVideoName(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in VIDEO_EXTENSIONS
}

private fun loadQrBitmap(url: String): android.graphics.Bitmap? {
    val qr = "https://api.qrserver.com/v1/create-qr-code/?size=480x480&margin=10&data=" +
        URLEncoder.encode(url, "UTF-8")
    val conn = (URL(qr).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
    }
    return try {
        conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
    } finally {
        // 只关流不 disconnect 会把连接留在池里，反复「重新获取二维码」会越堆越多
        runCatching { conn.disconnect() }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "大小未知"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    val text = if (value >= 100) value.roundToInt().toString() else String.format("%.1f", value)
    return "$text ${units[index]}"
}

private val QUALITY_TOKEN = Regex(
    "(?i)(2160p|1080p|720p|480p|4k|8k|hdr10|hdr|web[-._ ]?dl|blu[-._ ]?ray|remux|hevc|h265|x265|av1|aac|dts|10bit)",
)

private fun cleanTitle(name: String): String {
    val base = name.substringBeforeLast('.').ifBlank { name }
    val cut = QUALITY_TOKEN.find(base)?.range?.first ?: return base.trim()
    return base.substring(0, cut).trimEnd('.', ' ', '_', '-').ifBlank { base.trim() }
}

// ---- 网盘整季列表用的集号 / 标题辅助（与 CloudActivity 里的逻辑保持一致）----
internal fun episodeOnlyNumber(name: String): Int? {
    val stem = QUALITY_TOKEN.replace(name.substringBeforeLast("."), "").trim()
    Regex("(?i)s\\d{1,2}\\s*e(\\d{1,3})").find(stem)?.let {
        return it.groupValues[1].toIntOrNull()
    }
    // 「第2季」「第3部」不是集号，只有集/话/話或者纯数字才算
    Regex("第\\s*(\\d{1,4})\\s*(?!季|部|期)[集话話]?").find(stem)?.let {
        return it.groupValues[1].toIntOrNull()
    }
    // 以前是 (?i)ep?\\s*(\\d{1,4})，没有左边界：Movie2024 会被认成第 2024 集，
    // 整季列表按集号排序、标题也会显示成「第2024集」
    Regex("(?i)(?:^|[^A-Za-z])ep?[._\\s-]*(\\d{1,4})").find(stem)?.let {
        return it.groupValues[1].toIntOrNull()
    }
    if (stem.all { it.isDigit() }) return stem.toIntOrNull()
    return null
}

internal fun displayTitle(name: String): String =
    episodeOnlyNumber(name)?.let { "第${it}集" } ?: cleanTitle(name)

internal fun cloudSortKey(name: String): String =
    episodeOnlyNumber(name)
        ?.let { "ep" + it.toString().padStart(6, '0') }
        ?: name.lowercase().replace(Regex("\\d+")) { it.value.padStart(6, '0') }

internal fun cloudSeriesTitle(name: String, folder: String): String {
    val stem = QUALITY_TOKEN.replace(name.substringBeforeLast('.'), "")
    val cleaned = Regex(
        "(?i)[\\s._\\-\\[\\]()]*(?:第\\s*\\d{1,4}\\s*[集话話]|s\\d{1,2}\\s*e\\d{1,3}|ep?\\s*\\d{1,4}|\\d{1,4})" +
            "[\\s._\\-\\[\\]()]*$",
    ).replace(stem, "").replace(Regex("[._\\-]+"), " ").trim()
    return cleaned.ifBlank { folder }.take(24)
}

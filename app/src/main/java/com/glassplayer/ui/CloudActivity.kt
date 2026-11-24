package com.glassplayer.ui

import android.content.Intent
import android.content.res.Configuration
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.glassplayer.data.DebugLog
import com.glassplayer.data.QuarkApi
import com.glassplayer.data.SettingsStore
import com.glassplayer.player.PlayerActivity
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.glasmioColorScheme
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.glass.GlasmioBackdropHost
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/** 夸克网盘浏览：目录树 + 登录 + 直接播放。 */
class CloudActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val settings = SettingsStore(this)
        val dark = when (settings.themeMode) {
            1 -> true
            2 -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        setContent {
            GlasmioTheme(
                isDark = dark,
                colorScheme = glasmioColorScheme(dark),
            ) {
                // 玻璃控件必须有宿主，否则 LocalGlassBackdrop 会直接抛异常（点云图标闪退的原因）
                GlasmioBackdropHost(
                    sampledContent = { CloudWallpaper(dark) },
                    overlayContent = {
                        CloudScreen(
                            settings = settings,
                            isDark = dark,
                            onClose = { finish() },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun CloudScreen(
    settings: SettingsStore,
    isDark: Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { QuarkApi(context, settings) }
    // 界面上直接带版本号，方便确认装的是哪一版
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    var loggedIn by remember { mutableStateOf(api.isLoggedIn) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<QuarkApi.QuarkFile>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(1) }
    val crumbs = remember { mutableStateListOf("0" to "我的网盘") }
    val currentFolder = crumbs.last()

    var showLogin by remember { mutableStateOf(false) }
    var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var qrUrl by remember { mutableStateOf<String?>(null) }
    var loginStatus by remember { mutableStateOf("正在生成二维码…") }
    var loginJob by remember { mutableStateOf<Job?>(null) }

    fun load(folderId: String, pageNo: Int, append: Boolean) {
        scope.launch {
            loading = true
            message = null
            runCatching { withContext(Dispatchers.IO) { api.list(folderId, pageNo) } }
                .onSuccess { (list, more) ->
                    files = if (append) files + list else list
                    hasMore = more
                    page = pageNo
                }
                .onFailure {
                    message = "读取目录失败：" + (it.message ?: "未知错误")
                    if (!api.isLoggedIn) loggedIn = false
                }
            loading = false
        }
    }

    LaunchedEffect(currentFolder.first, loggedIn) {
        if (loggedIn) {
            files = emptyList()
            load(currentFolder.first, 1, append = false)
        }
    }

    // 点一个视频：把同目录里的同一部剧集串成播放列表，进入播放页就能切上下集 / 自动连播
    fun playVideo(file: QuarkApi.QuarkFile, initialFiles: List<QuarkApi.QuarkFile>) {
        scope.launch {
            val cacheMode = settings.quarkCachePlayback
            message = if (cacheMode) "正在准备上下集（边下边播）…" else "正在准备上下集…"
            // 目录是分页加载的，先把后面的页补齐，否则第 2 集以后认不出来
            var allFiles = initialFiles
            var pageNo = page
            var guard = 0
            while (hasMore && guard < 8) {
                val more = runCatching {
                    withContext(Dispatchers.IO) { api.list(currentFolder.first, pageNo + 1) }
                }.getOrNull() ?: break
                allFiles = allFiles + more.first
                hasMore = more.second
                pageNo += 1
                guard++
            }
            page = pageNo
            files = allFiles

            // 直接收整个目录的视频，不再按剧名筛选（纯数字命名的剧集没有剧名可比对）
            val pool = allFiles.filter { !it.isDir && isVideoFile(it.name) }.distinctBy { it.fid }
            DebugLog.record(
                "cloud",
                "files=${allFiles.size}：" + allFiles.joinToString(" | ") {
                    "${it.name}/dir=${it.isDir}/vid=${isVideoFile(it.name)}"
                },
            )
            val sortedPool = pool.sortedWith(
                compareBy({ episodeOnlyNumber(it.name) ?: Int.MAX_VALUE }, { sortKey(it.name) }),
            )
            // 目录特别大时只取当前集前后各 40 集，避免一次解析上百个直链
            val ordered = if (sortedPool.size <= 80) {
                sortedPool
            } else {
                val cursor = sortedPool.indexOfFirst { it.fid == file.fid }.coerceAtLeast(0)
                val from = (cursor - 40).coerceIn(0, (sortedPool.size - 80).coerceAtLeast(0))
                sortedPool.subList(from, (from + 80).coerceAtMost(sortedPool.size)).toList()
            }.let { list ->
                if (list.any { it.fid == file.fid }) list else list + file
            }

            val total = ordered.size
            val index = ordered.indexOfFirst { it.fid == file.fid }.coerceAtLeast(0)
            DebugLog.record(
                "cloud",
                "上下集：目录视频=${pool.size} 收录=$total 起始=$index 首集=${ordered.firstOrNull()?.name}",
            )

            // 只解析当前这一集：整季直链改成进播放页后按需解析，避免批量请求被网盘限流
            message = "正在解析播放地址…"
            var playUrl = if (cacheMode) {
                null
            } else {
                runCatching { withContext(Dispatchers.IO) { api.streamUrl(file.fid) } }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
            }
            if (playUrl == null) {
                delay(300)
                playUrl = runCatching { withContext(Dispatchers.IO) { api.downloadUrl(file.fid) } }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
            }
            if (playUrl == null) {
                delay(700)
                playUrl = runCatching { withContext(Dispatchers.IO) { api.downloadUrl(file.fid) } }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
            }
            if (playUrl == null) {
                message = "解析播放地址失败：这一集可能被限制或需要会员"
                if (!api.isLoggedIn) loggedIn = false
                return@launch
            }
            message = if (!cacheMode && !playUrl.contains(".m3u8")) {
                "转码不可用，已改用下载直链（可能较慢），其余集会在播放时自动补齐"
            } else {
                null
            }
            val uris = ArrayList<String>(total)
            repeat(total) { uris.add("") }
            uris[index] = playUrl

            val seriesLabel = seriesTitle(file.name, currentFolder.second)
            context.startActivity(
                Intent(context, PlayerActivity::class.java).apply {
                    putStringArrayListExtra(PlayerActivity.EXTRA_URIS, uris)
                    putStringArrayListExtra(
                        PlayerActivity.EXTRA_TITLES,
                        ArrayList(ordered.map { displayTitle(it.name) }),
                    )
                    putStringArrayListExtra(
                        PlayerActivity.EXTRA_KEYS,
                        // 记录/进度要用 quark://fid，和列表条目的 uri 保持一致
                        ArrayList(ordered.map { "quark://${it.fid}" }),
                    )
                    putStringArrayListExtra(
                        PlayerActivity.EXTRA_FIDS,
                        ArrayList(ordered.map { it.fid }),
                    )
                    putExtra(PlayerActivity.EXTRA_INDEX, index)
                    putExtra(PlayerActivity.EXTRA_SERIES, seriesLabel)
                },
            )
        }
    }

    // ---- 扫码登录 ----
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
                loggedIn = true
                showLogin = false
                loginStatus = "登录成功"
                message = "登录成功，正在读取网盘…"
                return@launch
            }
            loginStatus = "二维码已过期，点「重新获取」再试"
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(WindowInsets.systemBars.asPaddingValues()),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlasmioIconButton(onClick = {
                    if (crumbs.size > 1) crumbs.removeAt(crumbs.lastIndex) else onClose()
                }, size = 38.dp) {
                    SfIcon("chevron.left", "返回", size = 17.dp)
                }
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (loggedIn) "夸克网盘 · v$versionName" else "夸克网盘 · 未登录",
                        style = MaterialTheme.typography.titleMedium,
                        color = GlasmioTheme.colors.content,
                    )
                    Text(
                        text = if (loggedIn) currentFolder.second else "扫码登录后可直接播放网盘里的视频",
                        style = MaterialTheme.typography.labelSmall,
                        color = GlasmioTheme.colors.content.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                GlasmioIconButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText("GlassPlayer 调试日志", DebugLog.snapshot()),
                    )
                    message = "调试日志已复制到剪贴板"
                }, size = 38.dp) {
                    SfIcon("doc.on.doc", "复制调试日志", size = 16.dp)
                }
                Spacer(Modifier.width(2.dp))
                GlasmioIconButton(onClick = {
                    if (loggedIn) load(currentFolder.first, 1, append = false) else startQrLogin()
                }, size = 38.dp) {
                    SfIcon("arrow.clockwise", "刷新", size = 16.dp)
                }
            }

            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = GlasmioTheme.colors.content.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                )
            }

            if (!loggedIn) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    GlassSurface(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(26.dp),
                    ) {
                        Column(
                            Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "登录夸克网盘",
                                style = MaterialTheme.typography.titleMedium,
                                color = GlasmioTheme.colors.content,
                            )
                            Text(
                                "用夸克 App 扫码授权，登录状态只保存在本机，随时可以退出。",
                                style = MaterialTheme.typography.bodySmall,
                                color = GlasmioTheme.colors.content.copy(alpha = 0.6f),
                            )
                            Spacer(Modifier.height(2.dp))
                            GlassPill("扫码登录", icon = "qrcode", onClick = { startQrLogin() })
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(files, key = { it.fid }) { file ->
                        CloudRow(
                            title = file.name,
                            subtitle = if (file.isDir) "文件夹" else formatSize(file.size),
                            isDir = file.isDir,
                            onClick = {
                                if (file.isDir) {
                                    crumbs += file.fid to file.name
                                } else {
                                    playVideo(file, files)
                                }
                            },
                        )
                    }
                    if (hasMore) {
                        item(key = "more") {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                GlassPill(
                                    text = if (loading) "加载中…" else "加载更多",
                                    onClick = {
                                        if (!loading) load(currentFolder.first, page + 1, append = true)
                                    },
                                )
                            }
                        }
                    }
                    if (files.isEmpty() && !loading) {
                        item(key = "empty") {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 60.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "这个文件夹里没有内容",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = GlasmioTheme.colors.content.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                    item(key = "logout") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            GlassPill(
                                text = "退出登录",
                                danger = true,
                                onClick = {
                                    api.logout()
                                    loggedIn = false
                                    files = emptyList()
                                    crumbs.clear()
                                    crumbs += "0" to "我的网盘"
                                },
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
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
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
                        Modifier
                            .size(220.dp)
                            .background(Color(0x22FFFFFF), RoundedCornerShape(16.dp)),
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
    }
}

@Composable
private fun CloudRow(
    title: String,
    subtitle: String,
    isDir: Boolean,
    onClick: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SfIcon(
                systemName = if (isDir) "folder" else "play.rectangle",
                contentDescription = null,
                size = 18.dp,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
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
                systemName = if (isDir) "chevron.right" else "play.fill",
                contentDescription = null,
                size = 14.dp,
            )
        }
    }
}

private fun loadQrBitmap(url: String): android.graphics.Bitmap? {
    val qr = "https://api.qrserver.com/v1/create-qr-code/?size=480x480&margin=10&data=" +
        URLEncoder.encode(url, "UTF-8")
    val conn = (URL(qr).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
    }
    return conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
}

private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "mov", "ts", "m4v", "flv", "webm", "rmvb", "wmv", "mpg", "mpeg", "3gp",
)

private fun isVideoFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

/** 数字补零后比较，避免 "第10集" 排到 "第2集" 前面。 */
private fun sortKey(name: String): String =
    episodeOnlyNumber(name)
        ?.let { "ep" + it.toString().padStart(6, '0') }
        ?: name.lowercase().replace(Regex("\\d+")) { it.value.padStart(6, '0') }

private fun seriesTitle(name: String, folder: String): String {
    val stem = QUALITY_TOKEN.replace(name.substringBeforeLast('.'), "")
    val cleaned = Regex(
        "(?i)[\\s._\\-\\[\\]()]*(?:第\\s*\\d{1,4}\\s*[集话話]|s\\d{1,2}\\s*e\\d{1,3}|ep?\\s*\\d{1,4}|\\d{1,4})" +
            "[\\s._\\-\\[\\]()]*\$",
    ).replace(stem, "").replace(Regex("[._\\-]+"), " ").trim()
    return cleaned.ifBlank { folder }.take(24)
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

/** 去掉清晰度/编码等后缀，保留「剧名 + 集号」，让弹幕搜索和界面标题都干净。 */
private val QUALITY_TOKEN = Regex(
    "(?i)(2160p|1080p|720p|480p|4k|8k|hdr10|hdr|web[-._ ]?dl|blu[-._ ]?ray|remux|hevc|h265|x265|av1|aac|dts|10bit)",
)

private fun cleanTitle(name: String): String {
    val base = name.substringBeforeLast('.').ifBlank { name }
    val cut = QUALITY_TOKEN.find(base)?.range?.first ?: return base.trim()
    return base.substring(0, cut).trimEnd('.', ' ', '_', '-').ifBlank { base.trim() }
}

@Composable
private fun CloudWallpaper(isDark: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.verticalGradient(
                if (isDark) listOf(Color(0xFF070A12), Color(0xFF101828))
                else listOf(Color(0xFFF2F5FB), Color(0xFFDCE6F7)),
            ),
        )
        val accent = if (isDark) Color(0xFF2F5CFF) else Color(0xFF9BBCFF)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.5f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(size.width * 0.88f, size.height * 0.12f),
                radius = size.width * 0.9f,
            ),
            radius = size.width * 0.9f,
            center = androidx.compose.ui.geometry.Offset(size.width * 0.88f, size.height * 0.12f),
        )
        val warm = if (isDark) Color(0xFFFF6B6B) else Color(0xFFFFB4A8)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(warm.copy(alpha = 0.35f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(size.width * 0.05f, size.height * 0.92f),
                radius = size.width * 0.8f,
            ),
            radius = size.width * 0.8f,
            center = androidx.compose.ui.geometry.Offset(size.width * 0.05f, size.height * 0.92f),
        )
    }
}

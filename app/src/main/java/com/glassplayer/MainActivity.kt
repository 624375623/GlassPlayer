package com.glassplayer

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.glassplayer.data.QuarkApi
import com.glassplayer.data.SettingsStore
import com.glassplayer.data.VideoItem
import com.glassplayer.player.PlayerActivity
import com.glassplayer.ui.LibraryScreen
import com.glassplayer.ui.cloudSortKey
import com.glassplayer.ui.cloudSeriesTitle
import com.glassplayer.ui.displayTitle
import com.glassplayer.ui.episodeOnlyNumber
import com.glassplayer.ui.isVideoName
import io.github.neoruaa.glasmio.core.GlasmioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val refreshTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.glassplayer.data.DebugLog.installCrashRecorder(this)
        com.glassplayer.data.DebugLog.restoreLastCrash(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 实验性全局刷新率：必须在 setContent 之前设好窗口模式，首帧就按目标刷新率合成
        com.glassplayer.data.RefreshRate.apply(this, SettingsStore(this).refreshRateMode)

        setContent {
            val settings = remember { SettingsStore(this@MainActivity) }
            var themeMode by remember { mutableStateOf(settings.themeMode) }
            val dark = when (themeMode) {
                1 -> true
                2 -> false
                else -> isSystemInDarkTheme()
            }

            LaunchedEffect(dark) {
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }

            GlasmioTheme(
                isDark = dark,
                colorScheme = io.github.neoruaa.glasmio.core.glasmioColorScheme(dark),
            ) {
                LibraryScreen(
                    settings = settings,
                    isDark = dark,
                    themeMode = themeMode,
                    refreshKey = refreshTick.intValue,
                    onThemeModeChange = {
                        themeMode = it
                        settings.themeMode = it
                    },
                    onOpenVideo = { items, index -> openPlayer(items, index) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从设置 / 息屏 / 其它应用回来时，窗口属性可能被系统重置，重新按设置挑一次显示模式
        com.glassplayer.data.RefreshRate.apply(this, SettingsStore(this).refreshRateMode)
        refreshTick.intValue++
    }

    private fun openPlayer(items: List<VideoItem>, index: Int) {
        val tappedItem = items.getOrNull(index) ?: return
        // 网盘条目用 quark://fid 作为稳定身份，播放前才解析成有时效的直链
        if (tappedItem.uri.startsWith("quark://")) {
            openCloudVideo(tappedItem)
            return
        }
        if (items.isEmpty()) return
        // 一个文件夹可能有上千条视频（例如 DCIM/Camera），把整表塞进 Intent 和
        // MediaController 会超出 Binder 传输上限，表现为卡死 / 闪退 / 黑屏。
        // 这里只取被点条目前后各 MAX_NEIGHBORS 条，既保留前后切换能力，又让传输量恒定。
        val tapped = index.coerceIn(0, items.size - 1)
        // 目录里可能有上千条视频，跨目录连续播放需要把整段列表带过去；
        // 但 Intent/Binder 有 1MB 上限，所以最多带 1001 条，超出的按点击位置取窗口。
        val maxSpan = if (items.size <= MAX_PLAYLIST) items.size else MAX_NEIGHBORS * 2 + 1
        val from = if (items.size <= MAX_PLAYLIST) 0 else (tapped - MAX_NEIGHBORS).coerceAtLeast(0)
        val to = if (items.size <= MAX_PLAYLIST) items.size else (tapped + MAX_NEIGHBORS + 1).coerceAtMost(items.size)
        val playlist = items.subList(from, to)

        val intent = Intent(this, PlayerActivity::class.java).apply {
            putStringArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(playlist.map { it.uri }))
            putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(playlist.map { it.title }))
            putStringArrayListExtra(PlayerActivity.EXTRA_KEYS, ArrayList(playlist.map { it.uri }))
            putExtra(PlayerActivity.EXTRA_INDEX, tapped - from)
        }
        Log.i("GlassPlayer", "openPlayer total=${items.size} playlist=${playlist.size} index=${tapped - if (items.size <= MAX_PLAYLIST) 0 else from}")
        startActivity(intent)
        // 主页 → 播放页：播放页从下方轻微浮起，主页稍微压暗，避免硬切
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.gp_player_in, R.anim.gp_fade_out_soft)
    }

    /** 网盘视频：把所在目录整季拉出来串成播放列表，只解析当前集，mediaId 仍用 fid。 */
    private fun openCloudVideo(item: VideoItem) {
        val fid = item.uri.removePrefix("quark://")
        lifecycleScope.launch {
            val settings = SettingsStore(this@MainActivity)
            val api = QuarkApi(this@MainActivity, settings)
            val cacheMode = settings.quarkCachePlayback

            // 从收藏元信息找到这个视频所在的网盘目录
            val saved = settings.cloudVideo(fid)
            val parentFid = saved?.parentFid

            // 拉整目录并按集号排序，逻辑与网盘页 CloudPane 保持一致
            val built: Pair<List<QuarkApi.QuarkFile>, Int>? =
                if (parentFid.isNullOrBlank()) {
                    null
                } else {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            var page = api.list(parentFid, 1)
                            var files = page.first
                            var pageNo = 1
                            var guard = 0
                            while (page.second && guard < 8) {
                                pageNo += 1
                                page = api.list(parentFid, pageNo)
                                files = files + page.first
                                guard += 1
                            }
                            val pool = files.filter { !it.isDir && isVideoName(it.name) }
                                .distinctBy { it.fid }
                            val sorted = pool.sortedWith(
                                compareBy(
                                    { episodeOnlyNumber(it.name) ?: Int.MAX_VALUE },
                                    { cloudSortKey(it.name) },
                                ),
                            )
                            val windowed = if (sorted.size <= 80) {
                                sorted
                            } else {
                                val cursor = sorted.indexOfFirst { it.fid == fid }.coerceAtLeast(0)
                                val from = (cursor - 40)
                                    .coerceIn(0, (sorted.size - 80).coerceAtLeast(0))
                                sorted.subList(from, (from + 80).coerceAtMost(sorted.size)).toList()
                            }
                            // 目录可能已变化导致当前集不在列表里，用收藏元信息补一个占位
                            val withCurrent = if (windowed.any { it.fid == fid }) {
                                windowed
                            } else {
                                windowed + QuarkApi.QuarkFile(
                                    fid = fid,
                                    name = saved?.name ?: item.title,
                                    isDir = false,
                                    size = saved?.size ?: item.sizeBytes,
                                    updatedAt = saved?.updatedAt ?: item.dateAddedSec,
                                )
                            }
                            withCurrent to withCurrent.indexOfFirst { it.fid == fid }.coerceAtLeast(0)
                        }.getOrNull()
                    }
                }

            // 解析当前集直链：优先流播，失败/缓存模式回退下载直链
            var playUrl = if (cacheMode) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    runCatching { api.streamUrl(fid) }.getOrNull()?.takeIf { it.isNotBlank() }
                }
            }
            if (playUrl == null) {
                playUrl = withContext(Dispatchers.IO) {
                    runCatching { api.downloadUrl(fid) }.getOrNull()?.takeIf { it.isNotBlank() }
                }
            }
            if (playUrl.isNullOrBlank()) {
                Toast.makeText(
                    this@MainActivity,
                    "解析网盘播放地址失败，请检查登录状态",
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }

            // 整目录拿不到时退回单集，保证至少能播
            val ordered = built?.first ?: listOf(
                QuarkApi.QuarkFile(
                    fid = fid,
                    name = saved?.name ?: item.title,
                    isDir = false,
                    size = saved?.size ?: item.sizeBytes,
                    updatedAt = saved?.updatedAt ?: item.dateAddedSec,
                ),
            )
            val index = built?.second ?: 0
            val total = ordered.size
            val uris = ArrayList<String>(total).apply { repeat(total) { add("") } }
            uris[index] = playUrl

            val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
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
                    cloudSeriesTitle(saved?.name ?: item.title, saved?.parentName.orEmpty()),
                )
            }
            startActivity(intent)
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.gp_player_in, R.anim.gp_fade_out_soft)
        }
    }

    private companion object {
        /** 整表直接带过去的上限（超过这个数量就只带点击位置附近的窗口） */
        const val MAX_PLAYLIST = 1000
        /** 超大目录时，被点视频两侧各自携带的邻居数量，总条目 <= 2 * MAX_NEIGHBORS + 1 */
        const val MAX_NEIGHBORS = 500
    }
}

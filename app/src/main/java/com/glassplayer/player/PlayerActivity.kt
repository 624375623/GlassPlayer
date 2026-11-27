package com.glassplayer.player

import com.glassplayer.R
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.glassplayer.data.SettingsStore
import com.glassplayer.data.DebugLog
import io.github.neoruaa.glasmio.core.GlasmioTheme
import kotlin.math.roundToInt

/** Fullscreen playback host: immersive chrome, gestures, PiP and playback memory. */
class PlayerActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()
    private lateinit var settings: SettingsStore
    private var audioManager: AudioManager? = null
    private val pipMode = mutableStateOf(false)
    private var brightnessLevel = -1f
    private var videoWidth = 0
    private var videoHeight = 0
    private var lastPipParams: PictureInPictureParams? = null

    private var autoOrientation = true
    /** 播放中的定时进度落盘任务，null 表示当前没有在跑。 */
    private var progressTicker: Runnable? = null
    // 播放页「锁定屏幕」期间的方向锁：锁定时按当前方向定死，解锁恢复原来的策略
    private var orientationLocked = false
    private var orientationBeforeLock = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SettingsStore(this).secureScreen) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        settings = SettingsStore(this)
        // 实验性全局刷新率：播放页也用同一套窗口显示模式，避免主页 120Hz、播放页回落 60Hz
        com.glassplayer.data.RefreshRate.apply(this, settings.refreshRateMode)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        autoOrientation = settings.autoOrientation
        // 开启「跟随视频比例」时先不锁方向，等拿到视频尺寸再决定横竖屏
        requestedOrientation = if (autoOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        audioManager = getSystemService(AudioManager::class.java)

        val seriesLabel = intent.getStringExtra(EXTRA_SERIES)
        // 从画中画 / 单实例复用进来时，intent 里已经换成了新的视频列表：
        // 不能再用 savedInstanceState 里的旧集数和进度，否则会恢复成上一部片
        val freshStart = intent.getBooleanExtra(EXTRA_FRESH_START, false)
        if (freshStart) intent.removeExtra(EXTRA_FRESH_START)
        val entries = readEntries()
        // 通知 PendingIntent 不带数据，或进程重建后 extra 丢失：没有可播内容就回主页，
        // 不留空白播放页
        if (entries.isEmpty()) {
            DebugLog.record("player", "播放页无有效条目，回主页 · 重建=${savedInstanceState != null}")
            finish()
            return
        }
        // 进程/Activity 被系统回收后恢复：优先用保存下来的集数和进度
        val savedIndex = if (freshStart) -1 else savedInstanceState?.getInt(STATE_INDEX, -1) ?: -1
        val startIndex = (if (savedIndex >= 0) savedIndex else intent.getIntExtra(EXTRA_INDEX, 0))
            .coerceIn(0, entries.size - 1)
        val savedPosition = if (freshStart) 0L else savedInstanceState?.getLong(STATE_POSITION, 0L) ?: 0L
        val resumePosition = savedPosition.takeIf { it > 0 }
            ?: intent.getLongExtra(EXTRA_POSITION, 0L).takeIf { it > 0 }
            ?: entries.getOrNull(startIndex)?.let {
                if (settings.resumeEnabled) settings.positionOf(it.key) else 0L
            } ?: 0L
        DebugLog.record(
            "player",
            "播放页创建 · 条目=${entries.size} · 集=${startIndex + 1} · 进度=$resumePosition · " +
                "重建=${savedInstanceState != null} · 新请求=$freshStart",
        )

        // 诊断用：确认 content:// 流能否正常打开（部分授权/受限目录会在这里失败）
        // 网盘直链是 http(s)，不是 content://，跳过本地流探测
        entries.getOrNull(startIndex)?.takeIf { it.uri.startsWith("content://") }?.let { entry ->
            runCatching {
                contentResolver.openFileDescriptor(Uri.parse(entry.uri), "r")?.use { pfd ->
                    Log.i(TAG, "open ok uri=${entry.uri} size=${pfd.statSize}")
                } ?: Log.e(TAG, "open null uri=${entry.uri}")
            }.onFailure { Log.e(TAG, "open failed uri=${entry.uri}", it) }
        }

        viewModel.connect()

        setContent {
            GlasmioTheme(isDark = true) {
                PlayerScreen(
                    controller = viewModel.controller,
                    entries = entries,
                    initialIndex = startIndex,
                    startPositionMs = resumePosition,
                    settings = settings,
                    isInPip = pipMode.value,
                    onBack = { finish() },
                    onEnterPip = { enterPipMode() },
                    onToggleOrientation = { toggleOrientation() },
                    onLockChange = { setOrientationLocked(it) },
                    onBrightnessDelta = { adjustBrightness(it) },
                    onVolumeDelta = { adjustVolume(it) },
                    onVideoSizeChanged = { width, height ->
                        if (autoOrientation && !orientationLocked && width > 0 && height > 0) {
                            requestedOrientation = if (width >= height) {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            }
                        }
                        videoWidth = width
                        videoHeight = height
                        refreshPipParams()
                    },
                    onSessionReady = { index, position ->
                        viewModel.enqueue(entries, index, position)
                    },
                    connectionError = viewModel.connectionError,
                    onRetryConnect = { viewModel.reconnect() },
                    initialSeries = seriesLabel,
                )
            }
        }
    }

    override fun finish() {
        super.finish()
        // 播放页 → 主页：播放页淡出并略下沉，主页淡入
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.gp_fade_in_soft, R.anim.gp_player_out)
    }

    private fun readEntries(): List<PlaylistEntry> {
        val uris = intent.getStringArrayListExtra(EXTRA_URIS).orEmpty()
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES).orEmpty()
        val keys = intent.getStringArrayListExtra(EXTRA_KEYS).orEmpty()
        val fids = intent.getStringArrayListExtra(EXTRA_FIDS).orEmpty()
        // 网盘：fids 决定条目数量，uris 只填当前这一集，其余等播放页按需解析
        val count = maxOf(uris.size, titles.size, fids.size)
        return (0 until count).map { index ->
            PlaylistEntry(
                uri = uris.getOrElse(index) { "" },
                title = titles.getOrElse(index) { "视频 ${index + 1}" },
                key = keys.getOrElse(index) { fids.getOrElse(index) { "" } },
                fid = fids.getOrNull(index)?.takeIf { it.isNotBlank() },
            )
        }
    }

    private fun adjustBrightness(delta: Float): Float {
        val attributes = window.attributes
        val base = if (brightnessLevel >= 0f) {
            brightnessLevel
        } else if (attributes.screenBrightness >= 0f) {
            attributes.screenBrightness
        } else {
            0.5f
        }
        brightnessLevel = (base + delta).coerceIn(0.02f, 1f)
        attributes.screenBrightness = brightnessLevel
        window.attributes = attributes
        return brightnessLevel
    }

    private fun adjustVolume(delta: Float): Float {
        val manager = audioManager ?: return 0f
        val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (current + delta * maximum).roundToInt().coerceIn(0, maximum)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        return if (maximum > 0) target.toFloat() / maximum else 0f
    }

    /**
     * 锁定屏幕：除了藏起控件，还要把 Activity 的方向钉在当前朝向。
     * 只锁 Compose 布局是没用的——Activity 声明了 configChanges，系统仍会把
     * Configuration 翻成横/竖，Compose 跟着重组，看起来就是「锁了还能转」。
     */
    private fun setOrientationLocked(lock: Boolean) {
        if (lock == orientationLocked) return
        if (lock) {
            orientationBeforeLock = requestedOrientation
            orientationLocked = true
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            orientationLocked = false
            requestedOrientation = orientationBeforeLock
            if (autoOrientation && videoWidth > 0 && videoHeight > 0) {
                requestedOrientation = if (videoWidth >= videoHeight) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                }
            }
        }
    }

    private fun toggleOrientation() {
        if (orientationLocked) return
        autoOrientation = false
        requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun refreshPipParams() {
        val builder = PictureInPictureParams.Builder()
        if (videoWidth > 0 && videoHeight > 0) {
            // 系统对画中画宽高比有硬限制：必须在 0.418410 ~ 2.390000 之间，
            // 源视频是超宽/超窄（比如 480x2160、32:9）时直接传会抛 IllegalArgumentException 崩掉。
            val raw = videoWidth.toFloat() / videoHeight.toFloat()
            if (raw.isFinite() && raw > 0f) {
                val ratio = raw.coerceIn(PIP_MIN_RATIO, PIP_MAX_RATIO)
                builder.setAspectRatio(
                    Rational((ratio * 1000f).roundToInt().coerceAtLeast(1), 1000),
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(settings.autoPip)
        }
        val params = runCatching { builder.build() }.getOrNull() ?: return
        lastPipParams = params
        // 没开自动画中画、也没在画中画里时不必每次视频尺寸变化都走一次 IPC
        if (settings.autoPip || pipMode.value) {
            // 个别机型对参数校验更严，兜底不让它上升成崩溃
            runCatching { setPictureInPictureParams(params) }
        }
    }

    private fun enterPipMode() {
        refreshPipParams()
        val params = lastPipParams ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { enterPictureInPictureMode(params) }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (settings.autoPip && viewModel.controller?.isPlaying == true && !pipMode.value) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipMode.value = isInPictureInPictureMode
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 系统回收前把当前集数和进度存下来，重建后能接着播，而不是退回主页
        val c = viewModel.controller
        // -1（INDEX_UNSET）不能存进状态，否则重建时会被当成有效下标
        outState.putInt(STATE_INDEX, (c?.currentMediaItemIndex ?: 0).coerceAtLeast(0))
        outState.putLong(STATE_POSITION, c?.currentPosition ?: 0L)
    }

    override fun onPause() {
        super.onPause()
        rememberPosition()
    }

    override fun onResume() {
        super.onResume()
        com.glassplayer.data.RefreshRate.apply(this, settings.refreshRateMode)
        startProgressTicker()
    }

    override fun onStop() {
        super.onStop()
        stopProgressTicker()
        rememberPosition()
        DebugLog.record(
            "player",
            "播放页进入后台 · 后台播放=${settings.backgroundPlay} · 画中画=${pipMode.value}",
        )
        // 默认不后台播放：离开应用即暂停；画中画里继续播，设置里可开启后台播放
        if (!settings.backgroundPlay && !pipMode.value) {
            runCatching { viewModel.controller?.pause() }
        }
    }

    private fun rememberPosition() {
        val controller = viewModel.controller ?: return
        val item = controller.currentMediaItem
        // MediaController 在切集/回收瞬间可能拿不到条目；退回 localConfiguration 里的 uri，
        // 不要把这一帧的进度直接丢掉（丢掉就是「看了但没进记录」）
        val uri = item?.mediaId?.takeIf { it.isNotBlank() }
            ?: item?.localConfiguration?.uri?.toString()
            ?: return
        if (uri.isBlank()) return
        val position = runCatching { controller.currentPosition }.getOrDefault(0L)
        if (position > 0) {
            settings.savePosition(uri, position)
        } else {
            // 只播了几秒、进度还没攒起来也要记一条「看过」，记录页才会出现
            settings.markPlayed(uri)
        }
        // 网盘接口不返回时长，第一次播出来后回写到收藏/记录的元信息里，列表就不再显示 00:00
        if (uri.startsWith("quark://")) {
            val duration = runCatching { controller.duration }.getOrDefault(0L)
            if (duration > 0) {
                val fid = uri.removePrefix("quark://")
                settings.cloudVideo(fid)?.let { video ->
                    if (video.durationMs != duration) {
                        settings.saveCloudVideo(video.copy(durationMs = duration))
                    }
                }
            }
        }
    }

    /** 播放中每隔几秒落一次进度：只靠 onPause / onStop，进程被系统直接杀掉时会整段丢失。 */
    private fun startProgressTicker() {
        if (progressTicker != null) return
        val runnable = object : Runnable {
            override fun run() {
                rememberPosition()
                window.decorView.postDelayed(this, PROGRESS_SAVE_INTERVAL_MS)
            }
        }
        progressTicker = runnable
        window.decorView.postDelayed(runnable, PROGRESS_SAVE_INTERVAL_MS)
    }

    private fun stopProgressTicker() {
        progressTicker?.let { runnable -> window.decorView.removeCallbacks(runnable) }
        progressTicker = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // PlayerActivity 是 singleTop：在画中画里从主页点另一部片时不会新建实例，
        // 只会回调到这里。以前只 setIntent，entries 仍然是 onCreate 时算的旧列表，
        // 结果电视/画中画继续播上一部。这里带一个「新请求」标记重建页面，
        // onCreate 看到标记就跳过旧集数/进度的恢复。
        val hasPlaylist = intent.getStringArrayListExtra(EXTRA_URIS)?.isNotEmpty() == true ||
            intent.getStringArrayListExtra(EXTRA_FIDS)?.isNotEmpty() == true
        if (!hasPlaylist) {
            // 通知栏等不带播放参数的 intent：只刷新一下，不重建
            setIntent(intent)
            return
        }
        DebugLog.record("player", "单实例收到新播放请求，重建播放页")
        intent.putExtra(EXTRA_FRESH_START, true)
        setIntent(intent)
        runCatching { recreate() }.onFailure {
            DebugLog.record("player", "重建播放页失败：${it.message}")
        }
    }

    companion object {
        private const val TAG = "GlassPlayer"

        /** 播放中落盘进度的间隔：太短会频繁写 prefs，太长则被杀进程时丢得多。 */
        private const val PROGRESS_SAVE_INTERVAL_MS = 5_000L
        const val EXTRA_URIS = "extra_uris"
        const val EXTRA_TITLES = "extra_titles"
        const val EXTRA_INDEX = "extra_index"
        const val EXTRA_POSITION = "extra_position"
        const val EXTRA_KEYS = "extra_keys"
        const val EXTRA_SERIES = "extra_series"
        const val EXTRA_FIDS = "extra_fids"

        /** singleTop 复用实例时标记「这是一个全新的播放请求」，避免恢复旧的集数/进度。 */
        const val EXTRA_FRESH_START = "extra_fresh_start"

        // 实例状态保存/恢复的键
        private const val STATE_INDEX = "state_index"
        private const val STATE_POSITION = "state_position"

        // 系统画中画宽高比的合法区间
        const val PIP_MIN_RATIO = 0.418410f
        const val PIP_MAX_RATIO = 2.390000f
    }
}

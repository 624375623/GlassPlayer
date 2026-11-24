package com.glassplayer.cast

import android.content.Context
import android.net.Uri
import com.glassplayer.data.DebugLog
import com.glassplayer.data.QuarkApi
import com.glassplayer.data.SettingsStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 投屏总控：发现设备 → 准备可播地址 → 交给电视 → 轮询电视端状态。
 *
 * 手机这边只做「指挥」，画面在电视上解。播放期间手机端播放器应当暂停，
 * 否则两头同时出声；恢复由界面层负责。
 */
object CastManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state.asStateFlow()

    private var server: LocalMediaServer? = null
    private var pollJob: Job? = null
    private var currentDevice: CastDevice? = null
    private var currentSpeed = 1.0

    // ---- 设备发现：跑在自己的长生命周期 scope，不绑定 Compose 弹层 ----
    // 旧实现让 LaunchedEffect 直接 await 发现结果，发现要 7 秒多，期间弹层一旦
    // 重组/动画导致 composition 作用域取消，整个发现就被中断（日志里的
    // 「The coroutine scope left the composition」）。改成后台跑、结果走 flow。
    private val _discovered = MutableStateFlow<List<CastDevice>>(emptyList())
    val discovered: StateFlow<List<CastDevice>> = _discovered.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var discoveryJob: Job? = null

    /** 触发一次扫描。可重复调用：取消上一次、后台重新跑。 */
    fun startDiscovery(context: Context, timeoutMs: Long = 6000) {
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            _scanning.value = true
            val found = try {
                SsdpDiscovery.discover(context, timeoutMs)
            } catch (ce: CancellationException) {
                // 协程取消要正常向上抛，不能当成普通失败吞掉
                throw ce
            } catch (e: Exception) {
                DebugLog.record("cast", "ssdp 失败 ${e.message}")
                emptyList()
            }
            _discovered.value = found
            _scanning.value = false
        }
    }

    /**
     * 开始投屏。
     *
     * [uri] 支持四种形态：
     * - `quark://<fid>` 网盘：取夸克直链，手机做带鉴权头的代理；
     * - `http(s)://` 网络地址：同样走代理，避免电视缺 Referer 被拒；
     * - `file://` / `content://`：起本地文件服务；
     */
    suspend fun cast(context: Context, device: CastDevice, uri: String, title: String) =
        withContext(Dispatchers.IO) {
            // 换设备前先把上一台的会话收干净，否则两台电视会同时拉手机上的流
            val previous = currentDevice
            pollJob?.cancel()
            pollJob = null
            runCatching { server?.stop() }
            server = null
            currentDevice = null
            if (previous != null) runCatching { DlnaControl.stop(previous) }

            currentDevice = device
            _state.value = CastState(device = device, connecting = true, title = title)

            val source = runCatching { prepareSource(context, uri, title) }.getOrElse { error ->
                DebugLog.record("cast", "准备素材失败 ${error.message}")
                _state.value = CastState(
                    device = device,
                    message = error.message ?: "这个视频没法投屏",
                )
                return@withContext
            }

            val mediaServer = LocalMediaServer(context.applicationContext)
            val url = runCatching { mediaServer.serve(source) }.getOrElse { error ->
                _state.value = CastState(device = device, message = error.message ?: "本地服务启动失败")
                return@withContext
            }
            server = mediaServer

            val uriOk = DlnaControl.setUri(device, url, title, source.contentType)
            val playOk = uriOk && DlnaControl.play(device)
            if (!uriOk || !playOk) {
                DebugLog.record(
                    "cast",
                    "设备拒绝接收 ${device.name} · setUri=$uriOk play=$playOk",
                )
                mediaServer.stop()
                server = null
                currentDevice = null
                _state.value = CastState(device = device, message = "电视拒绝了这条视频，换台设备试试")
                return@withContext
            }

            _state.value = CastState(
                device = device,
                casting = true,
                playing = true,
                title = title,
            )
            startPolling(device)
        }

    /** 手机要暂停/继续时同步给电视。 */
    fun setPaused(paused: Boolean) {
        val device = currentDevice ?: return
        scope.launch {
            if (paused) {
                // 设备还在 TRANSITIONING 时直接 Pause 会被 501 拒绝（Transition to PAUSE not allowed），
                // 先等它进入 PLAYING/PAUSED/STOPPED 等稳定状态，最多等约 4 秒
                var waited = 0
                while (waited < 8) {
                    val st = DlnaControl.transportState(device)
                    if (st == "PLAYING" || st == "PAUSED" || st == "STOPPED") break
                    delay(500)
                    waited++
                }
                runCatching { DlnaControl.pause(device) }
            } else {
                runCatching { DlnaControl.play(device, currentSpeed) }
            }
            _state.value = _state.value.copy(playing = !paused)
        }
    }

    fun seekTo(positionMs: Long) {
        val device = currentDevice ?: return
        scope.launch {
            runCatching { DlnaControl.seek(device, positionMs) }
            _state.value = _state.value.copy(positionMs = positionMs)
        }
    }

    fun setVolume(volume: Int) {
        val device = currentDevice ?: return
        scope.launch { runCatching { DlnaControl.setVolume(device, volume) } }
    }

    /** 结束投屏：停电视、关服务、清状态。 */
    fun stop() {
        val device = currentDevice
        pollJob?.cancel()
        pollJob = null
        currentDevice = null
        runCatching { server?.stop() }
        server = null
        _state.value = CastState()
        if (device != null) scope.launch { runCatching { DlnaControl.stop(device) } }
    }

    private fun startPolling(device: CastDevice) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var failures = 0
            while (true) {
                delay(2000)
                val (position, duration) = runCatching {
                    DlnaControl.positionInfo(device)
                }.getOrElse { 0L to 0L }
                val transport = runCatching {
                    DlnaControl.transportState(device)
                }.getOrNull()

                if (transport == null) {
                    failures++
                    // 连续三次都问不到，基本是电视断开了
                    if (failures >= 3) {
                        _state.value = CastState(
                            device = device,
                            message = "和电视断开了，重新连接试试",
                        )
                        runCatching { server?.stop() }
                        return@launch
                    }
                } else {
                    failures = 0
                }

                _state.value = _state.value.copy(
                    positionMs = position,
                    durationMs = if (duration > 0) duration else _state.value.durationMs,
                    playing = transport == "PLAYING",
                    casting = transport != "STOPPED" && transport != null,
                )
            }
        }
    }

    /** 把不同来源统一成电视能取的地址。 */
    private suspend fun prepareSource(context: Context, uri: String, title: String): LocalMediaServer.Source {
        val mime = LocalMediaServer.contentTypeOf(title)

        if (uri.startsWith("quark://")) {
            val fid = uri.removePrefix("quark://")
            val settings = SettingsStore(context)
            val api = QuarkApi(context, settings)
            // 原文件 mp4 兼容性最好，先试直链；失败再用转码 m3u8
            val download = runCatching { api.downloadUrl(fid) }.getOrNull()
            val stream = if (download.isNullOrBlank()) {
                runCatching { api.streamUrl(fid) }.getOrNull()
            } else {
                null
            }
            val url = download ?: stream ?: throw IllegalStateException("网盘没给出可播放地址")
            val contentType = if (url.contains(".m3u8")) "application/vnd.apple.mpegurl" else mime
            return LocalMediaServer.RemoteSource(url, settings.quarkCookie, contentType, quark = true)
        }

        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            val contentType = if (uri.contains(".m3u8")) "application/vnd.apple.mpegurl" else mime
            // 防御：万一夸克完整直链从其他路径进来，按 host 识别并带上会话 Cookie
            val isQuark = runCatching {
                java.net.URL(uri).host?.endsWith("quark.cn", ignoreCase = true) == true
            }.getOrDefault(false)
            val cookie = if (isQuark) SettingsStore(context).quarkCookie else null
            return LocalMediaServer.RemoteSource(uri, cookie, contentType, quark = isQuark)
        }

        if (uri.startsWith("file://")) {
            val path = uri.removePrefix("file://")
            val file = File(path)
            if (!file.exists() || file.length() <= 0) throw IllegalStateException("文件不存在")
            return LocalMediaServer.LocalFileSource(
                opener = { runCatching { java.io.FileInputStream(file) as java.io.InputStream }.getOrNull() },
                length = file.length(),
                contentType = mime,
            )
        }

        val local = LocalMediaServer.localSource(context, Uri.parse(uri), title)
            ?: throw IllegalStateException("读不到这个视频")
        return local
    }
}

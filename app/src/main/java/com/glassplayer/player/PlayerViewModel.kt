package com.glassplayer.player

import android.app.Application
import android.content.ComponentName
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

data class PlaylistEntry(
    val uri: String,
    val title: String,
    val key: String = uri,
    /** 网盘文件的 fid：直链进播放页后才按需解析，避免一次性解析整季被网盘限流。 */
    val fid: String? = null,
)

/** 未解析直链的网盘集占位前缀。 */
const val PENDING_SCHEME = "glassplayer-pending://"

/** 跨进程可靠的「未解析直链」标记：MediaController 拿到的 MediaItem 里 localConfiguration 可能为空。 */
const val PENDING_EXTRA = "gp_pending"

/**
 * 网盘 fid 挂在每个 MediaItem 的 extras 上。
 * 播放列表可以被上移/移除，此时「界面下标 → entries 下标」已经错位，
 * 只有跟着条目本身走的 fid 才能保证按需解析、投屏切集取到的是同一条视频。
 */
const val FID_EXTRA = "gp_fid"

/** Owns the MediaController connection to [PlaybackService]. */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    var controller by mutableStateOf<MediaController?>(null)
        private set

    var connectionError by mutableStateOf<String?>(null)
        private set

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var pendingAction: (() -> Unit)? = null

    fun connect() {
        if (controllerFuture != null || controller != null) return
        val context = getApplication<Application>()
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { mediaController ->
                        controller = mediaController
                        pendingAction?.invoke()
                        pendingAction = null
                    }
                    .onFailure { connectionError = it.message ?: "播放服务启动失败" }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /** 连接失败后重试：释放旧 Future 再重新绑定服务。 */
    fun reconnect() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        connectionError = null
        connect()
    }

    fun enqueue(entries: List<PlaylistEntry>, startIndex: Int, startPositionMs: Long) {
        val action: () -> Unit = {
            val mediaController = controller
            if (mediaController != null) {
                val items = entries.map { entry ->
                    // 还没解析到直链的集用占位地址，播放页切到它之前会替换成真实地址
                    val playableUri = entry.uri.ifBlank {
                        entry.fid?.let { PENDING_SCHEME + it } ?: entry.uri
                    }
                    val pending = entry.uri.isBlank() && entry.fid != null
                    MediaItem.Builder()
                        .setUri(playableUri)
                        .setMediaId(entry.key)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(entry.title)
                                .setIsPlayable(true)
                                .setIsBrowsable(false)
                                .setExtras(
                                    android.os.Bundle().apply {
                                        if (pending) putBoolean(PENDING_EXTRA, true)
                                        entry.fid?.let { putString(FID_EXTRA, it) }
                                    },
                                )
                                .build(),
                        )
                        .build()
                }
                mediaController.setMediaItems(items, startIndex, startPositionMs.coerceAtLeast(0L))
                mediaController.prepare()
                mediaController.play()
            }
        }
        if (controller != null) action() else pendingAction = action
    }

    override fun onCleared() {
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onCleared()
    }
}

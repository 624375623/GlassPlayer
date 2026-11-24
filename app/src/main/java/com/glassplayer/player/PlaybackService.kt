package com.glassplayer.player

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.content.Intent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.HttpDataSource
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.glassplayer.data.DebugLog
import com.glassplayer.data.SettingsStore
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Hosts the ExoPlayer instance so playback survives the player UI and keeps the
 * media notification / lock-screen controls alive.
 */
class PlaybackService : MediaSessionService() {

    private companion object {
        const val TAG = "GlassPlayer"
    }

    private var mediaSession: MediaSession? = null

    /**
     * 网盘视频是 http 直链，必须带 Cookie / Referer 才能下；
     * 「边下边播」打开时再套一层 CacheDataSource，播过的片段留在本地。
     */
    private fun mediaDataSourceFactory(): DataSource.Factory = object : DataSource.Factory {
        override fun createDataSource(): DataSource {
            val settings = SettingsStore(this@PlaybackService)
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent(QUARK_UA)
                .setDefaultRequestProperties(
                    buildMap {
                        put("Referer", "https://pan.quark.cn/")
                        put("Origin", "https://pan.quark.cn")
                        settings.quarkCookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
                    },
                )
                // 夸克直链会 302 到 CDN，跨协议时也要把请求头带过去
                .setAllowCrossProtocolRedirects(true)
            // 本地 content:// 仍走系统数据源，只有 http(s) 才套网盘请求头 / 缓存
            val local = DefaultDataSource.Factory(this@PlaybackService, http).createDataSource()
            val remoteFactory = object : DataSource.Factory {
                override fun createDataSource(): DataSource = QuarkParallelDataSource(this@PlaybackService)
            }
            val remote = if (settings.quarkCachePlayback) {
                val mediaCache = QuarkMediaCache.get(this@PlaybackService)
                // 只 setCache 不会写入缓存：必须显式给 CacheDataSink，
                // 否则每次 seek 都重新走网盘 Range，逐帧一等就是好几秒。
                // 分片提交粒度压到 256KB，暂停时逐帧走一小段也能尽快落盘。
                val cacheWriteSinkFactory = CacheDataSink.Factory()
                    .setCache(mediaCache)
                    .setFragmentSize(256L * 1024)
                CacheDataSource.Factory()
                    .setCache(mediaCache)
                    .setUpstreamDataSourceFactory(remoteFactory)
                    .setCacheWriteDataSinkFactory(cacheWriteSinkFactory)
                    .createDataSource()
            } else {
                remoteFactory.createDataSource()
            }
            return SchemeSwitchDataSource(local, remote)
        }
    }

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(SettingsStore(this).decoderFallback)

        // 逐帧/小幅拖动会 seek 到当前关键帧重新解码；默认 back buffer 是 0，
        // 关键帧一丢就得重新走网盘 Range 下载，表现出来就是点一下等好几秒。
        // 保留 30 秒已解码前的压缩数据（从关键帧开始保留），±1 帧就能直接从内存重解码。
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                10_000,   // 最少缓冲
                180_000,  // 最多缓冲：暂停时也尽量把后面预取满
                1_500,    // 起播所需
                3_000,    // 卡顿后恢复所需
            )
            .setBackBuffer(30_000, true)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        val dataSourceFactory = mediaDataSourceFactory()
        val progressiveFactory = ProgressiveMediaSource.Factory(dataSourceFactory)
        val hlsFactory = HlsMediaSource.Factory(dataSourceFactory)
        // 网盘「流播」拿到的是 m3u8，其余仍是普通文件；这里按后缀分派
        val mediaSourceFactory = object : MediaSource.Factory {
            override fun setDrmSessionManagerProvider(
                provider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider,
            ): MediaSource.Factory {
                progressiveFactory.setDrmSessionManagerProvider(provider)
                hlsFactory.setDrmSessionManagerProvider(provider)
                return this
            }

            override fun setLoadErrorHandlingPolicy(
                policy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy,
            ): MediaSource.Factory {
                progressiveFactory.setLoadErrorHandlingPolicy(policy)
                hlsFactory.setLoadErrorHandlingPolicy(policy)
                return this
            }

            override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)

            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                val uri = mediaItem.localConfiguration?.uri?.toString().orEmpty()
                return if (uri.contains(".m3u8")) {
                    hlsFactory.createMediaSource(mediaItem)
                } else {
                    progressiveFactory.createMediaSource(mediaItem)
                }
            }
        }

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.i(TAG, "playbackState=$playbackState")
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                Log.i(TAG, "videoSize=${videoSize.width}x${videoSize.height}")
            }

            override fun onRenderedFirstFrame() {
                Log.i(TAG, "firstFrameRendered")
            }

            override fun onTracksChanged(tracks: Tracks) {
                tracks.groups.forEach { group ->
                    if (group.type == C.TRACK_TYPE_VIDEO) {
                        for (index in 0 until group.length) {
                            val format = group.getTrackFormat(index)
                            Log.i(TAG, "videoTrack codecs=${format.codecs} mime=${format.sampleMimeType} ${format.width}x${format.height}")
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "playerError=${error.errorCodeName}", error)
            }
        })

        val sessionIntent = Intent(this, PlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 ||
            player.playbackState == androidx.media3.common.Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}

/** 网盘播放缓存：单个进程内只能有一个 SimpleCache 实例。 */
object QuarkMediaCache {
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024

    @Volatile
    private var instance: SimpleCache? = null

    fun get(context: Context): SimpleCache = instance ?: synchronized(this) {
        instance ?: SimpleCache(
            File(context.cacheDir, "quark_media"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { instance = it }
    }
}

private const val QUARK_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 " +
        "Safari/537.36 Channel/pckk_other_ch"

/** 按 URI scheme 分流：content/file 走本地数据源，http(s) 走网盘数据源。 */
private class SchemeSwitchDataSource(
    private val local: DataSource,
    private val remote: DataSource,
) : DataSource {

    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        local.addTransferListener(transferListener)
        remote.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme?.lowercase()
        val target = if (scheme == "http" || scheme == "https") remote else local
        active = target
        return target.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        requireNotNull(active) { "DataSource 未打开" }.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun close() {
        runCatching { active?.close() }
        active = null
    }
}

/**
 * 网盘直链数据源：CDN 对 UA / Referer / Cookie 的组合很敏感，不同文件命中的策略不一样。
 * 这里按「客户端 UA + 完整头 → 仅 Cookie → 浏览器 UA → 裸请求」依次重试，
 * 只有拿到 4xx/5xx 才换下一组，纯网络错误直接抛出，避免无谓等待。
 */
private class QuarkRemoteDataSource(private val context: Context) : DataSource {

    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) = Unit

    override fun open(dataSpec: DataSpec): Long {
        val cookie = SettingsStore(context).quarkCookie
        val variants = buildList {
            add(QUARK_UA to buildMap<String, String> {
                put("Referer", "https://pan.quark.cn/")
                cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            })
            add(QUARK_UA to buildMap<String, String> {
                cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            })
            add(BROWSER_UA to buildMap<String, String> {
                put("Referer", "https://pan.quark.cn/")
                cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            })
            add(QUARK_UA to emptyMap())
        }
        var lastError: IOException? = null
        for ((ua, headers) in variants) {
            val ds = DefaultHttpDataSource.Factory()
                .setUserAgent(ua)
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true)
                .createDataSource()
            try {
                val length = ds.open(dataSpec)
                active = ds
                Log.i("GlassPlayer", "quark ds ok headers=${headers.keys}")
                DebugLog.record("cdn", "open ok headers=${headers.keys}")
                return length
            } catch (e: IOException) {
                lastError = e
                runCatching { ds.close() }
                val status = (e as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                Log.i("GlassPlayer", "quark ds fail status=$status headers=${headers.keys}")
                DebugLog.record("cdn", "open fail status=$status headers=${headers.keys}")
                if (status == null) break
            }
        }
        throw lastError ?: IOException("网盘直链连接失败")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        requireNotNull(active) { "数据源未打开" }.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun close() {
        runCatching { active?.close() }
        active = null
    }
}

private const val BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/94.0.4606.71 Safari/537.36 Core/1.94.225.400 QQBrowser/12.2.5544.400"

/**
 * 网盘下载直链的单连接速度被压得很低（实测 ~100KB/s），大文件根本缓冲不完。
 * 这里把文件切成 512KB 分片，用 8 条连接并行 Range 拉取，读侧仍按顺序交付，
 * 相当于一个内置的「多线程下载器」。服务端不支持 Range 时自动退回单连接。
 */
private class QuarkParallelDataSource(private val context: Context) : DataSource {

    private val sequential = QuarkRemoteDataSource(context)
    private var useSequential = false

    private var uri: Uri? = null
    @Volatile private var totalLength = -1L
    @Volatile private var readPosition = 0L
    private var requestLength = -1L
    @Volatile private var closed = false

    private val lock = Object()
    private val chunkMap = java.util.TreeMap<Long, ChunkBuffer>()
    // 本次 open 的起始位置：首片从该位置开始拉，而不是从 chunk 边界开始。
    // 否则 seek 到分片中间时，要先把目标点之前的几十~几百 KB 下完才能读到第一字节。
    private var openPosition = 0L
    private var openChunkIndex = 0L
    private val failedChunks = mutableSetOf<Long>()
    private var nextChunk = 0L
    private var executor: java.util.concurrent.ExecutorService? = null

    private var variantUa = QUARK_UA
    private var variantHeaders: Map<String, String> = emptyMap()

    // 每次 open/close 递增；旧 worker 发现代数变了就退出，避免快速拖动时线程越积越多
    @Volatile private var generation = 0L

    override fun addTransferListener(transferListener: TransferListener) = Unit

    override fun open(dataSpec: DataSpec): Long {
        synchronized(lock) { resetLocked() }
        useSequential = false
        return try {
            openParallel(dataSpec)
        } catch (e: Exception) {
            DebugLog.record("cdn", "parallel 不可用，退回单连接：${e.message}")
            synchronized(lock) { resetLocked() }
            useSequential = true
            sequential.open(dataSpec)
        }
    }

    private fun openParallel(dataSpec: DataSpec): Long {
        val target = dataSpec.uri.toString()
        // 同一个直链每次 seek 都会重新 open，之前每次都要探测 1~3 次 Range 0-0，
        // 逐帧时这几百毫秒会被放大成「点了没反应」。探测结果按 URL 复用。
        val cachedProbe = probeCache[target]
        var total = -1L
        var chosen: Pair<String, Map<String, String>>? = null
        var lastError: Exception? = null
        if (cachedProbe != null) {
            total = cachedProbe.total
            variantUa = cachedProbe.ua
            variantHeaders = cachedProbe.headers
            chosen = cachedProbe.ua to cachedProbe.headers
        } else {
            val variants = buildList {
                add(QUARK_UA to buildMap<String, String> {
                    put("Referer", "https://pan.quark.cn/")
                    SettingsStore(context).quarkCookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
                })
                add(QUARK_UA to buildMap<String, String> {
                    SettingsStore(context).quarkCookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
                })
                add(BROWSER_UA to buildMap<String, String> {
                    put("Referer", "https://pan.quark.cn/")
                    SettingsStore(context).quarkCookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
                })
            }
            for (variant in variants) {
                try {
                    val conn = openConnection(target, variant.first, variant.second, "0", "0")
                    val code = conn.responseCode
                    if (code == 206) {
                        total = parseTotal(conn.getHeaderField("Content-Range")) ?: -1L
                        conn.disconnect()
                        if (total > 0) {
                            chosen = variant
                            break
                        }
                    } else {
                        conn.disconnect()
                        DebugLog.record("cdn", "probe status=$code headers=${variant.second.keys}")
                    }
                } catch (e: Exception) {
                    lastError = e
                }
            }
        }
        val pick = chosen ?: throw (lastError ?: IOException("网盘不支持分片读取"))
        variantUa = pick.first
        variantHeaders = pick.second
        if (cachedProbe == null) {
            if (probeCache.size > 16) probeCache.clear()
            probeCache[target] = ProbeResult(total, variantUa, variantHeaders)
        }

        // 小文件不值得并行，单连接足够
        if (total <= 4L * 1024 * 1024) throw IOException("文件较小，走单连接")

        uri = dataSpec.uri
        totalLength = total
        readPosition = dataSpec.position.coerceAtLeast(0L)
        openPosition = readPosition
        openChunkIndex = readPosition / CHUNK_SIZE
        requestLength = dataSpec.length
        val remaining = total - readPosition
        // 有些 seek 会落在文件末尾，此时不该再开线程拉分片
        if (remaining <= 0) return 0
        DebugLog.record("cdn", "parallel open total=${total / 1024 / 1024}MB pos=$readPosition ua=${variantHeaders.keys}")

        val pool = java.util.concurrent.Executors.newFixedThreadPool(WORKERS) { runnable ->
            Thread(runnable, "quark-range").apply { isDaemon = true }
        }
        executor = pool
        nextChunk = readPosition / CHUNK_SIZE
        val myGeneration = generation
        repeat(WORKERS) { pool.execute { workerLoop(target, myGeneration) } }
        return if (requestLength > 0) minOf(requestLength, remaining) else remaining
    }

    private fun workerLoop(target: String, myGeneration: Long) {
        val lastChunk = (totalLength + CHUNK_SIZE - 1) / CHUNK_SIZE - 1
        while (true) {
            if (closed || generation != myGeneration) return
            var index = -1L
            synchronized(lock) {
                while (!closed && generation == myGeneration) {
                    val readChunk = readPosition / CHUNK_SIZE
                    if (nextChunk > lastChunk) return
                    if (nextChunk > readChunk + MAX_AHEAD) {
                        try {
                            lock.wait(200)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return
                        }
                        continue
                    }
                    index = nextChunk
                    nextChunk++
                    break
                }
            }
            if (index < 0) return
            if (closed || generation != myGeneration) return

            // 首片从本次 open 的精确位置起拉：seek/逐帧后的第一字节不用等前面的数据
            val start = if (index == openChunkIndex) openPosition else index * CHUNK_SIZE
            val end = minOf((index + 1) * CHUNK_SIZE - 1, totalLength - 1)
            val size = (end - start + 1).toInt()
            val chunk = ChunkBuffer(start, size)
            synchronized(lock) {
                if (closed || generation != myGeneration) return
                chunkMap[index] = chunk
                lock.notifyAll()
            }
            var ok = false
            // 拖动后 CDN 偶发 502/超时，给每个分片一次重试机会
            var attempt = 0
            while (attempt < 2 && !ok && !closed && generation == myGeneration) {
                attempt++
                try {
                    val conn = openConnection(target, variantUa, variantHeaders, start.toString(), end.toString())
                    if (conn.responseCode != 206) {
                        if (attempt == 2) DebugLog.record("cdn", "chunk $index status=${conn.responseCode}")
                        conn.disconnect()
                        continue
                    }
                    synchronized(lock) {
                        chunk.filled = 0
                        lock.notifyAll()
                    }
                    conn.inputStream.use { input ->
                        var filled = 0
                        // 边下边交付：读到多少就让 read() 取多少，不再等整片 512KB
                        while (filled < size) {
                            val n = input.read(chunk.data, filled, size - filled)
                            if (n <= 0) break
                            filled += n
                            synchronized(lock) {
                                chunk.filled = filled
                                lock.notifyAll()
                            }
                        }
                        ok = filled == size
                    }
                } catch (e: Exception) {
                    // 超时/断流：重试一次，仍失败就标记该分片
                }
            }
            if (closed || generation != myGeneration) return
            synchronized(lock) {
                chunk.done = true
                if (!ok) {
                    chunk.failed = true
                    failedChunks.add(index)
                } else {
                    // 只保留读指针附近的分片，避免把整个文件塞进内存
                    val keepFrom = (readPosition / CHUNK_SIZE) - 1
                    chunkMap.headMap(keepFrom).keys.toList().forEach { chunkMap.remove(it) }
                }
                lock.notifyAll()
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (useSequential) return sequential.read(buffer, offset, length)
        if (readPosition >= totalLength) return -1
        val index = readPosition / CHUNK_SIZE
        var waitedMs = 0
        synchronized(lock) {
            val chunk: ChunkBuffer
            while (true) {
                val current = chunkMap[index]
                if (current != null && current.filled > 0) {
                    chunk = current
                    break
                }
                if (current != null && current.failed) throw IOException("分片 $index 下载失败")
                if (index in failedChunks) throw IOException("分片 $index 下载失败")
                if (closed) throw IOException("数据源已关闭")
                // 长时间等不到分片（CDN 挂住/连接被掐）就抛错，让 ExoPlayer 走重试，而不是卡死
                if (waitedMs > 30_000) throw IOException("分片 $index 超时")
                try {
                    lock.wait(200)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("数据源已关闭", e)
                }
                waitedMs += 200
            }
            // 首片可能不是从 chunk 边界开始，偏移必须以该片自己的 start 为准
            val inChunk = (readPosition - chunk.start).toInt()
            val available = chunk.filled - inChunk
            if (available <= 0) {
                // 数据还没到目标偏移：继续等，不能跳过（跳了会丢字节）
                return readLockedWait(buffer, offset, length, index, waitedMs)
            }
            val n = minOf(length, available)
            System.arraycopy(chunk.data, inChunk, buffer, offset, n)
            readPosition += n
            return n
        }
    }

    /** 已拿到分片但目标偏移还没下载到：在锁内继续等，直到可读或超时。 */
    private fun readLockedWait(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        index: Long,
        waitedAlready: Int,
    ): Int {
        var waitedMs = waitedAlready
        while (true) {
            val current = chunkMap[index] ?: throw IOException("分片 $index 被回收")
            val inChunk = (readPosition - current.start).toInt()
            val available = current.filled - inChunk
            if (available > 0) {
                val n = minOf(length, available)
                System.arraycopy(current.data, inChunk, buffer, offset, n)
                readPosition += n
                return n
            }
            if (current.failed) throw IOException("分片 $index 下载失败")
            if (closed) throw IOException("数据源已关闭")
            if (waitedMs > 30_000) throw IOException("分片 $index 超时")
            try {
                lock.wait(200)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("数据源已关闭", e)
            }
            waitedMs += 200
        }
    }

    private class ChunkBuffer(val start: Long, val size: Int) {
        val data = ByteArray(size)
        var filled = 0
        var done = false
        var failed = false
    }

    override fun getUri(): Uri? = if (useSequential) sequential.uri else uri

    override fun close() {
        runCatching { sequential.close() }
        synchronized(lock) {
            generation++
            closed = true
            lock.notifyAll()
        }
        executor?.shutdownNow()
        executor = null
    }

    private fun resetLocked() {
        generation++
        closed = false
        chunkMap.clear()
        failedChunks.clear()
        totalLength = -1
        readPosition = 0
        requestLength = -1
        nextChunk = 0
        executor?.shutdownNow()
        executor = null
    }

    private fun openConnection(
        target: String,
        ua: String,
        headers: Map<String, String>,
        rangeStart: String,
        rangeEnd: String,
    ): HttpURLConnection {
        val conn = URL(target).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", ua)
        conn.setRequestProperty("Range", "bytes=$rangeStart-$rangeEnd")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return conn
    }

    private fun parseTotal(contentRange: String?): Long? {
        val slash = contentRange?.substringAfterLast('/') ?: return null
        return slash.toLongOrNull()
    }

    private data class ProbeResult(
        val total: Long,
        val ua: String,
        val headers: Map<String, String>,
    )

    private companion object {
        // 128KB × 8 连接：seek 后的目标通常落在前几片里，几十~一百多 KB 就能换帧；
        // 512KB 一片时单连接要拉满 0.5MB，逐帧会明显滞后。
        const val CHUNK_SIZE = 128L * 1024
        const val WORKERS = 8
        // 64 片 ≈ 8MB，和原来 16×512KB 的内存上限保持一致
        const val MAX_AHEAD = 64L
        // 直链过期后 URL 也会换，按 URL 缓存不会读旧票据
        val probeCache = java.util.concurrent.ConcurrentHashMap<String, ProbeResult>()
    }
}

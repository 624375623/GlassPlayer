package com.glassplayer.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Thumbnails come from MediaStore first, with a retriever fallback for exotic containers. */
object ThumbnailLoader {

    private val cache = object : LruCache<String, Bitmap>(
        24 * 1024 * 1024,
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    suspend fun load(context: Context, item: VideoItem, width: Int = 480): Bitmap? =
        withContext(Dispatchers.IO) {
            cache.get(item.uri)?.let { return@withContext it }
            // ContentResolver.loadThumbnail 是 API 29 才有的；Android 9 直接走
            // MediaMetadataRetriever，首帧解码虽然慢一点，但不会在查询时抛 NoSuchMethod。
            val storeThumb = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                runCatching {
                    context.contentResolver.loadThumbnail(
                        item.uriValue,
                        Size(width, (width * 9 / 16).coerceAtLeast(1)),
                        null,
                    )
                }.getOrNull()
            } else {
                null
            }
            val bitmap = storeThumb ?: runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, item.uriValue)
                    retriever.getFrameAtTime(1_000_000L)
                } finally {
                    retriever.release()
                }
            }.getOrNull()
            bitmap?.also { cache.put(item.uri, it) }
        }
}

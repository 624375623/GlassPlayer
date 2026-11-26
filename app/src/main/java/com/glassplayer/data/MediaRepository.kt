package com.glassplayer.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the device video library through MediaStore. */
class MediaRepository(private val context: Context) {

    private val legacyStorage = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    suspend fun loadVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            // RELATIVE_PATH 是 API 29 才有的列；Android 9 查询它会直接报未知列。
            // API 28 用 DATA 绝对路径推导目录，API 29+ 仍用 RELATIVE_PATH。
            if (legacyStorage) {
                MediaStore.Video.Media.DATA
            } else {
                MediaStore.Video.Media.RELATIVE_PATH
            },
            MediaStore.Video.Media.MIME_TYPE,
        )

        val items = ArrayList<VideoItem>()
        val cursor = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )

        cursor?.use { c ->
            val idIndex = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameIndex = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationIndex = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeIndex = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateIndex = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val widthIndex = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightIndex = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val bucketIndex = c.getColumnIndex(MediaStore.Video.Media.BUCKET_ID)
            val bucketNameIndex = c.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val pathIndex = c.getColumnIndex(
                if (legacyStorage) MediaStore.Video.Media.DATA
                else MediaStore.Video.Media.RELATIVE_PATH,
            )
            val mimeIndex = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)

            while (c.moveToNext()) {
                val id = c.getLong(idIndex)
                val displayName = c.getString(nameIndex) ?: "未命名视频"
                val bucketName = bucketNameIndex.takeIf { it >= 0 }
                    ?.let { c.getString(it) }
                    ?.takeIf { it.isNotBlank() }
                    ?: "其他"
                val rawPath = pathIndex.takeIf { it >= 0 }?.let { c.getString(it) }.orEmpty()
                val relative = if (legacyStorage) {
                    legacyRelativePath(rawPath)
                } else {
                    rawPath.trimEnd('/')
                }
                val folder = relative.ifBlank { bucketName }
                val folderKey = bucketIndex.takeIf { it >= 0 }
                    ?.let { c.getLong(it) }
                    ?: folder.hashCode().toLong()
                items += VideoItem(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id).toString(),
                    title = displayName.substringBeforeLast('.'),
                    durationMs = c.getLong(durationIndex),
                    sizeBytes = c.getLong(sizeIndex),
                    dateAddedSec = c.getLong(dateIndex),
                    width = c.getInt(widthIndex),
                    height = c.getInt(heightIndex),
                    bucketId = folderKey,
                    folderName = folder,
                    relativePath = relative,
                    mimeType = c.getString(mimeIndex) ?: "video/*",
                )
            }
        }
        items
    }

    /**
     * Android 9 没有 RELATIVE_PATH。把 MediaStore.DATA 的绝对路径裁成
     * 「DCIM/Camera」这种相对目录，详情页和文件夹分组都能继续用。
     */
    private fun legacyRelativePath(absolute: String): String {
        if (absolute.isBlank()) return ""
        val path = absolute.trim()
        val markers = listOf(
            "/storage/emulated/0/",
            "/storage/self/primary/",
            "/emulated/0/",
            "/sdcard/",
        )
        val relative = markers.firstOrNull { path.contains(it) }
            ?.let { path.substringAfter(it) }
            ?: path.trimStart('/')
        return relative.substringBeforeLast('/', "")
    }

    fun foldersOf(videos: List<VideoItem>): List<VideoFolder> = videos
        .groupBy { it.bucketId }
        .map { (bucketId, group) ->
            VideoFolder(
                bucketId = bucketId,
                name = group.first().folderName,
                count = group.size,
                newest = group.first(),
            )
        }
        .sortedByDescending { it.newest.dateAddedSec }

    fun formatOf(item: VideoItem): String {
        val ext = item.mimeType.substringAfter('/', "").ifBlank { "video" }
        return ext.uppercase()
    }
}

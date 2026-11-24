package com.glassplayer.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the device video library through MediaStore. */
class MediaRepository(private val context: Context) {

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
            MediaStore.Video.Media.RELATIVE_PATH,
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
            val bucketIndex = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
            val bucketNameIndex = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val pathIndex = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val mimeIndex = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)

            while (c.moveToNext()) {
                val id = c.getLong(idIndex)
                val displayName = c.getString(nameIndex) ?: "未命名视频"
                val relative = c.getString(pathIndex)?.trimEnd('/') ?: ""
                val folder = relative.ifBlank {
                    c.getString(bucketNameIndex) ?: "其他"
                }
                items += VideoItem(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id).toString(),
                    title = displayName.substringBeforeLast('.'),
                    durationMs = c.getLong(durationIndex),
                    sizeBytes = c.getLong(sizeIndex),
                    dateAddedSec = c.getLong(dateIndex),
                    width = c.getInt(widthIndex),
                    height = c.getInt(heightIndex),
                    bucketId = c.getLong(bucketIndex),
                    folderName = folder,
                    relativePath = c.getString(pathIndex) ?: "",
                    mimeType = c.getString(mimeIndex) ?: "video/*",
                )
            }
        }
        items
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

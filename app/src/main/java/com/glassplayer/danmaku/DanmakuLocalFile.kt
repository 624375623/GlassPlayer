package com.glassplayer.danmaku

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * 同目录同名弹幕自动加载。
 *
 * 视频 `某某.第05集.mp4` 旁边放一份 `某某.第05集.xml`（B 站格式）
 * 或 `.json`（弹弹play / B 站 JSON 格式），进播放页就能直接用，
 * 完全不走网络。这条是照搬弹弹play「autoLoadSameNameDanmu」的做法，
 * 也是断网时唯一还能有弹幕的路径。
 */
object DanmakuLocalFile {

    private val supportedExt = setOf("xml", "json")

    fun load(context: Context, videoUri: String): Pair<String, List<Danmaku>>? = runCatching {
        val parsed = Uri.parse(videoUri)
        val videoProjection = arrayOf(
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DISPLAY_NAME,
        )
        var relativePath = ""
        var displayName: String? = null
        context.contentResolver.query(parsed, videoProjection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                relativePath = cursor.getString(0) ?: ""
                displayName = cursor.getString(1)
            }
        }
        val base = displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: return@runCatching null

        val filesUri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
        )
        val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH}=? AND " +
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf(relativePath, "$base.%")

        var result: Pair<String, List<Danmaku>>? = null
        context.contentResolver.query(filesUri, projection, selection, args, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            while (cursor.moveToNext() && result == null) {
                val fileName = cursor.getString(nameIndex) ?: continue
                val ext = fileName.substringAfterLast('.', "").lowercase()
                if (ext !in supportedExt) continue
                val fileUri = ContentUris.withAppendedId(filesUri, cursor.getLong(idIndex))
                val text = context.contentResolver.openInputStream(fileUri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: continue
                val items = DanmakuParser.parse(text)
                if (items.isNotEmpty()) {
                    result = "本地弹幕 · $fileName" to items
                }
            }
        }
        result
    }.getOrNull()
}

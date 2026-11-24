package com.glassplayer.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaCodecList
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 应用内自检：设备信息、媒体库规模、Camera 目录探针、可用视频解码器、上次崩溃日志。
 * 真机上打不开某些目录时，先看这里的“Camera 探针”结果即可区分权限问题还是解码问题。
 */
object Diagnostics {

    suspend fun run(context: Context): String = withContext(Dispatchers.IO) {
        buildString {
            appendLine("== GlassPlayer 自检 ==")
            appendLine(
                "设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / " +
                    "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
            )
            appendLine()

            appendLine("[媒体库]")
            var total = 0
            var cameraUri: String? = null
            val bucketCounts = LinkedHashMap<String, Int>()
            runCatching {
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                        MediaStore.Video.Media.RELATIVE_PATH,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        total += 1
                        val bucket = cursor.getString(1) ?: "未知"
                        bucketCounts[bucket] = (bucketCounts[bucket] ?: 0) + 1
                        val path = cursor.getString(2).orEmpty()
                        if (cameraUri == null &&
                            (bucket.equals("Camera", true) || path.contains("DCIM/Camera"))
                        ) {
                            cameraUri = ContentUris.withAppendedId(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                cursor.getLong(0),
                            ).toString()
                        }
                    }
                }
            }.onFailure { appendLine("查询失败: ${it.javaClass.simpleName}: ${it.message}") }

            appendLine("视频总数: $total")
            bucketCounts.entries.sortedByDescending { it.value }.take(6).forEach { (name, count) ->
                appendLine("  $name: $count")
            }
            appendLine()

            appendLine("[Camera 目录探针]")
            val probe = cameraUri
            if (probe == null) {
                appendLine("未在媒体库中找到 Camera 视频")
            } else {
                appendLine("URI: $probe")
                val started = System.currentTimeMillis()
                runCatching {
                    context.contentResolver.openFileDescriptor(Uri.parse(probe), "r")?.use { pfd ->
                        appendLine(
                            "打开成功，size=${pfd.statSize}B，耗时=${System.currentTimeMillis() - started}ms",
                        )
                    } ?: appendLine("打开返回 null（系统拒绝或文件不可读）")
                }.onFailure {
                    appendLine("打开失败: ${it.javaClass.simpleName}: ${it.message}")
                }
            }
            appendLine()

            appendLine("[视频解码器]")
            runCatching {
                val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                listOf("video/avc", "video/hevc", "video/av01", "video/x-vnd.on2.vp9").forEach { type ->
                    val names = codecList.codecInfos
                        .filter { !it.isEncoder && it.supportedTypes.contains(type) }
                        .map { it.name }
                    appendLine("$type -> ${if (names.isEmpty()) "无" else names.joinToString()}")
                }
            }.onFailure { appendLine("枚举失败: ${it.message}") }
            appendLine()

            val logDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
            appendLine("[上次崩溃]")
            val crashFile = File(logDir, "last_crash.txt")
            if (crashFile.exists()) {
                append(crashFile.readText().take(4000))
            } else {
                appendLine("无记录")
            }

            appendLine()
            appendLine("[崩溃历史 · 最近 ${com.glassplayer.GlassPlayerApp.HISTORY_LIMIT} 条]")
            val historyFile = File(logDir, "crash_history.txt")
            if (historyFile.exists()) {
                val entries = historyFile.readText()
                    .split(com.glassplayer.GlassPlayerApp.SEPARATOR)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                if (entries.isEmpty()) {
                    appendLine("无记录")
                } else {
                    var budget = 5000
                    entries.forEachIndexed { index, entry ->
                        if (budget <= 0) return@forEachIndexed
                        appendLine("#${index + 1}${if (index == 0) "（最新）" else ""}")
                        val text = entry.take(budget)
                        append(text.trim())
                        appendLine()
                        budget -= text.length
                    }
                }
            } else {
                appendLine("无记录")
            }

            appendLine()
            appendLine("[运行日志]")
            appendLine(DebugLog.snapshot())
        }
    }
}

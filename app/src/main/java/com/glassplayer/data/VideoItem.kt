package com.glassplayer.data

import android.net.Uri

/** A single local video discovered through MediaStore. */
data class VideoItem(
    val id: Long,
    val uri: String,
    val title: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val width: Int,
    val height: Int,
    val bucketId: Long,
    val folderName: String,
    val relativePath: String,
    val mimeType: String,
    /** 网盘文件夹条目：点击它应该进入对应目录，而不是播放。 */
    val cloudDir: Boolean = false,
) {
    val uriValue: Uri get() = Uri.parse(uri)

    val resolutionText: String
        get() = when {
            width <= 0 || height <= 0 -> "未知分辨率"
            else -> "${width} × ${height}"
        }

    /** 列表里展示的简短格式标签，例如 MP4 / MKV / WEBM。 */
    val formatLabel: String
        get() = mimeType.substringAfter('/', "").uppercase().ifBlank { "视频" }

    val aspectRatio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 16f / 9f
}

data class VideoFolder(
    val bucketId: Long,
    val name: String,
    val count: Int,
    val newest: VideoItem,
)

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

fun formatAgo(timestampMs: Long): String {
    if (timestampMs <= 0) return ""
    val diff = System.currentTimeMillis() - timestampMs
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)} 天前"
        else -> "${minutes / (60 * 24 * 30)} 个月前"
    }
}

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "未知大小"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024f)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
    else -> "%.2f GB".format(bytes / (1024f * 1024f * 1024f))
}

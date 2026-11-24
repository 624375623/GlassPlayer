package com.glassplayer.danmaku

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/**
 * 弹幕本地缓存。
 *
 * 关键改动（v3.8）：缓存键从「URI 字符串哈希」换成「内容指纹」——
 * 读文件前 16MB 算 MD5，再拼上文件大小。这样视频改名、移动目录，
 * 甚至同一份文件换个位置，都还能命中同一份缓存，和弹弹play 的
 * hashOnly 匹配是同一个思路，只是用在了我们自己的缓存层。
 *
 * 只存渲染必需的四项（时间 / 模式 / 颜色 / 文本），一行一条，约 300KB / 万条。
 * 只保留最近 8 集。
 */
object DanmakuCache {

    private const val MAX_FILES = 8
    private const val MAGIC = "gp-danmaku-v1"

    /** 计算指纹采样长度：前 16MB 足够区分绝大多数视频。 */
    private const val SAMPLE_BYTES = 16L * 1024 * 1024
    private const val BUFFER_BYTES = 256 * 1024

    private fun dir(context: Context): File =
        File(context.filesDir, "danmaku").apply { if (!exists()) mkdirs() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("glass_player_prefs", Context.MODE_PRIVATE)

    /**
     * 内容指纹：`md5(前16MB)` 前 24 位 + 文件大小。
     *
     * 同一台设备上，uri → 指纹的映射会写进 prefs，第二次打开同一路径
     * 不再重算 MD5（16MB 读盘在手机上要一两百毫秒）。
     */
    fun fingerprint(context: Context, uri: String): String? {
        if (uri.isBlank()) return null
        val key = "dmfp_" + Integer.toHexString(uri.hashCode()) + "_" + uri.length
        prefs(context).getString(key, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val value = computeFingerprint(context, uri) ?: return null
        prefs(context).edit().putString(key, value).apply()
        return value
    }

    private fun computeFingerprint(context: Context, uri: String): String? = runCatching {
        val parsed = Uri.parse(uri)
        var size = 0L
        context.contentResolver.openFileDescriptor(parsed, "r")?.use { size = it.statSize }
        val digest = MessageDigest.getInstance("MD5")
        context.contentResolver.openInputStream(parsed)?.use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            var total = 0L
            while (total < SAMPLE_BYTES) {
                val target = minOf(buffer.size.toLong(), SAMPLE_BYTES - total).toInt()
                val read = input.read(buffer, 0, target)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                total += read
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }.take(24)
        "$hex-$size"
    }.getOrNull()

    private fun fileFor(context: Context, uri: String, fingerprint: String?): File {
        val name = if (fingerprint.isNullOrBlank()) {
            // 指纹算不出来（比如权限/流式来源）才退回 URI 哈希
            "${Integer.toHexString(uri.hashCode())}_${uri.length}.tsv"
        } else {
            "$fingerprint.tsv"
        }
        return File(dir(context), name)
    }

    fun save(context: Context, uri: String, label: String, items: List<Danmaku>) {
        if (uri.isBlank() || items.isEmpty()) return
        runCatching {
            val fp = fingerprint(context, uri)
            val file = fileFor(context, uri, fp)
            file.bufferedWriter().use { writer ->
                writer.appendLine(MAGIC)
                writer.appendLine(label.replace('\n', ' ').replace('\t', ' '))
                items.forEach { item ->
                    val text = item.text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
                    writer.append(item.timeMs.toString()).append('\t')
                        .append(item.mode.ordinal.toString()).append('\t')
                        .append(item.color.toString()).append('\t')
                        .append(text).append('\n')
                }
            }
            prune(context, keep = file)
        }
    }

    fun load(context: Context, uri: String): Pair<String, List<Danmaku>>? {
        if (uri.isBlank()) return null
        val file = fileFor(context, uri, fingerprint(context, uri))
        if (!file.exists()) return null
        return runCatching {
            val lines = file.readLines()
            if (lines.size < 3 || lines.first() != MAGIC) return null
            val label = lines[1]
            val items = ArrayList<Danmaku>(lines.size)
            for (index in 2 until lines.size) {
                val parts = lines[index].split('\t', limit = 4)
                if (parts.size < 4) continue
                val time = parts[0].toLongOrNull() ?: continue
                val mode = DanmakuMode.values().getOrElse(parts[1].toIntOrNull() ?: 0) {
                    DanmakuMode.SCROLL
                }
                val color = parts[2].toIntOrNull() ?: 0xFFFFFF
                items.add(Danmaku(time, parts[3], mode, color))
            }
            // 触碰一下时间戳，配合 prune 的 LRU 语义
            file.setLastModified(System.currentTimeMillis())
            label to items
        }.getOrNull()
    }

    fun remove(context: Context, uri: String) {
        if (uri.isBlank()) return
        runCatching { fileFor(context, uri, fingerprint(context, uri)).delete() }
    }

    private fun prune(context: Context, keep: File) {
        val files = dir(context).listFiles()?.filter { it.isFile } ?: return
        if (files.size <= MAX_FILES) return
        files.filter { it.absolutePath != keep.absolutePath }
            .sortedBy { it.lastModified() }
            .take(files.size - MAX_FILES)
            .forEach { it.delete() }
    }
}

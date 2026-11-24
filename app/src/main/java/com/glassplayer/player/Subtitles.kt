package com.glassplayer.player

import android.content.Context
import android.net.Uri

data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

data class SubtitleTrack(val name: String, val cues: List<SubtitleCue>) {

    fun textAt(positionMs: Long): String? {
        if (cues.isEmpty()) return null
        var low = 0
        var high = cues.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cue = cues[mid]
            when {
                positionMs < cue.startMs -> high = mid - 1
                positionMs > cue.endMs -> low = mid + 1
                else -> return cue.text
            }
        }
        return null
    }
}

/** Reads external subtitle files: SRT / WebVTT / ASS / SSA. */
object SubtitleParser {

    private val timeLine = Regex(
        """[0-9]{1,2}:[0-9]{2}:[0-9]{2}[,.]?[0-9]{0,3}\s*-->\s*[0-9]{1,2}:[0-9]{2}:[0-9]{2}[,.]?[0-9]{0,3}""",
    )
    private val simpleTimeLine = Regex(
        """([0-9]{1,2}:)?[0-9]{1,2}:[0-9]{2}[,.]?[0-9]{0,3}\s*-->\s*([0-9]{1,2}:)?[0-9]{1,2}:[0-9]{2}[,.]?[0-9]{0,3}""",
    )
    // 两个花括号都转义：Android ICU 正则把未配对/未转义的 } 当作非法量词，
    // 只转义开头的 \{ 会在编译时抛 PatternSyntaxException，导致 object 初始化崩溃
    private val assTag = Regex("""\{[^}]*\}""")

    fun read(context: Context, uri: Uri): Pair<String, String>? {
        val displayName = queryName(context, uri) ?: "external.srt"
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        val content = decodeText(bytes)
        return displayName to content
    }

    /**
     * 字幕常见三种编码：UTF-8（可能带 BOM）、UTF-16（带 BOM）、GBK/GB18030。
     * 国内片源大量是 GBK，直接按 UTF-8 读会整篇乱码，这里按 BOM + 严格 UTF-8 试探再回落 GBK。
     */
    private fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        // UTF-8 BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        // UTF-16 BOM
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            }
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            }
        }
        // 严格 UTF-8：有非法字节会抛异常，说明不是 UTF-8
        val utf8 = runCatching {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        if (utf8 != null) return utf8
        // 回落到 GBK / GB18030
        return runCatching { String(bytes, charset("GB18030")) }
            .getOrElse { String(bytes, Charsets.ISO_8859_1) }
    }

    private fun queryName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else null
        }
    }.getOrNull()

    fun parse(fileName: String, raw: String): SubtitleTrack {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val lower = fileName.lowercase()
        val cues = when {
            lower.endsWith(".ass") || lower.endsWith(".ssa") -> parseAss(text)
            else -> parseTimedText(text)
        }
        return SubtitleTrack(fileName, cues.sortedBy { it.startMs })
    }

    private fun parseTimedText(text: String): List<SubtitleCue> {
        val lines = text.split('\n')
        val cues = ArrayList<SubtitleCue>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            val match = simpleTimeLine.find(line) ?: run {
                index++
                continue
            }
            val times = line.split("-->").map { it.trim() }
            if (times.size < 2) {
                index++
                continue
            }
            val start = parseTimestamp(times[0])
            val end = parseTimestamp(times[1].substringBefore(' ').trim())
            index++
            val builder = StringBuilder()
            while (index < lines.size && lines[index].isNotBlank()) {
                if (builder.isNotEmpty()) builder.append('\n')
                builder.append(lines[index].trim().replace(Regex("""<[^>]+>"""), ""))
                index++
            }
            if (start >= 0 && end > start) {
                cues += SubtitleCue(start, end, builder.toString())
            }
        }
        return cues
    }

    private fun parseAss(text: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        var inEvents = false
        var startField = 1
        var endField = 2
        var textField = 9
        text.split('\n').forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("[Events]") -> inEvents = true
                line.startsWith("Format:") && inEvents -> {
                    val fields = line.removePrefix("Format:").split(',').map { it.trim().lowercase() }
                    startField = fields.indexOf("start").coerceAtLeast(1)
                    endField = fields.indexOf("end").coerceAtLeast(2)
                    textField = fields.indexOf("text").coerceAtLeast(9)
                }
                inEvents && line.startsWith("Dialogue:") -> {
                    val parts = line.removePrefix("Dialogue:").split(',', limit = textField + 2)
                    if (parts.size > textField) {
                        val start = parseTimestamp(parts[startField])
                        val end = parseTimestamp(parts[endField])
                        val body = parts[textField]
                            .replace(assTag, "")
                            .replace("\\N", "\n")
                            .replace("\\n", "\n")
                            .trim()
                        if (start >= 0 && end > start && body.isNotEmpty()) {
                            cues += SubtitleCue(start, end, body)
                        }
                    }
                }
            }
        }
        return cues
    }

    private fun parseTimestamp(value: String): Long {
        val cleaned = value.trim().replace(',', '.')
        if (cleaned.isEmpty()) return -1
        val parts = cleaned.split(':')
        return try {
            when (parts.size) {
                3 -> {
                    val seconds = parts[2].toDouble()
                    (parts[0].toLong() * 3600 + parts[1].toLong() * 60) * 1000L + (seconds * 1000).toLong()
                }
                2 -> parts[0].toLong() * 60_000L + (parts[1].toDouble() * 1000).toLong()
                else -> (cleaned.toDouble() * 1000).toLong()
            }
        } catch (error: NumberFormatException) {
            -1
        }
    }
}

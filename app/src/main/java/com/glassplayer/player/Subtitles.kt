package com.glassplayer.player

import android.content.Context
import android.net.Uri

enum class SubtitleKind { DIALOGUE, LYRIC, SIGN, NOTE }

enum class SubtitleLanguage(val label: String) {
    CHINESE("中文"),
    JAPANESE("日语"),
    ENGLISH("英语"),
    OTHER("其他"),
}

/**
 * 一条 ASS / SRT / VTT 字幕事件。
 *
 * ASS 的双语字幕经常不是同一句话里用 \N 分行，而是同一时刻写两条 Dialogue：
 * 中文一条、日文一条，各自带样式和 MarginV。只保留 start/end/text 会丢掉
 * “同一时刻还有另一条”的信息，所以这里把样式、层级和垂直边距一起留下来。
 */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val style: String = "",
    val layer: Int = 0,
    val order: Int = 0,
    val fontSize: Float = 0f,
    val alignment: Int = 2,
    val marginV: Int = 0,
    val kind: SubtitleKind = SubtitleKind.DIALOGUE,
    val isDrawing: Boolean = false,
    val language: SubtitleLanguage = SubtitleLanguage.OTHER,
)

data class SubtitleLine(
    val text: String,
    val style: String = "",
    val fontSize: Float = 0f,
    val alignment: Int = 2,
    val language: SubtitleLanguage = SubtitleLanguage.OTHER,
)

data class SubtitleTrack(val name: String, val cues: List<SubtitleCue>) {

    private val maxDurationMs = cues.maxOfOrNull { (it.endMs - it.startMs).coerceAtLeast(0L) } ?: 0L

    /**
     * 文件里实际出现的字幕语言，用于字幕面板决定是否显示“全部 / 中文 / 日语”选择。
     * 有对白时只看对白，避免片头中文署名把日语单语片误判成双语。
     */
    val languages: List<SubtitleLanguage> = run {
        val dialogue = cues.filter {
            it.kind == SubtitleKind.DIALOGUE && !it.isDrawing && it.language != SubtitleLanguage.OTHER
        }
        val source = dialogue.ifEmpty {
            cues.filter {
                it.kind != SubtitleKind.NOTE && !it.isDrawing && it.language != SubtitleLanguage.OTHER
            }
        }
        source.map { it.language }.distinct().sortedBy { it.ordinal }
    }

    /**
     * 返回当前时刻所有应显示的字幕行，按画面上的从上到下排序。
     *
     * 旧实现用二分查找只返回第一条命中的 cue，同一时刻的中文 / 日文事件只能显示一条；
     * 这里先定位到当前位置，再回看所有仍在有效期内的重叠事件，合并成多行。
     *
     * [onlyLanguage] 非空时只保留该语言；选择“日语”不会再把中文行混进来。
     */
    fun linesAt(positionMs: Long, onlyLanguage: SubtitleLanguage? = null): List<SubtitleLine> {
        if (cues.isEmpty()) return emptyList()
        val anchor = firstIndexAfter(positionMs) - 1
        if (anchor < 0) return emptyList()

        val active = ArrayList<SubtitleCue>()
        val earliest = positionMs - maxDurationMs
        var index = anchor
        while (index >= 0 && cues[index].startMs >= earliest) {
            val cue = cues[index]
            if (positionMs >= cue.startMs && positionMs < cue.endMs && !cue.isDrawing) {
                active += cue
            }
            index--
        }
        val visible = if (onlyLanguage == null) {
            active
        } else {
            active.filter { it.language == onlyLanguage }
        }
        if (visible.isEmpty()) return emptyList()
        return selectLines(visible)
    }

    /** 兼容旧调用点：把同一时刻的多条活动字幕合成一个多行字符串。 */
    fun textAt(positionMs: Long, onlyLanguage: SubtitleLanguage? = null): String? {
        val lines = linesAt(positionMs, onlyLanguage)
        if (lines.isEmpty()) return null
        return lines.joinToString("\n") { it.text }
    }

    private fun firstIndexAfter(positionMs: Long): Int {
        var low = 0
        var high = cues.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startMs <= positionMs) low = mid + 1 else high = mid
        }
        return low
    }

    private fun selectLines(active: List<SubtitleCue>): List<SubtitleLine> {
        if (active.isEmpty()) return emptyList()

        // 有对白时优先显示对白；Sign / Note 之类的画面字不抢对白的位置。
        // 没有对白时才退回到歌词 / 画面文本，避免整段时间完全空白。
        val dialogue = active.filter { it.kind == SubtitleKind.DIALOGUE }
        val pool = if (dialogue.isNotEmpty()) {
            dialogue
        } else {
            active.filter { it.kind != SubtitleKind.NOTE }
        }
        if (pool.isEmpty()) return emptyList()

        // 同一 style + 同一文本的连续 / 逐帧事件只保留当前最新的一条，避免
        // OP / ED 的逐帧特效事件在同一时刻堆成几十行；同一样式但文本不同的
        // 重叠对白仍然各自保留，不会被后一条吞掉。
        val newestByStyleAndText = LinkedHashMap<String, SubtitleCue>()
        for (cue in pool) {
            val key = cue.style.trim().lowercase().ifBlank { "_default" } + "\u0000" + cue.text
            val current = newestByStyleAndText[key]
            if (current == null ||
                cue.startMs > current.startMs ||
                (cue.startMs == current.startMs && cue.order >= current.order)
            ) {
                newestByStyleAndText[key] = cue
            }
        }

        return newestByStyleAndText.values
            .sortedWith(
                compareBy<SubtitleCue> { verticalRank(it.alignment) }
                    .thenBy { if (it.alignment in 7..9) it.marginV else -it.marginV }
                    .thenBy { languageRank(it.language) }
                    .thenBy { it.order },
            )
            .map {
                SubtitleLine(
                    text = it.text,
                    style = it.style,
                    fontSize = it.fontSize,
                    alignment = it.alignment,
                    language = it.language,
                )
            }
            // 中英 / 中日文完全相同的歌词只留一行（例如 "Shall we dance"）
            .distinctBy { it.text }
            .take(MAX_LINES)
    }

    /** ASS 的 numpad 对齐：7~9 在顶部，4~6 居中，1~3 在底部。 */
    private fun verticalRank(alignment: Int): Int = when (alignment) {
        in 7..9 -> 0
        in 4..6 -> 1
        else -> 2
    }

    private fun languageRank(language: SubtitleLanguage): Int = when (language) {
        SubtitleLanguage.CHINESE -> 0
        SubtitleLanguage.JAPANESE -> 1
        SubtitleLanguage.ENGLISH -> 2
        SubtitleLanguage.OTHER -> 3
    }

    private companion object {
        // 手机竖屏最多同时显示四行；超过时优先保留最靠近底部的语言行。
        const val MAX_LINES = 4
    }
}

/** Reads external subtitle files: SRT / WebVTT / ASS / SSA. */
object SubtitleParser {

    private data class AssStyle(
        val fontSize: Float = 0f,
        val alignment: Int = 2,
        val marginV: Int = 0,
    )

    private val timeLine = Regex(
        """[0-9]{1,2}:[0-9]{2}:[0-9]{2}[,.]?[0-9]{0,3}\s*-->\s*[0-9]{1,2}:[0-9]{2}:[0-9]{2}[,.]?[0-9]{0,3}""",
    )
    private val simpleTimeLine = Regex(
        """([0-9]{1,2}:)?[0-9]{1,2}:[0-9]{2}[,.]?[0-9]{0,3}\s*-->\s*([0-9]{1,2}:)?[0-9]{1,2}:[0-9]{2}[,.]?[0-9]{0,3}""",
    )
    // 两个花括号都转义：Android ICU 正则把未配对/未转义的 } 当作非法量词，
    // 只转义开头的 \{ 会在编译时抛 PatternSyntaxException，导致 object 初始化崩溃
    private val assTag = Regex("""\{[^}]*\}""")
    private val assDrawingTag = Regex("""\\p(\d+)""")
    private val kanaScript = Regex("[\u3040-\u30FF\u31F0-\u31FF\uFF66-\uFF9D]")
    private val hanScript = Regex("[\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF]")
    private val latinScript = Regex("[A-Za-z]")
    private val styleTokenSplit = Regex("[^a-z]+")

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
        return SubtitleTrack(fileName, cues.sortedWith(compareBy({ it.startMs }, { it.order })))
    }

    private fun parseTimedText(text: String): List<SubtitleCue> {
        val lines = text.split('\n')
        val cues = ArrayList<SubtitleCue>()
        var index = 0
        var order = 0
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
                // SRT / VTT 的双语通常写在一个 cue 的多行里；拆成独立行后
                // 才能分别识别“中文行 / 日语行”，并按语言单独开关。
                builder.toString().split('\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { cueText ->
                        cues += SubtitleCue(
                            startMs = start,
                            endMs = end,
                            text = cueText,
                            order = order++,
                            language = detectLanguage("", cueText),
                        )
                    }
            }
        }
        return cues
    }

    private fun parseAss(text: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        val styles = HashMap<String, AssStyle>()
        var inStyles = false
        var inEvents = false
        var styleFormat = emptyList<String>()
        var startField = 1
        var endField = 2
        var styleField = 3
        var layerField = 0
        var marginVField = 7
        var textField = 9
        var order = 0

        text.split('\n').forEach { rawLine ->
            val line = rawLine.trim()
            if (line.startsWith("[")) {
                inStyles = line == "[V4+ Styles]" || line == "[V4 Styles]"
                inEvents = line == "[Events]"
                return@forEach
            }
            when {
                inStyles && line.startsWith("Format:") -> {
                    styleFormat = line.removePrefix("Format:")
                        .split(',')
                        .map { it.trim().lowercase() }
                }

                inStyles && line.startsWith("Style:") -> {
                    if (styleFormat.isEmpty()) return@forEach
                    val values = line.removePrefix("Style:").split(',')
                    val nameIndex = styleFormat.indexOf("name")
                    val name = values.getOrNull(nameIndex)?.trim().orEmpty()
                    if (name.isEmpty()) return@forEach
                    styles[name.lowercase()] = AssStyle(
                        fontSize = values.getOrNull(styleFormat.indexOf("fontsize"))
                            ?.trim()?.toFloatOrNull() ?: 0f,
                        alignment = values.getOrNull(styleFormat.indexOf("alignment"))
                            ?.trim()?.toIntOrNull() ?: 2,
                        marginV = values.getOrNull(styleFormat.indexOf("marginv"))
                            ?.trim()?.toIntOrNull() ?: 0,
                    )
                }

                inEvents && line.startsWith("Format:") -> {
                    val fields = line.removePrefix("Format:")
                        .split(',')
                        .map { it.trim().lowercase() }
                    startField = fields.indexOf("start").takeIf { it >= 0 } ?: 1
                    endField = fields.indexOf("end").takeIf { it >= 0 } ?: 2
                    styleField = fields.indexOf("style").takeIf { it >= 0 } ?: 3
                    layerField = fields.indexOf("layer").takeIf { it >= 0 } ?: 0
                    marginVField = fields.indexOf("marginv").takeIf { it >= 0 } ?: 7
                    textField = fields.indexOf("text").takeIf { it >= 0 } ?: 9
                }

                inEvents && line.startsWith("Dialogue:") -> {
                    // limit 必须恰好是 textField + 1：多给 1 会把 Text 里第一个
                    // 英文逗号之后的内容切到下一个元素，字幕会被截断。
                    val parts = line.removePrefix("Dialogue:")
                        .split(',', limit = textField + 1)
                    if (parts.size <= textField) return@forEach

                    val start = parseTimestamp(parts.getOrNull(startField).orEmpty())
                    val end = parseTimestamp(parts.getOrNull(endField).orEmpty())
                    val style = parts.getOrNull(styleField)?.trim().orEmpty()
                    val layer = parts.getOrNull(layerField)?.trim()?.toIntOrNull() ?: 0
                    val eventMarginV = parts.getOrNull(marginVField)?.trim()?.toIntOrNull() ?: 0
                    val info = styles[style.lowercase()] ?: AssStyle()
                    val rawBody = parts[textField]
                    val drawing = isDrawing(rawBody)
                    val body = cleanAssText(rawBody)

                    if (start >= 0 && end > start && body.isNotEmpty()) {
                        cues += SubtitleCue(
                            startMs = start,
                            endMs = end,
                            text = body,
                            style = style,
                            layer = layer,
                            order = order++,
                            fontSize = info.fontSize,
                            alignment = info.alignment,
                            marginV = if (eventMarginV != 0) eventMarginV else info.marginV,
                            kind = kindOf(style),
                            isDrawing = drawing,
                            language = detectLanguage(style, body),
                        )
                    }
                }
            }
        }
        return cues
    }

    private fun cleanAssText(raw: String): String {
        return raw
            .replace(assTag, "")
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .replace("\\h", " ")
            .trim()
    }

    /**
     * ASS 的 \p1 ~ \p9 是矢量绘图模式；这类 Sign 事件去掉标签后只剩
     * "m 315 48 l ..." 之类的路径坐标，直接当字幕会显示一大串数字。
     */
    private fun isDrawing(raw: String): Boolean {
        var mode = 0
        assDrawingTag.findAll(raw).forEach { match ->
            mode = match.groupValues[1].toIntOrNull() ?: 0
        }
        return mode > 0
    }

    private fun kindOf(style: String): SubtitleKind {
        val value = style.trim().lowercase()
        return when {
            value == "note" || value.startsWith("note") -> SubtitleKind.NOTE
            value == "sign" || value.startsWith("sign") -> SubtitleKind.SIGN
            value.startsWith("op") || value.startsWith("ed") -> SubtitleKind.LYRIC
            else -> SubtitleKind.DIALOGUE
        }
    }

    /**
     * 语言识别优先相信字幕样式名（Text - CN / Text - JP / OP - EN），
     * 没有样式信息时再按文字脚本判断：假名 → 日语，汉字 → 中文，拉丁字母 → 英语。
     */
    private fun detectLanguage(style: String, text: String): SubtitleLanguage {
        styleLanguage(style)?.let { return it }
        return when {
            kanaScript.containsMatchIn(text) -> SubtitleLanguage.JAPANESE
            hanScript.containsMatchIn(text) -> SubtitleLanguage.CHINESE
            latinScript.containsMatchIn(text) -> SubtitleLanguage.ENGLISH
            else -> SubtitleLanguage.OTHER
        }
    }

    private fun styleLanguage(style: String): SubtitleLanguage? {
        val tokens = style.lowercase().split(styleTokenSplit).filter { it.isNotEmpty() }
        val chinese = tokens.any {
            it in setOf("cn", "chs", "cht", "zh", "chi", "zho", "sc", "tc", "chinese")
        }
        val japanese = tokens.any { it in setOf("jp", "ja", "jpn", "japanese") }
        val english = tokens.any { it in setOf("en", "eng", "english") }
        return when {
            chinese && !japanese && !english -> SubtitleLanguage.CHINESE
            japanese && !chinese && !english -> SubtitleLanguage.JAPANESE
            english && !chinese && !japanese -> SubtitleLanguage.ENGLISH
            else -> null
        }
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

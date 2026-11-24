package com.glassplayer.danmaku

import org.json.JSONObject

enum class DanmakuMode { SCROLL, TOP, BOTTOM }

data class Danmaku(
    val timeMs: Long,
    val text: String,
    val mode: DanmakuMode,
    val color: Int,
)

/** 一次“匹配到某一集弹幕库”的结果。 */
data class DanmakuMatch(
    val source: String,
    val id: String,
    val title: String,
    val subtitle: String = "",
)

/**
 * 弹幕解析：同时吃 B 站 / 弹弹play 系接口返回的 XML（<d p="时间,模式,颜色,...">文本</d>）
 * 和 JSON（{"comments":[{"p":"...","m":"..."}]}）。
 */
object DanmakuParser {

    private val dTag = Regex("<d\\s+p=\"([^\"]*)\"[^>]*>(.*?)</d>", RegexOption.DOT_MATCHES_ALL)

    fun parse(text: String): List<Danmaku> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        return if (trimmed.startsWith("<")) parseXml(trimmed) else parseJson(trimmed)
    }

    fun parseXml(xml: String): List<Danmaku> {
        val out = ArrayList<Danmaku>(512)
        dTag.findAll(xml).forEach { m ->
            val params = m.groupValues[1].split(',')
            val content = unescape(m.groupValues[2])
            val item = build(params, content) ?: return@forEach
            out.add(item)
        }
        return out.sortedBy { it.timeMs }
    }

    fun parseJson(json: String): List<Danmaku> {
        val out = ArrayList<Danmaku>(512)
        runCatching {
            val root = JSONObject(json)
            val arr = root.optJSONArray("comments") ?: root.optJSONArray("data")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val p = o.optString("p") ?: continue
                    val content = unescape(o.optString("m"))
                    build(p.split(','), content)?.let { out.add(it) }
                }
            }
        }
        return out.sortedBy { it.timeMs }
    }

    private fun build(params: List<String>, content: String): Danmaku? {
        if (content.isBlank()) return null
        val seconds = params.getOrNull(0)?.toDoubleOrNull() ?: return null
        val mode = when (params.getOrNull(1)?.toIntOrNull()) {
            4 -> DanmakuMode.BOTTOM
            5 -> DanmakuMode.TOP
            else -> DanmakuMode.SCROLL
        }
        val color = params.getOrNull(2)?.toIntOrNull()?.takeIf { it in 0..0xFFFFFF } ?: 0xFFFFFF
        return Danmaku((seconds * 1000).toLong(), content, mode, color)
    }

    private fun unescape(raw: String): String = raw
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
        .trim()
}

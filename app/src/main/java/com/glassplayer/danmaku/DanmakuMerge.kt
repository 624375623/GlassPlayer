package com.glassplayer.danmaku

/**
 * 弹幕去重 / 合并。
 *
 * B 站和腾讯都存在同一句话被大量复制的情况（尤其是名场面），
 * 不合并的话同一秒会叠十几条一模一样的字，既没法看也拖慢绘制。
 * 这里按「文本 + 500ms 时间桶」判重：同一句话在同一秒内只留最早的一条。
 */
object DanmakuMerge {

    private const val BUCKET_MS = 500L

    fun dedupe(items: List<Danmaku>): List<Danmaku> {
        if (items.size < 2) return items
        val seen = HashSet<String>(items.size)
        val out = ArrayList<Danmaku>(items.size)
        items.forEach { item ->
            val key = "${item.text}|${item.timeMs / BUCKET_MS}"
            if (seen.add(key)) out.add(item)
        }
        return out.sortedBy { it.timeMs }
    }

    /** 多源合并：B 站 + 腾讯同集拼一份，按时间去重。 */
    fun merge(vararg sources: List<Danmaku>): List<Danmaku> {
        val all = ArrayList<Danmaku>(sources.sumOf { it.size })
        sources.forEach { all.addAll(it) }
        return dedupe(all)
    }
}

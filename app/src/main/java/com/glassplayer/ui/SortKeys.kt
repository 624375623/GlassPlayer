package com.glassplayer.ui

import java.text.Collator
import java.util.Locale

/**
 * 本地媒体库的标题排序键。
 *
 * 原实现是 `title.lowercase()` 直接比较，走的是 Unicode 码点序：
 * - 「第10集」排在「第2集」前面（'1' < '2'）
 * - 中文按码点排，不是拼音序
 * 这里和网盘列表的 cloudSortKey 对齐：数字补零后再比，中文交给 Collator 按拼音，
 * 另外提供「数字优先」和「首字母」两种取键方式。
 */
internal object SortKeys {

    /** 8 位定宽，覆盖集数、年份、版本号这类数字足够；超长数字退化为原样比较。 */
    private const val NUM_WIDTH = 8

    private val collator: Collator = Collator.getInstance(Locale.CHINA)

    private val hanToLatin: android.icu.text.Transliterator? = runCatching {
        android.icu.text.Transliterator.getInstance("Han-Latin")
    }.getOrNull()

    private val NUMBER = Regex("\\d+")

    /** 自然序：数字按数值（补零后比），其余按拼音。 */
    fun natural(title: String): String = padNumbers(title)

    /** 数字优先：含数字的按首个数字的数值排在前面，不含数字的排后面。 */
    fun numeric(title: String): String {
        val first = NUMBER.find(title)?.value
        return if (first != null) {
            "0" + first.padStart(NUM_WIDTH, '0') + padNumbers(title)
        } else {
            "1" + padNumbers(title)
        }
    }

    /** 首字母：取首字符的拼音首字母作分组键，同组内再按自然序。 */
    fun initial(title: String): String {
        val first = title.firstOrNull { !it.isWhitespace() && it != '.' } ?: return padNumbers(title)
        val key = when {
            first.isDigit() -> "#"
            first.code < 128 -> first.uppercaseChar()
            else -> hanInitial(first.toString()) ?: first
        }
        return "$key${padNumbers(title)}"
    }

    /** 汉字转拉丁后取首字母；Han-Latin 给的是带声调拼音（如 ān），先 NFD 拆成基本字母。 */
    private fun hanInitial(text: String): Char? {
        val latin = hanToLatin?.transliterate(text) ?: return null
        val decomposed = java.text.Normalizer.normalize(latin, java.text.Normalizer.Form.NFD)
        return decomposed.firstOrNull { it in 'a'..'z' || it in 'A'..'Z' }?.uppercaseChar()
    }

    /** 用 Collator 比较：中文按拼音，英文忽略大小写；完全相同时再按原串收尾保证稳定。 */
    fun compare(a: String, b: String): Int = synchronized(collator) {
        val c = collator.compare(a, b)
        if (c != 0) c else a.compareTo(b)
    }

    /**
     * 预生成排序键。
     *
     * Collator.compare 每次比较都要走一遍 ICU 的拼音转换，8000+ 条视频排序时会做
     * 十几万次比较，全部压在主线程上就是肉眼可见的卡顿。先用 getCollationKey 把每条
     * 记录的键算一次（O(n)），比较阶段只比二进制，单次比较便宜一个数量级。
     */
    fun collationKey(text: String): java.text.CollationKey = synchronized(collator) {
        collator.getCollationKey(text)
    }

    /** 路径排序：目录名优先，同目录再比文件名。 */
    fun path(folder: String, title: String): String =
        padNumbers(folder) + "/" + padNumbers(title)

    private fun padNumbers(s: String): String {
        if (s.isEmpty()) return s
        // 没有数字就直接返回，省掉一次 StringBuilder 分配
        var hasDigit = false
        for (ch in s) {
            if (ch.isDigit()) {
                hasDigit = true
                break
            }
        }
        if (!hasDigit) return s

        val out = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch.isDigit()) {
                var j = i
                while (j < s.length && s[j].isDigit()) j++
                val digits = s.substring(i, j)
                if (digits.length <= NUM_WIDTH) {
                    out.append(digits.padStart(NUM_WIDTH, '0'))
                } else {
                    out.append(digits)
                }
                i = j
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }
}

package com.glassplayer.danmaku

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.sp
import kotlin.math.max

/**
 * 自绘弹幕层。没有引第三方弹幕库（DanmakuFlameMaster 是 View 体系，和 Compose 播放页不好融合），
 * 直接用 Canvas 绘制：
 *
 * - 三种模式：滚动、顶部固定、底部固定；
 * - 轨道贪心分配，避免同屏互相压字；
 * - 时间轴跟播放器 position 走，暂停即冻结；
 * - 每帧只做绘制阶段的重绘（frame 状态只在 draw 里读），不触发重组，保证不掉帧。
 */
private class OverlayCache {
    var items: List<Danmaku>? = null
    var width = -1f
    var height = -1f
    var textSize = -1f
    var laneOf = IntArray(0)
    var widthOf = FloatArray(0)
    var laneHeight = 0f
    var laneCount = 0
    var fixedLanes = 0
    var scrollDuration = 0f
}

private const val FIXED_DURATION_MS = 4_000L
private const val SCROLL_DURATION_MS = 8_000f
private const val SCROLL_TAIL_PX = 48f

/** 顶部 / 底部固定弹幕各自最多占几行，多出来的直接并到这几行里滚动消失，避免整屏被压住。 */
private const val MAX_FIXED_LANES = 3

@Composable
fun DanmakuOverlay(
    items: List<Danmaku>,
    positionMs: Long,
    playing: Boolean,
    opacity: Float,
    fontColor: Int,
    textScale: Float,
    speedFactor: Float,
    areaFraction: Float,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    val paint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }
    val cache = remember { OverlayCache() }
    val clockMs = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val anchorPos = remember { mutableLongStateOf(positionMs) }
    val anchorClock = remember { mutableLongStateOf(clockMs.longValue) }

    LaunchedEffect(positionMs) {
        anchorPos.longValue = positionMs
        anchorClock.longValue = clockMs.longValue
    }

    LaunchedEffect(playing) {
        // 暂停时必须退出循环：之前 while(true) + if(playing) 会继续每帧唤醒，
        // 暂停后仍然 60fps 空转，持续耗电
        while (playing) {
            withFrameNanos {
                clockMs.longValue = SystemClock.elapsedRealtime()
            }
        }
    }

    Canvas(modifier) {
        val textSizePx = 14.sp.toPx() * textScale
        paint.textSize = textSizePx
        val alphaInt = (opacity.coerceIn(0.15f, 1f) * 255).toInt()

        val laneHeight = textSizePx * 1.55f
        val areaHeight = size.height * areaFraction.coerceIn(0.3f, 1f)
        val laneCount = max(1, (areaHeight / laneHeight).toInt())
        val fixedLanes = minOf(MAX_FIXED_LANES, laneCount)
        val scrollDuration = SCROLL_DURATION_MS / speedFactor.coerceIn(0.5f, 2f)

        if (cache.items !== items ||
            cache.width != size.width ||
            cache.height != size.height ||
            cache.textSize != textSizePx ||
            cache.laneCount != laneCount ||
            cache.scrollDuration != scrollDuration
        ) {
            cache.items = items
            cache.width = size.width
            cache.height = size.height
            cache.textSize = textSizePx
            cache.laneHeight = laneHeight
            cache.laneCount = laneCount
            cache.fixedLanes = fixedLanes
            cache.scrollDuration = scrollDuration
            // 只在真正要画的时候量文字宽度：一集上万条弹幕时，全量 measureText 会造成首帧卡顿
            cache.widthOf = FloatArray(items.size) { -1f }
            cache.laneOf = IntArray(items.size)

            fun pickLane(free: FloatArray, start: Float): Int {
                var chosen = -1
                var fallback = 0
                var fallbackFree = Float.MAX_VALUE
                for (lane in free.indices) {
                    if (free[lane] <= start) {
                        chosen = lane
                        break
                    }
                    if (free[lane] < fallbackFree) {
                        fallbackFree = free[lane]
                        fallback = lane
                    }
                }
                return if (chosen < 0) fallback else chosen
            }

            // 三种模式各用各的轨道池：滚动占满整个显示区，顶部/底部分别只有 3 行
            val scrollFree = FloatArray(laneCount) { -1e18f }
            val topFree = FloatArray(fixedLanes) { -1e18f }
            val bottomFree = FloatArray(fixedLanes) { -1e18f }
            items.forEachIndexed { i, item ->
                val start = item.timeMs.toFloat()
                when (item.mode) {
                    DanmakuMode.SCROLL -> {
                        val lane = pickLane(scrollFree, start)
                        cache.laneOf[i] = lane
                        scrollFree[lane] = start + scrollDuration
                    }

                    DanmakuMode.TOP -> {
                        val lane = pickLane(topFree, start)
                        cache.laneOf[i] = -(lane + 1)
                        topFree[lane] = start + FIXED_DURATION_MS.toFloat()
                    }

                    DanmakuMode.BOTTOM -> {
                        val lane = pickLane(bottomFree, start)
                        cache.laneOf[i] = -(lane + 1) - 100
                        bottomFree[lane] = start + FIXED_DURATION_MS.toFloat()
                    }
                }
            }
        }

        val sinceAnchor = (clockMs.longValue - anchorClock.longValue).coerceIn(0L, 400L)
        val now = anchorPos.longValue + if (playing) sinceAnchor else 0L

        var index = lowerBound(items, now - scrollDuration.toLong() - FIXED_DURATION_MS)
        // 颜色 / 阴影每条都一样，循环外设一次；原来每画一条都走一遍 setShadowLayer
        paint.color = (alphaInt shl 24) or (fontColor and 0xFFFFFF)
        paint.setShadowLayer(3f, 0f, 1.5f, 0xCC000000.toInt())
        while (index < items.size) {
            val item = items[index]
            if (item.timeMs > now) break
            val encodedLane = cache.laneOf.getOrElse(index) { 0 }
            var width = cache.widthOf.getOrElse(index) { -1f }
            if (width < 0f) {
                width = paint.measureText(item.text)
                if (index < cache.widthOf.size) cache.widthOf[index] = width
            }
            when (item.mode) {
                DanmakuMode.SCROLL -> {
                    val lane = encodedLane.coerceAtLeast(0)
                    val elapsed = (now - item.timeMs).toFloat()
                    if (elapsed in 0f..scrollDuration) {
                        val progress = elapsed / scrollDuration
                        val x = size.width - progress * (size.width + width + SCROLL_TAIL_PX)
                        val baseline = (lane * laneHeight) + textSizePx + 4f
                        paint.textAlign = android.graphics.Paint.Align.LEFT
                        drawIntoCanvas {
                            it.nativeCanvas.drawText(item.text, x, baseline, paint)
                        }
                    }
                }

                DanmakuMode.TOP -> {
                    if (now - item.timeMs <= FIXED_DURATION_MS) {
                        val lane = (-encodedLane - 1).coerceIn(0, max(0, cache.fixedLanes - 1))
                        val baseline = (lane * laneHeight) + textSizePx + 4f
                        paint.textAlign = android.graphics.Paint.Align.CENTER
                        drawIntoCanvas {
                            it.nativeCanvas.drawText(item.text, size.width / 2f, baseline, paint)
                        }
                    }
                }

                DanmakuMode.BOTTOM -> {
                    if (now - item.timeMs <= FIXED_DURATION_MS) {
                        val lane = (-encodedLane - 101).coerceIn(0, max(0, cache.fixedLanes - 1))
                        val baseline = size.height - 108f - (lane * laneHeight)
                        paint.textAlign = android.graphics.Paint.Align.CENTER
                        drawIntoCanvas {
                            it.nativeCanvas.drawText(item.text, size.width / 2f, baseline, paint)
                        }
                    }
                }
            }
            index++
        }
    }
}

private fun lowerBound(items: List<Danmaku>, timeMs: Long): Int {
    var lo = 0
    var hi = items.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (items[mid].timeMs < timeMs) lo = mid + 1 else hi = mid
    }
    return lo
}

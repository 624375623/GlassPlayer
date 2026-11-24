package com.glassplayer.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/**
 * 手势层：
 * - 单击：显隐控件
 * - 双击：左 1/3 快退 10 秒，右 1/3 快进 10 秒，中间播放/暂停
 * - 长按：临时 2× 倍速，松手恢复
 * - 单指横向拖动：拖动进度；单指竖向拖动：左半屏亮度、右半屏音量
 * - 双指捏合：缩放画面（缩小/放大），双指期间不再触发进度与亮度音量
 */
fun Modifier.playerGestures(
    enabled: Boolean,
    durationMs: Long,
    currentPosition: () -> Long,
    onTap: () -> Unit,
    onDoubleTapCenter: () -> Unit,
    onDoubleTapLeft: () -> Unit,
    onDoubleTapRight: () -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    onBrightnessDelta: (Float) -> Unit,
    onVolumeDelta: (Float) -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
    onPinchZoom: (Float) -> Unit,
): Modifier = this
    .pointerInput(enabled) {
        if (!enabled) return@pointerInput
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = { offset ->
                val third = size.width / 3f
                when {
                    offset.x < third -> onDoubleTapLeft()
                    offset.x > third * 2f -> onDoubleTapRight()
                    else -> onDoubleTapCenter()
                }
            },
            onLongPress = { onLongPressStart() },
            onPress = {
                val startedAt = System.currentTimeMillis()
                // 长按被判定后本次按压会被消费，tryAwaitRelease 返回 false 也算结束，统一按按住时长判断。
                tryAwaitRelease()
                if (System.currentTimeMillis() - startedAt >= 450L) onLongPressEnd()
            },
        )
    }
    .pointerInput(enabled, durationMs) {
        if (!enabled) return@pointerInput
        val seekSpan = durationMs.coerceIn(1L, 8 * 60_000L)

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var mode = 0 // 0 未判定，1 进度，2 亮度/音量
            var totalX = 0f
            var totalY = 0f
            var seekTarget = currentPosition()
            var pinching = false

            while (true) {
                val event = awaitPointerEvent()
                val pressedCount = event.changes.count { it.pressed }

                // 双指：直接接管为画面缩放，之前如果已经在拖进度就先收尾
                if (pressedCount >= 2) {
                    if (!pinching) {
                        pinching = true
                        if (mode == 1) onSeekFinished()
                        mode = 0
                    }
                    val zoom = event.calculateZoom()
                    if (zoom.isFinite() && zoom != 1f) onPinchZoom(zoom)
                    event.changes.forEach { it.consume() }
                    continue
                }

                if (pinching) {
                    // 松开一根手指后不再回落到单指拖动，等全部抬起再结束
                    if (pressedCount == 0) break
                    continue
                }

                val change = event.changes.firstOrNull { it.id == down.id }
                    ?: event.changes.firstOrNull { it.pressed }
                    ?: break
                if (!change.pressed) {
                    if (mode == 1) onSeekFinished()
                    break
                }

                val dx = change.position.x - change.previousPosition.x
                val dy = change.position.y - change.previousPosition.y
                totalX += dx
                totalY += dy

                if (mode == 0) {
                    if (abs(totalX) > 26f && abs(totalX) > abs(totalY)) {
                        mode = 1
                    } else if (abs(totalY) > 26f) {
                        mode = 2
                    }
                }

                when (mode) {
                    1 -> {
                        val deltaMs = (dx / size.width.toFloat()) * seekSpan
                        seekTarget = (seekTarget + deltaMs.toLong()).coerceIn(0L, durationMs)
                        onSeekPreview(seekTarget)
                        change.consume()
                    }

                    2 -> {
                        val delta = -dy / size.height.toFloat()
                        if (change.position.x < size.width / 2f) {
                            onBrightnessDelta(delta)
                        } else {
                            onVolumeDelta(delta)
                        }
                        change.consume()
                    }
                }
            }
        }
    }

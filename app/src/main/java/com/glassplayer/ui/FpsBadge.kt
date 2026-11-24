package com.glassplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 帧率指示器：每 500ms 统计一次实际合成帧数，用来验证是否真的跑在 60/120Hz。
 * 只做两次状态更新/秒，自身几乎不消耗性能。
 */
@Composable
fun FpsBadge(modifier: Modifier = Modifier) {
    var fps by remember { mutableIntStateOf(0) }
    var frames by remember { mutableIntStateOf(0) }
    var windowStart by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { now ->
                if (windowStart == 0L) windowStart = now
                frames++
                val elapsed = now - windowStart
                if (elapsed >= 500_000_000L) {
                    fps = (frames * 1_000_000_000L / elapsed).toInt()
                    frames = 0
                    windowStart = now
                }
            }
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(
            text = "$fps fps",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = Color.White,
        )
    }
}

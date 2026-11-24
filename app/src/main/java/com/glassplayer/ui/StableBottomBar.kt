package com.glassplayer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol

/**
 * 底部导航的一项。
 * 这里不再依赖玻璃折射层：某些厂商 GPU 合成时报错，会把采样矩形整块画出来。
 */
data class BottomBarItem(val key: Int, val label: String, val icon: SfSymbol)

/**
 * 自绘底部导航胶囊。
 *
 * 历史问题：Glasmio 的导航栏靠 backdrop 实时采样做透镜折射，个别机型（如 vivo 部分版本）
 * 在导航层做位移动画时会把这一层当独立图层合成，采样矩形没被胶囊轮廓裁掉，
 * 屏幕上就出现一整条粉/蓝色方块。这里改成纯本地绘制——背景、描边、高光全部按胶囊形状
 * 裁切，不含任何跨图层采样，位移时只剩一个圆角胶囊整体上下移动，任何设备都不会出方块。
 */
@Composable
fun StableBottomBar(
    items: List<BottomBarItem>,
    selectedKey: Int,
    onSelected: (Int) -> Unit,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    val topColor = if (isDark) Color(0xFF2A2E36).copy(alpha = 0.94f) else Color(0xFFFCFDFF).copy(alpha = 0.96f)
    val bottomColor = if (isDark) Color(0xFF15171C).copy(alpha = 0.94f) else Color(0xFFEDF0F6).copy(alpha = 0.96f)
    val borderColor = if (isDark) Color(0x33FFFFFF) else Color(0x1A000000)
    val inert = (if (isDark) Color.White else Color(0xFF15171C)).copy(alpha = if (isDark) 0.86f else 0.82f)
    val accent = GlasmioTheme.colors.accent

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(topColor, bottomColor)))
            .border(1.dp, borderColor, shape)
            .drawBehind {
                // 顶部内高光 + 底部暗边，用一个极细的渐变伪造玻璃厚度，不产生额外图层
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to (if (isDark) Color(0x26FFFFFF) else Color(0x33FFFFFF)),
                        0.16f to Color.Transparent,
                    ),
                    size = size,
                )
                drawLine(
                    color = if (isDark) Color(0x14FFFFFF) else Color(0x0F000000),
                    start = Offset(1f, size.height - 1f),
                    end = Offset(size.width - 1f, size.height - 1f),
                    strokeWidth = 1f,
                )
            },
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val selected = item.key == selectedKey
                val color = if (selected) accent else inert
                // 不使用 Material ripple：矩形波纹在没有背景的格子上会显成一块灰色暗影。
                // 按下态改为极轻微的缩放，任何设备都只会看到图标/文字本身的变化。
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val pressScale by animateFloatAsState(
                    targetValue = if (pressed) 0.92f else 1f,
                    animationSpec = tween(durationMillis = 120),
                    label = "nav-press",
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = pressScale
                            scaleY = pressScale
                        }
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                        ) { onSelected(item.key) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    SfIcon(
                        item.icon,
                        item.label,
                        size = 21.dp,
                        tint = color,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.label,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = color,
                    )
                }
            }
        }
    }
}

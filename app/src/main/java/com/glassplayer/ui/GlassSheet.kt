package com.glassplayer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.glass.GlassSurface

/**
 * 应用内底部弹层：视觉沿用 Glasmio 的 iOS 风格玻璃面板，但不新开 Dialog 窗口。
 *
 * 两个性能要点：
 * 1) Dialog 每次弹出都要创建窗口并做首帧合成，是“点设置卡一下”的主要来源，这里改为同窗口内动画；
 * 2) 动画进度只在 draw/graphicsLayer 阶段读取，不进入组合阶段——内容只组合一次，
 *    动画全程不产生重组，滚动长列表时也不会被弹层拖慢。
 */
@Composable
fun GlassSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    maxContentHeight: Int = 520,
    content: @Composable ColumnScope.() -> Unit,
) {
    val progress = remember { Animatable(if (visible) 1f else 0f) }
    var rendered by remember { mutableStateOf(visible) }

    LaunchedEffect(visible) {
        if (visible) {
            rendered = true
            // 弹出用弹簧：到底部时有一点点回弹，比线性 tween 更有「物理感」
            progress.animateTo(
                1f,
                spring(
                    // 临界阻尼：一滑到底不抖动，比带一点回弹的弹簧更「丝滑」
                    dampingRatio = 1f,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        } else {
            progress.animateTo(0f, tween(durationMillis = 165, easing = FastOutSlowInEasing))
            rendered = false
        }
    }

    if (!rendered) return

    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 进度在绘制阶段读取，避免每帧重组
            .drawBehind {
                drawRect(Color.Black, alpha = 0.32f * progress.value)
            }
            .clickable(interactionSource = interaction, indication = null) { onDismissRequest() },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer {
                    val p = progress.value.coerceIn(0f, 1f)
                    translationY = (1f - p) * 760f
                    alpha = p
                    // 0.94 → 1.0：面板像从屏幕下方「浮」上来，而不是纯平移
                    val scale = 0.94f + 0.06f * p
                    scaleX = scale
                    scaleY = scale
                }
                .padding(horizontal = 6.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 6.dp)
                // 吞掉面板内的空白点击，避免冒泡到遮罩触发关闭
                .clickable(interactionSource = interaction, indication = null) {},
        ) {
            val panelColor = if (GlasmioTheme.colors.isDark) Color(0xF216181C) else Color(0xF7FAFBFC)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(GlasmioTheme.shapes.large)
                    .background(panelColor)
                    .border(
                        width = 1.dp,
                        color = GlasmioTheme.colors.subtleStroke.copy(alpha = 0.35f),
                        shape = GlasmioTheme.shapes.large,
                    ),
            ) {
            GlassSurface(
                shape = GlasmioTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
                sampleBackdrop = false,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(36.dp)
                            .height(4.dp)
                            .background(
                                GlasmioTheme.colors.content.copy(alpha = 0.25f),
                                RoundedCornerShape(50),
                            ),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            color = GlasmioTheme.colors.content,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(50))
                                .clickable(interactionSource = interaction, indication = null) {
                                    onDismissRequest()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            SfIcon(
                                "xmark",
                                "关闭",
                                size = 14.dp,
                                tint = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxContentHeight.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        content()
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            }
        }
    }
}

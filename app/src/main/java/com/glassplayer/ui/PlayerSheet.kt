package com.glassplayer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.icons.SfIcon

/**
 * 播放页专用弹层：只占半屏，另一半保留画面。
 *
 * - 横屏（宽 > 高）：面板贴右侧，占 46% 宽度，整屏高度；
 * - 竖屏：面板贴底部，占 56% 高度，整屏宽度；
 * - 遮罩只压很淡的一层，面板底色透明度由 [panelAlpha] 控制，方便边看画面边调设置。
 */
@Composable
fun PlayerSheet(
    panelAlpha: Float,
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
            progress.animateTo(1f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
        } else {
            progress.animateTo(0f, tween(durationMillis = 165, easing = FastOutSlowInEasing))
            rendered = false
        }
    }

    if (!rendered) return

    val interaction = remember { MutableInteractionSource() }
    val config = LocalConfiguration.current
    val isLandscape = config.screenWidthDp > config.screenHeightDp
    val panelShape = if (isLandscape) {
        RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp)
    } else {
        RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    }
    val panelColor = (if (GlasmioTheme.colors.isDark) Color(0xFF15171C) else Color(0xFFFBFCFE))
        .copy(alpha = panelAlpha.coerceIn(0.3f, 1f))

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                // 比全屏弹层淡很多：半屏面板，另一侧要能看清画面
                drawRect(Color.Black, alpha = 0.16f * progress.value)
            }
            .clickable(interactionSource = interaction, indication = null) { onDismissRequest() },
    ) {
        Column(
            modifier = if (isLandscape) {
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(0.46f)
            } else {
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.56f)
            }
                .graphicsLayer {
                    val p = progress.value.coerceIn(0f, 1f)
                    if (isLandscape) {
                        translationX = (1f - p) * size.width
                    } else {
                        translationY = (1f - p) * size.height
                    }
                }
                .windowInsetsPadding(
                    if (isLandscape) WindowInsets.systemBars else WindowInsets.navigationBars,
                )
                .padding(
                    if (isLandscape) {
                        PaddingValues(top = 8.dp, bottom = 8.dp, end = 8.dp)
                    } else {
                        PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                    },
                )
                .clickable(interactionSource = interaction, indication = null) {},
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(panelShape)
                    .background(panelColor)
                    .border(
                        width = 1.dp,
                        color = GlasmioTheme.colors.subtleStroke.copy(alpha = 0.30f),
                        shape = panelShape,
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
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
                    Spacer(Modifier.height(2.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
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

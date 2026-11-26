package io.github.neoruaa.glasmio.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme

/** 安卓 9 版只保留玻璃卡片的视觉语义，不做实时采样/模糊。 */
interface Backdrop

class LayerBackdrop internal constructor() : Backdrop

class CanvasBackdrop internal constructor(
    private val onDraw: DrawScope.() -> Unit,
) : Backdrop {
    fun draw(scope: DrawScope) = scope.onDraw()
}

enum class GlassEmphasis { Regular, Prominent }

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    emphasis: GlassEmphasis = GlassEmphasis.Regular,
    sampleBackdrop: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = GlasmioTheme.colors
    val container = if (emphasis == GlassEmphasis.Prominent) {
        colors.prominentContainer
    } else {
        colors.container
    }
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Box(
        modifier = modifier
            .clip(shape)
            .background(container, shape)
            .border(1.dp, colors.subtleStroke, shape)
            .then(clickable),
        content = content,
    )
}

@Composable
fun GlasmioBackdropHost(
    modifier: Modifier = Modifier,
    sampledContent: @Composable BoxScope.(LayerBackdrop) -> Unit,
    overlayContent: @Composable BoxScope.(LayerBackdrop) -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    Box(modifier) {
        sampledContent(backdrop)
        overlayContent(backdrop)
    }
}

@Composable
fun GlasmioBackdropProvider(
    backdrop: Backdrop,
    content: @Composable () -> Unit,
) {
    content()
}

@Composable
fun rememberLayerBackdrop(): LayerBackdrop = remember { LayerBackdrop() }

@Composable
fun rememberCanvasBackdrop(onDraw: DrawScope.() -> Unit): Backdrop =
    remember { CanvasBackdrop(onDraw) }

@Composable
fun rememberCombinedBackdrop(first: Backdrop, second: Backdrop): Backdrop = first

@Composable
fun rememberCombinedBackdrop(first: Backdrop, second: Backdrop, third: Backdrop): Backdrop = first

fun Modifier.layerBackdrop(backdrop: LayerBackdrop): Modifier = this

fun Modifier.trackBackdropPosition(backdrop: LayerBackdrop): Modifier = this

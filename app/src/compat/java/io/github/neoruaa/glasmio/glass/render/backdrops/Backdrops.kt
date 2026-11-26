package io.github.neoruaa.glasmio.glass.render.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.neoruaa.glasmio.glass.Backdrop
import io.github.neoruaa.glasmio.glass.LayerBackdrop

@Composable
fun rememberLayerBackdrop(): LayerBackdrop =
    io.github.neoruaa.glasmio.glass.rememberLayerBackdrop()

@Composable
fun rememberCanvasBackdrop(onDraw: DrawScope.() -> Unit): Backdrop =
    io.github.neoruaa.glasmio.glass.rememberCanvasBackdrop(onDraw)

@Composable
fun rememberCombinedBackdrop(first: Backdrop, second: Backdrop): Backdrop =
    io.github.neoruaa.glasmio.glass.rememberCombinedBackdrop(first, second)

@Composable
fun rememberCombinedBackdrop(first: Backdrop, second: Backdrop, third: Backdrop): Backdrop =
    io.github.neoruaa.glasmio.glass.rememberCombinedBackdrop(first, second, third)

fun Modifier.layerBackdrop(backdrop: LayerBackdrop): Modifier = this

package io.github.neoruaa.glasmio.core

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Android 9 兼容层的主题 token。
 *
 * 真实 Glasmio 0.1.2 的 AAR 最低 API 33，且玻璃效果直接依赖
 * RenderEffect / RuntimeShader。安卓 9 版不加载它，改用这组接近 iOS
 * 深色玻璃的纯色/半透明 token，保证原有页面代码不用改。
 */
data class GlassColors(
    val isDark: Boolean,
    val accent: Color,
    val content: Color,
    val secondaryContent: Color,
    val container: Color,
    val prominentContainer: Color,
    val subtleStroke: Color,
    val groupedBackground: Color,
    val elevatedBackground: Color,
    val separator: Color,
    val tertiaryContent: Color,
    val destructive: Color,
)

data class GlasmioShapes(
    val small: Shape,
    val medium: Shape,
    val large: Shape,
)

private fun defaultShapes(): GlasmioShapes = GlasmioShapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(28.dp),
)

fun defaultGlassColors(dark: Boolean): GlassColors = if (dark) {
    GlassColors(
        isDark = true,
        accent = Color(0xFF0A84FF),
        content = Color.White,
        secondaryContent = Color.White.copy(alpha = 0.72f),
        container = Color(0xCC1C1C1E),
        prominentContainer = Color(0xE6333338),
        subtleStroke = Color.White.copy(alpha = 0.18f),
        groupedBackground = Color(0xFF1C1C1E),
        elevatedBackground = Color(0xFF2C2C2E),
        separator = Color.White.copy(alpha = 0.12f),
        tertiaryContent = Color.White.copy(alpha = 0.45f),
        destructive = Color(0xFFFF453A),
    )
} else {
    GlassColors(
        isDark = false,
        accent = Color(0xFF007AFF),
        content = Color(0xFF1C1C1E),
        secondaryContent = Color(0xFF1C1C1E).copy(alpha = 0.65f),
        container = Color(0xE6FFFFFF),
        prominentContainer = Color(0xF2FFFFFF),
        subtleStroke = Color.Black.copy(alpha = 0.12f),
        groupedBackground = Color(0xFFF2F2F7),
        elevatedBackground = Color.White,
        separator = Color.Black.copy(alpha = 0.10f),
        tertiaryContent = Color(0xFF1C1C1E).copy(alpha = 0.42f),
        destructive = Color(0xFFFF3B30),
    )
}

val LocalGlassColors = compositionLocalOf { defaultGlassColors(false) }
val LocalGlasmioShapes = compositionLocalOf { defaultShapes() }

fun glasmioColorScheme(dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = Color(0xFF0A84FF),
        onPrimary = Color.White,
        background = Color(0xFF0B0B0C),
        onBackground = Color.White,
        surface = Color(0xFF1C1C1E),
        onSurface = Color.White,
        surfaceVariant = Color(0xFF2C2C2E),
        onSurfaceVariant = Color.White.copy(alpha = 0.72f),
        error = Color(0xFFFF453A),
    )
} else {
    lightColorScheme(
        primary = Color(0xFF007AFF),
        onPrimary = Color.White,
        background = Color(0xFFF2F2F7),
        onBackground = Color(0xFF1C1C1E),
        surface = Color.White,
        onSurface = Color(0xFF1C1C1E),
        surfaceVariant = Color(0xFFE5E5EA),
        onSurfaceVariant = Color(0xFF1C1C1E).copy(alpha = 0.65f),
        error = Color(0xFFFF3B30),
    )
}

object GlasmioTheme {
    val colors: GlassColors
        @Composable get() = LocalGlassColors.current

    val shapes: GlasmioShapes
        @Composable get() = LocalGlasmioShapes.current
}

@Composable
fun GlasmioTheme(
    isDark: Boolean,
    colorScheme: ColorScheme = glasmioColorScheme(isDark),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalGlassColors provides defaultGlassColors(isDark),
        LocalGlasmioShapes provides defaultShapes(),
    ) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

package io.github.neoruaa.glasmio.icons

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * SF Symbols 的安卓 9 降级实现：不加载 Glasmio 的字体资源，改为单色字符。
 * 图标只承担识别作用，尺寸/颜色/语义描述仍按原调用点工作。
 */
enum class SfSymbol(val systemName: String) {
    House("house"),
    HouseFilled("house.fill"),
    Layers("square.3.layers.3d"),
    InfoCircle("info.circle"),
    Sparkles("sparkles"),
    MusicNote("music.note"),
    MusicNoteSquareStackFilled("music.note.square.stack.fill"),
    RadioWaves("radio.waves"),
    RadioFilled("radio.fill"),
    Safari("safari"),
    SafariFilled("safari.fill"),
    MusicNoteList("music.note.list"),
    Microphone("microphone"),
    Heart("heart"),
    Star("star"),
    StarFilled("star.fill"),
    Download("arrow.down.circle"),
    DownloadFilled("arrow.down.circle.fill"),
    Cloud("cloud"),
    CloudFilled("cloud.fill"),
    Clock("clock"),
    ClockFilled("clock.fill"),
    ArrowClockwise("arrow.clockwise"),
    Warning("exclamationmark.triangle"),
    Search("magnifyingglass"),
    Settings("gear"),
    Gearshape("gearshape"),
    PersonFilled("person.fill"),
    Waveform("waveform"),
    PlayFilled("play.fill"),
    PauseFilled("pause.fill"),
    ForwardFilled("forward.fill"),
    BackwardFilled("backward.fill"),
    Ellipsis("ellipsis"),
    Close("xmark"),
    ChevronBack("chevron.left"),
}

private fun glyphFor(name: String): String = when (name) {
    "chevron.left", "chevron.backward" -> "‹"
    "chevron.right", "chevron.forward" -> "›"
    "arrow.clockwise" -> "⟳"
    "arrow.up.arrow.down" -> "⇅"
    "checkmark", "checkmark.circle.fill" -> "✓"
    "xmark", "xmark.circle.fill", "xmark.circle" -> "✕"
    "magnifyingglass" -> "⌕"
    "gear", "gearshape" -> "⚙"
    "play.fill" -> "▶"
    "pause.fill" -> "Ⅱ"
    "forward.fill" -> "⏩"
    "backward.fill" -> "⏪"
    "play.rectangle" -> "▭"
    "rectangle.on.rectangle" -> "▭"
    "heart", "heart.fill" -> "♥"
    "star", "star.fill" -> "★"
    "folder" -> "▱"
    "doc.on.doc" -> "⧉"
    "ellipsis" -> "⋯"
    "speaker.wave.2.fill" -> "◖"
    "cloud", "cloud.fill" -> "☁"
    "clock", "clock.fill" -> "◷"
    "arrow.down.circle", "arrow.down.circle.fill" -> "↓"
    "square.3.layers.3d" -> "▤"
    "house", "house.fill" -> "⌂"
    "person.fill" -> "◉"
    "waveform" -> "〜"
    "music.note", "music.note.list" -> "♪"
    "sparkles" -> "✦"
    "info.circle" -> "ⓘ"
    "exclamationmark.triangle" -> "⚠"
    "safari", "safari.fill" -> "◎"
    "microphone" -> "◉"
    "radio.waves", "radio.fill" -> "◉"
    else -> "•"
}

@Composable
fun SfIcon(
    systemName: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = Color.Unspecified,
) {
    SfIconGlyph(glyphFor(systemName), contentDescription, modifier, size, tint)
}

@Composable
fun SfIcon(
    symbol: SfSymbol,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = Color.Unspecified,
) {
    SfIconGlyph(glyphFor(symbol.systemName), contentDescription, modifier, size, tint)
}

private fun vectorFor(glyph: String): ImageVector? = when (glyph) {
    "⌕" -> Icons.Filled.Search
    "⚙" -> Icons.Filled.Settings
    "▶" -> Icons.Filled.PlayArrow
    "⟳" -> Icons.Filled.Refresh
    "‹" -> Icons.Filled.KeyboardArrowLeft
    "›" -> Icons.Filled.KeyboardArrowRight
    "⇅" -> Icons.Filled.KeyboardArrowUp
    "♥" -> Icons.Filled.Favorite
    "★" -> Icons.Filled.Star
    "✓" -> Icons.Filled.Check
    "✕" -> Icons.Filled.Close
    "⋯" -> Icons.Filled.MoreVert
    "⚠" -> Icons.Filled.Warning
    "ⓘ" -> Icons.Filled.Info
    "⌂" -> Icons.Filled.Home
    "◉" -> Icons.Filled.Person
    "▭" -> Icons.Filled.ShoppingCart
    else -> null
}

@Composable
private fun SfIconGlyph(
    glyph: String,
    contentDescription: String?,
    modifier: Modifier,
    size: Dp,
    tint: Color,
) {
    val color = if (tint == Color.Unspecified) LocalContentColor.current else tint
    val vector = vectorFor(glyph)
    if (vector != null) {
        Icon(
            imageVector = vector,
            contentDescription = contentDescription,
            modifier = modifier.size(size),
            tint = color,
        )
        return
    }
    val semanticsModifier = if (contentDescription.isNullOrBlank()) {
        modifier
    } else {
        modifier.semantics { this.contentDescription = contentDescription }
    }
    Box(modifier = semanticsModifier, contentAlignment = Alignment.Center) {
        Text(
            text = glyph,
            color = color,
            fontSize = size.value.sp,
            lineHeight = size.value.sp,
            textAlign = TextAlign.Center,
        )
    }
}

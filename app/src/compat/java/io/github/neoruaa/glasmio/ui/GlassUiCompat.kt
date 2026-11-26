package io.github.neoruaa.glasmio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.Backdrop
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol

data class GlasmioTabItem<T>(
    val key: T,
    val label: String,
    val symbol: SfSymbol,
    val contentDescription: String = label,
)

@Composable
fun GlasmioButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = GlasmioTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.prominentContainer,
            contentColor = colors.content,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 18.dp,
            vertical = 10.dp,
        ),
        content = content,
    )
}

@Composable
fun GlasmioIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = GlasmioTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.container)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
fun <T> GlasmioNavigationBar(
    items: List<GlasmioTabItem<T>>,
    selectedKey: T,
    onSelected: (T) -> Unit,
    onExpand: () -> Unit = {},
    compactProgress: MutableState<Float> = remember { mutableStateOf(0f) },
    compactSize: Dp = 56.dp,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
) {
    val colors = GlasmioTheme.colors
    Row(
        modifier = modifier
            .height(compactSize)
            .clip(RoundedCornerShape(28.dp))
            .background(colors.container)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            val selected = item.key == selectedKey
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .clickable { onSelected(item.key) }
                    .padding(vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SfIcon(
                    symbol = item.symbol,
                    contentDescription = item.contentDescription,
                    size = 18.dp,
                    tint = if (selected) colors.accent else colors.content.copy(alpha = 0.62f),
                )
                Text(
                    text = item.label,
                    color = if (selected) colors.accent else colors.content.copy(alpha = 0.62f),
                    fontSize = 10.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
fun GlasmioBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return
    Column(modifier = modifier.fillMaxWidth(), content = content)
}

@Composable
fun GlasmioAdaptiveSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Slider(value = value, onValueChange = onValueChange, modifier = modifier)
}

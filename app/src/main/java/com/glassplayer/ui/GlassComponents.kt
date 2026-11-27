package com.glassplayer.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Density
import com.glassplayer.data.ThumbnailLoader
import com.glassplayer.data.VideoFolder
import com.glassplayer.data.VideoItem
import com.glassplayer.data.formatDuration
import com.glassplayer.data.formatSize
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.icons.SfIcon

/**
 * 视频卡片（视频框）底板的不透明度。
 *
 * 只改卡片底色，标题 / 副标题 / 时长这些文字的 alpha 完全不动 ——
 * 用户要的是「背景能透出壁纸、字还是实心的」。
 */
val LocalCardBackgroundAlpha = androidx.compose.runtime.compositionLocalOf { 1f }

@Composable
internal fun videoCardBackground(selected: Boolean): Color {
    val base = GlasmioTheme.colors.elevatedBackground
    return if (selected) {
        GlasmioTheme.colors.accent.copy(alpha = 0.22f)
    } else {
        base.copy(alpha = base.alpha * LocalCardBackgroundAlpha.current.coerceIn(0f, 1f))
    }
}

/**
 * 按压反馈：按下时轻微缩小、松开弹回。
 *
 * 用 spring 而不是 tween，松手有一种「回弹」的手感；缩放只在 graphicsLayer 里改，
 * 不触发重组也不影响布局，列表里成百上千个卡片同时存在也不会掉帧。
 */
@Composable
private fun pressScale(
    interaction: MutableInteractionSource,
    pressedScale: Float = 0.955f,
): Float {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            // 按下立刻缩、松手立刻回，阻尼取接近临界，避免回弹拖沓
            dampingRatio = 0.9f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "pressScale",
    )
    return scale
}

@Composable
fun GlassPill(
    text: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    selected: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = GlasmioTheme.colors
    val bg = if (selected) colors.accent else colors.elevatedBackground
    val fg = when {
        danger -> Color(0xFFFF5A5C)
        selected -> Color.White
        else -> colors.content
    }
    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(32.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) SfIcon(icon, null, size = 14.dp, tint = fg)
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            maxLines = 1,
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = GlasmioTheme.colors.content.copy(alpha = 0.85f),
    )
}

private fun durationLabel(item: VideoItem): String =
    if (item.uri.startsWith("quark://") && item.durationMs <= 0) "网盘" else formatDuration(item.durationMs)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoRow(
    item: VideoItem,
    progressMs: Long,
    favorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    metaExtra: String? = null,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, item.uri) {
        value = ThumbnailLoader.load(context, item, width = 240)
    }

    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(GlasmioTheme.shapes.medium)
            .background(videoCardBackground(selected))
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .height(58.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF101018)),
            ) {
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Text(
                    text = durationLabel(item),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontSize = 10.sp,
                )
                if (progressMs > 0 && item.durationMs > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color(0x66000000)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((progressMs.toFloat() / item.durationMs).coerceIn(0f, 1f))
                                .height(3.dp)
                                .background(Color(0xFF4C8DFF)),
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                    fontWeight = FontWeight.Medium,
                    color = GlasmioTheme.colors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append("${item.folderName} · ${item.resolutionText} · ${formatSize(item.sizeBytes)}")
                        if (metaExtra != null) append(" · $metaExtra")
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (selected) {
                SfIcon("checkmark.circle.fill", "已选择", size = 20.dp, tint = GlasmioTheme.colors.accent)
            } else if (favorite) {
                SfIcon("heart.fill", "已收藏", size = 16.dp)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoTile(
    item: VideoItem,
    progressMs: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, item.uri) {
        value = ThumbnailLoader.load(context, item, width = 240)
    }
    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(GlasmioTheme.shapes.medium)
            .background(videoCardBackground(selected))
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF101018)),
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                text = durationLabel(item),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontSize = 10.sp,
            )
            if (selected) {
                SfIcon(
                    "checkmark.circle.fill",
                    "已选择",
                    size = 22.dp,
                    tint = GlasmioTheme.colors.accent,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                )
            }
            if (progressMs > 0 && item.durationMs > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color(0x66000000)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((progressMs.toFloat() / item.durationMs).coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(Color(0xFF4C8DFF)),
                    )
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            fontWeight = FontWeight.Medium,
            color = GlasmioTheme.colors.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${item.resolutionText} · ${formatSize(item.sizeBytes)}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun FolderRow(
    folder: VideoFolder,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, folder.newest.uri) {
        value = ThumbnailLoader.load(context, folder.newest)
    }

    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(GlasmioTheme.shapes.medium)
            .background(videoCardBackground(selected = false))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF101018)),
            ) {
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text("${folder.count}", style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                    fontWeight = FontWeight.Medium,
                    color = GlasmioTheme.colors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${folder.count} 个视频 · 最新 ${folder.newest.title}",
                    style = MaterialTheme.typography.labelSmall,
                    color = GlasmioTheme.colors.content.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SfIcon("chevron.right", null, size = 14.dp)
        }
    }
}

@Composable
fun ResumeCard(
    item: VideoItem,
    progressMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, item.uri) {
        value = ThumbnailLoader.load(context, item, width = 240)
    }
    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    Column(
        modifier = modifier
            // 「继续观看」横排卡片原来 178dp 宽，一屏只能露一张半，显得又大又挤；
            // 收到 132dp（缩略图 16:9 ≈ 74dp 高），一行能放 2.5 张，字号也一起收小。
            .width(132.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(14.dp))
            .background(
                Color(0x22000000).let {
                    it.copy(alpha = it.alpha * LocalCardBackgroundAlpha.current.coerceIn(0f, 1f))
                },
            )
            // 之前只有缩放进场动画，漏了点击：整张「继续观看」卡片点了没有任何反应
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color(0xFF101018)),
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                text = durationLabel(item),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
                // 缩略图是深色的，浅色主题下用 content 色会变成深灰字压深底，直接看不清
                color = Color.White,
                fontSize = 9.sp,
            )
            if (item.durationMs > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color(0x66000000)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((progressMs.toFloat() / item.durationMs).coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(Color(0xFF4C8DFF)),
                    )
                }
            }
        }
        Column(Modifier.padding(horizontal = 6.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                fontWeight = FontWeight.Medium,
                color = GlasmioTheme.colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "已看到 ${formatDuration(progressMs)}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = GlasmioTheme.colors.content.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
fun ToggleRow(title: String, description: String? = null, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = GlasmioTheme.colors.content)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.labelSmall,
                    color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        GlassPill(text = if (checked) "开启" else "关闭", selected = checked, onClick = onToggle)
    }
}

@Composable
fun EmptyState(title: String, subtitle: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SfIcon("rectangle.on.rectangle", null, size = 30.dp)
        Text(title, style = MaterialTheme.typography.titleSmall, color = GlasmioTheme.colors.content)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = GlasmioTheme.colors.content.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (action != null && onAction != null) {
            GlassPill(text = action, selected = true, onClick = onAction)
        }
    }
}

/**
 * 轻量滑杆：高度只有 26dp，轨道 3dp，用于弹层里的数值调节，
 * 比 GlasmioAdaptiveSlider 紧凑很多，不会撑开面板。
 */
@Composable
fun SlimSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
    modifier: Modifier = Modifier,
    trackHeight: Dp = 26.dp,
    thumbRadius: Dp = 7.dp,
    barHeight: Dp = 3.dp,
) {
    val accent = GlasmioTheme.colors.accent
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val shown = (dragValue ?: value).coerceIn(0f, 1f)

    val density = androidx.compose.ui.platform.LocalDensity.current
    fun fractionOf(x: Float, widthPx: Float): Float {
        val r = with(density) { thumbRadius.toPx() }
        val usable = (widthPx - 2 * r).coerceAtLeast(1f)
        return ((x - r) / usable).coerceIn(0f, 1f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val next = fractionOf(offset.x, size.width.toFloat())
                    dragValue = next
                    onValueChange(next)
                    dragValue = null
                    onValueChangeFinished()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val next = fractionOf(offset.x, size.width.toFloat())
                        dragValue = next
                        onValueChange(next)
                    },
                    onDragEnd = {
                        dragValue = null
                        onValueChangeFinished()
                    },
                    onDragCancel = { dragValue = null },
                ) { change, _ ->
                    change.consume()
                    val next = fractionOf(change.position.x, size.width.toFloat())
                    dragValue = next
                    onValueChange(next)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val r = thumbRadius.toPx()
            val usable = (size.width - 2 * r).coerceAtLeast(1f)
            val barH = barHeight.toPx()
            drawRoundRect(
                color = Color.White.copy(alpha = 0.14f),
                topLeft = Offset(r, cy - barH / 2f),
                size = Size(usable, barH),
                cornerRadius = CornerRadius(barH / 2f, barH / 2f),
            )
            drawRoundRect(
                color = accent,
                topLeft = Offset(r, cy - barH / 2f),
                size = Size(usable * shown, barH),
                cornerRadius = CornerRadius(barH / 2f, barH / 2f),
            )
            drawCircle(
                color = accent,
                radius = r,
                center = Offset(r + usable * shown, cy),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = r * 0.42f,
                center = Offset(r + usable * shown, cy),
            )
        }
    }
}

package com.glassplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.ClipData
import android.content.ClipboardManager
import com.glassplayer.cast.CastDiagnostics
import com.glassplayer.cast.CastDevice
import com.glassplayer.cast.CastManager
import com.glassplayer.cast.CastState
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.icons.SfIcon

/**
 * 投屏面板：搜索设备 → 选中 → 投屏中控制。
 *
 * 内容直接放进 PlayerSheet 的滚动容器，所以这里不再自己套滚动。
 */
@Composable
fun CastPanel(
    castState: CastState,
    onCast: (CastDevice) -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onDisconnect: () -> Unit,
) {
    val context = LocalContext.current
    // 设备列表和扫描状态都来自 CastManager 的后台协程，重组/弹层动画都不会中断发现
    val devices by CastManager.discovered.collectAsState()
    val scanning by CastManager.scanning.collectAsState()
    var scanRound by remember { mutableStateOf(0) }
    var copied by remember { mutableStateOf(false) }

    // 每次打开面板扫一轮；点「重新搜索」也靠 scanRound 触发。
    // 这里只负责「触发」，不 await 结果，所以本作用域即使被取消也不影响后台发现。
    LaunchedEffect(scanRound) {
        copied = false
        CastManager.startDiscovery(context)
    }
    val hint = if (!scanning && devices.isEmpty()) {
        "没找到可投屏的设备。确认电视和手机连的是同一个 Wi-Fi，并打开电视的投屏/多屏互动。"
    } else {
        null
    }

    if (castState.casting || castState.connecting) {
        CastingCard(
            state = castState,
            onTogglePlay = onTogglePlay,
            onSeek = onSeek,
            onDisconnect = onDisconnect,
        )
    }

    val message = castState.message ?: hint
    if (message != null && !castState.casting) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = GlasmioTheme.colors.content.copy(alpha = 0.6f),
            modifier = Modifier.padding(vertical = 10.dp),
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (scanning) "正在搜索局域网设备…" else "可用设备 ${devices.size} 台",
            style = MaterialTheme.typography.bodySmall,
            color = GlasmioTheme.colors.content.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(50))
                .clickable(enabled = !scanning) { scanRound++ },
            contentAlignment = Alignment.Center,
        ) {
            SfIcon(
                "arrow.clockwise",
                "重新搜索",
                size = 15.dp,
                tint = GlasmioTheme.colors.content.copy(alpha = if (scanning) 0.28f else 0.65f),
            )
        }
    }

    devices.forEach { device ->
        DeviceRow(
            device = device,
            active = castState.device?.uuid == device.uuid && castState.casting,
            enabled = !castState.connecting,
            onClick = { onCast(device) },
        )
    }

    if (!scanning && devices.isEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = "试试：电视设置里打开「DLNA / 投屏」；小米、华为、创维等品牌一般默认开启。",
            style = MaterialTheme.typography.bodySmall,
            color = GlasmioTheme.colors.content.copy(alpha = 0.45f),
        )
    }

    Spacer(Modifier.height(10.dp))
    // 诊断入口始终可见：投屏被拒、连上后异常等情况都要能复制日志，不能只在空列表时出现
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GlasmioTheme.colors.content.copy(alpha = 0.08f))
            .clickable {
                val report = CastDiagnostics.report(context, CastManager.state.value)
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    ClipData.newPlainText("GlassPlayer 投屏诊断", report),
                )
                copied = true
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SfIcon(
            if (copied) "checkmark.circle.fill" else "doc.on.doc",
            "复制诊断",
            size = 16.dp,
            tint = GlasmioTheme.colors.content.copy(alpha = 0.8f),
        )
        Text(
            text = if (copied) "已复制，直接粘贴发出来即可" else "复制投屏诊断信息",
            style = MaterialTheme.typography.bodySmall,
            color = GlasmioTheme.colors.content.copy(alpha = 0.85f),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun DeviceRow(
    device: CastDevice,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SfIcon(
            if (active) "tv.fill" else "tv.and.mediabox",
            "设备",
            size = 20.dp,
            tint = GlasmioTheme.colors.content.copy(alpha = if (active) 1f else 0.75f),
        )
        Column(
            modifier = Modifier.weight(1f).padding(start = 10.dp),
        ) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.bodyLarge,
                color = GlasmioTheme.colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = buildString {
                if (active) append("正在投屏")
                if (device.model.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(device.model)
                }
            }
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = GlasmioTheme.colors.content.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (active) {
            SfIcon("checkmark.circle.fill", "已连接", size = 16.dp)
        }
    }
}

/** 投屏中：设备名 + 播放状态 + 可拖进度 + 暂停 / 断开。 */
@Composable
private fun CastingCard(
    state: CastState,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onDisconnect: () -> Unit,
) {
    val content = GlasmioTheme.colors.content
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(content.copy(alpha = 0.06f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = state.device?.name ?: "连接中…",
            style = MaterialTheme.typography.titleSmall,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = when {
                state.connecting -> "正在连接电视…"
                state.playing -> "电视端播放中"
                else -> "电视端已暂停"
            },
            style = MaterialTheme.typography.labelSmall,
            color = content.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 2.dp),
        )

        if (state.casting) {
            val total = state.durationMs.coerceAtLeast(1L)
            val fraction = (state.positionMs.toFloat() / total).coerceIn(0f, 1f)
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(content.copy(alpha = 0.18f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .background(content.copy(alpha = 0.75f)),
                )
            }
            Text(
                text = "${formatClock(state.positionMs)} / ${formatClock(state.durationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = content.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 4.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CastAction(
                    icon = if (state.playing) "pause.circle.fill" else "play.circle.fill",
                    label = if (state.playing) "暂停" else "播放",
                    onClick = onTogglePlay,
                )
                CastAction(
                    icon = "gobackward.10",
                    label = "后退 10 秒",
                    onClick = { onSeek((state.positionMs - 10_000).coerceAtLeast(0)) },
                )
                CastAction(
                    icon = "goforward.10",
                    label = "前进 10 秒",
                    onClick = { onSeek(state.positionMs + 10_000) },
                )
                CastAction(
                    icon = "xmark.circle",
                    label = "断开投屏",
                    onClick = onDisconnect,
                )
            }
        }
    }
}

@Composable
private fun CastAction(icon: String, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(50))
            .background(GlasmioTheme.colors.content.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        SfIcon(icon, label, size = 16.dp, tint = GlasmioTheme.colors.content.copy(alpha = 0.85f))
    }
}

private fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

package com.glassplayer.cast

import android.content.Context
import com.glassplayer.data.DebugLog

/**
 * 投屏排障报告：把「网络环境 + 当前状态 + 投屏相关日志」打包成一段纯文本。
 *
 * 用户搜不到设备时点一下「复制诊断」就能整段发过来，不需要教他去找文件。
 */
object CastDiagnostics {

    fun report(context: Context, state: CastState): String = buildString {
        appendLine("GlassPlayer 投屏诊断")
        appendLine("网络：${CastNet.summary(context)}")
        appendLine(
            "状态：${when {
                state.connecting -> "连接中"
                state.casting -> "投屏中（${if (state.playing) "播放" else "暂停"}）"
                else -> "未投屏"
            }}",
        )
        state.device?.let { device ->
            appendLine("设备：${device.name}")
            appendLine("  型号=${device.model.ifBlank { "未知" }}")
            appendLine("  controlURL=${device.avControlUrl}")
            appendLine("  UDN=${device.uuid.ifBlank { "无" }}")
        }
        state.message?.let { appendLine("提示：$it") }
        appendLine("---- 投屏日志 ----")
        val castLines = DebugLog.snapshot()
            .lineSequence()
            .filter { it.contains("[cast]") }
            .toList()
        appendLine(if (castLines.isEmpty()) "（本次没有投屏日志）" else castLines.joinToString("\n"))
    }
}

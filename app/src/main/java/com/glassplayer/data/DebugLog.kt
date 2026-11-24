package com.glassplayer.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量调试日志：只保留最近 400 行，全部在内存里，不落盘、不上传。
 * 网盘接口 / 播放器出错时点「复制日志」就能拿到完整上下文。
 */
object DebugLog {

    @Volatile private var crashRecorderInstalled = false

    /** 把未捕获异常落到 filesDir/last_crash.txt，下次启动时读进日志，方便定位闪退。 */
    fun installCrashRecorder(context: Context) {
        if (crashRecorderInstalled) return
        crashRecorderInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(context.filesDir, "last_crash.txt").writeText(
                    buildString {
                        appendLine("thread=${thread.name}")
                        appendLine(error.toString())
                        error.stackTraceToString().lineSequence().take(80).forEach { appendLine(it) }
                    },
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun restoreLastCrash(context: Context) {
        val file = File(context.filesDir, "last_crash.txt")
        if (!file.exists()) return
        runCatching {
            file.readText().lineSequence().take(40).forEach { line ->
                record("crash", line)
            }
            file.delete()
        }
    }

    private const val MAX_LINES = 400
    private val lines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun record(tag: String, message: String) {
        val line = "${timeFormat.format(Date())} [$tag] $message"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n").ifBlank { "（暂无日志）" }

    @Synchronized
    fun clear() = lines.clear()
}

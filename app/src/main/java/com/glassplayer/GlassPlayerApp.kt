package com.glassplayer

import android.app.Application
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 捕获未处理异常并落盘，便于真机问题定位。
 * 日志位置：Android/data/com.glassplayer/files/logs/last_crash.txt
 */
class GlassPlayerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val dir = (getExternalFilesDir(null) ?: filesDir).let { File(it, "logs") }
                dir.mkdirs()
                val entry = buildString {
                    appendLine("time=" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                    appendLine("thread=" + thread.name)
                    appendLine(Log.getStackTraceString(throwable))
                }
                // 最新一条单独存一份，供自检面板直接展示
                File(dir, "last_crash.txt").writeText(entry)
                // 历史最多留 HISTORY_LIMIT 条，新的排在最前，便于确认是否只有一种崩溃原因
                val history = File(dir, "crash_history.txt")
                val old = runCatching { if (history.exists()) history.readText() else "" }.getOrDefault("")
                val entries = buildList {
                    add(entry.trim())
                    old.split(SEPARATOR).map { it.trim() }.filter { it.isNotBlank() }.forEach { add(it) }
                }.take(HISTORY_LIMIT)
                history.writeText(entries.joinToString(SEPARATOR))
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val HISTORY_LIMIT = 5
        const val SEPARATOR = "\n@@@\n"
    }
}

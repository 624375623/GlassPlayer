package com.glassplayer.data

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.WindowManager

/**
 * 实验性全局刷新率控制。
 *
 * 做法是给当前窗口挑一个 DisplayMode（`preferredDisplayModeId`），让系统按这个模式的
 * 刷新率合成；不写系统设置、不需要权限，退出应用即恢复。
 *
 * 注意三点：
 * 1) 只能挑设备 `display.supportedModes` 里真实存在的模式，选不到就保持跟随系统，
 *    绝不能凭想象设一个 90/120，否则窗口会黑屏或回落到最低刷新率；
 * 2) 同一刷新率可能有多种分辨率 / 色深的模式，优先选和当前分辨率一致的，避免画面被缩放；
 * 3) Android 9 兼容版同样走这条路径（API 23 起就有 preferredDisplayModeId）。
 */
object RefreshRate {

    private const val TAG = "GlassPlayer"

    /** mode: 0 跟随系统，1 强制 90Hz，2 强制 120Hz。返回实际生效的刷新率（0 = 未改动）。 */
    fun apply(activity: Activity?, mode: Int): Float {
        val window = activity?.window ?: return 0f
        val attrs = window.attributes
        if (mode <= 0) {
            if (attrs.preferredDisplayModeId != 0) {
                attrs.preferredDisplayModeId = 0
                window.attributes = attrs
            }
            return 0f
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return 0f
        val display = currentDisplay(activity) ?: return 0f
        val target = if (mode == 1) 90f else 120f
        val current = display.mode
        val modes = runCatching { display.supportedModes }.getOrNull()?.toList().orEmpty()
        if (modes.isEmpty()) return 0f

        // 先按「刷新率足够接近目标」筛一遍，再按分辨率一致 → 刷新率接近 → 色深 排序
        val candidates = modes.filter { kotlin.math.abs(it.refreshRate - target) <= 3f }
        val best = candidates
            .sortedWith(
                compareByDescending<android.view.Display.Mode> {
                    it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
                }
                    .thenBy { kotlin.math.abs(it.refreshRate - target) }
                    .thenByDescending { it.physicalWidth * it.physicalHeight },
            )
            .firstOrNull()

        val modeId = best?.modeId ?: run {
            Log.w(TAG, "设备没有接近 ${target}Hz 的显示模式，保持跟随系统")
            return 0f
        }
        val changed = attrs.preferredDisplayModeId != modeId
        if (changed) {
            attrs.preferredDisplayModeId = modeId
            window.attributes = attrs
        }
        Log.i(TAG, "刷新率目标=${target}Hz 实际=${best.refreshRate}Hz mode=${best.modeId}")
        return best.refreshRate
    }

    /** 当前设备支持的刷新率取值，用于设置页提示。 */
    fun supportedRates(activity: Activity?): List<Int> {
        val host = activity ?: return emptyList()
        val display = currentDisplay(host) ?: return emptyList()
        return runCatching { display.supportedModes }.getOrNull()
            ?.map { it.refreshRate.roundToIntSafely() }
            ?.distinct()
            ?.sorted()
            .orEmpty()
    }

    @Suppress("DEPRECATION")
    private fun currentDisplay(activity: Activity): android.view.Display? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            activity.windowManager.defaultDisplay
        }
    }.getOrNull()

    private fun Float.roundToIntSafely(): Int = kotlin.math.round(this).toInt()
}

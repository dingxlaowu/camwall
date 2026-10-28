package com.example.camwall

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max

/**
 * 只调本应用窗口的亮度（不改系统设置，不需要任何权限）。
 *
 * 触摸 -> 在 rampUpSec 内升到 highPct
 *      -> 保持 holdSec
 *      -> 在 fadeSec 内逐渐降到 lowPct
 * 期间再次触摸会重新计时。
 *
 * 用自己的计时循环而不是 ValueAnimator，
 * 这样即使手机在开发者选项里关掉了"动画缩放"，渐变也照常工作。
 */
class BrightnessController(
    private val activity: Activity,
    private val settings: () -> Settings,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var current = -1f
    private var from = 0f
    private var to = 0f
    private var t0 = 0L
    private var dur = 1L
    private var holdCount = 0

    private val high: Float get() = (settings().highPct / 100f).coerceIn(0.05f, 1f)

    // 0 在部分机型上表示"关背光"，所以最低给 0.004（约 1/255）
    private val low: Float get() = (settings().lowPct / 100f).coerceIn(0.004f, high)

    private val rampMs: Long get() = (settings().rampUpSec * 1000).toLong().coerceAtLeast(0)
    private val holdMs: Long get() = (settings().holdSec * 1000).toLong().coerceAtLeast(0)
    private val fadeMs: Long get() = (settings().fadeSec * 1000).toLong().coerceAtLeast(0)

    /** 当前是否处于"暗屏"状态 */
    fun isDim(): Boolean = current >= 0 && current < low + (high - low) * 0.25f

    /** 有触摸 / 按键 */
    fun onUserTouch() {
        handler.removeCallbacks(startFade)
        animateTo(high, rampMs)
        if (holdCount == 0) handler.postDelayed(startFade, rampMs + holdMs)
    }

    /** 弹窗编辑时保持常亮，关闭后恢复计时 */
    fun hold(on: Boolean) {
        holdCount = max(0, holdCount + if (on) 1 else -1)
        if (on) {
            handler.removeCallbacks(startFade)
            animateTo(high, rampMs)
        } else if (holdCount == 0) {
            onUserTouch()
        }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
    }

    private val startFade = Runnable { animateTo(low, fadeMs) }

    private fun animateTo(target: Float, fullDurationMs: Long) {
        handler.removeCallbacks(step)
        val start = if (current < 0) low else current
        val range = (high - low).coerceAtLeast(0.01f)
        from = start
        to = target
        t0 = SystemClock.uptimeMillis()
        // 按距离等比例缩短时间：已经很亮时再碰一下，不用再等满 rampUpSec
        dur = (fullDurationMs * (abs(target - start) / range)).toLong().coerceAtLeast(1)
        handler.post(step)
    }

    private val step = object : Runnable {
        override fun run() {
            val t = ((SystemClock.uptimeMillis() - t0).toFloat() / dur).coerceIn(0f, 1f)
            apply(from + (to - from) * t)
            if (t < 1f) handler.postDelayed(this, 40)
        }
    }

    private fun apply(v: Float) {
        if (abs(v - current) < 0.0005f) return
        current = v
        val lp = activity.window.attributes
        lp.screenBrightness = v
        activity.window.attributes = lp
    }
}

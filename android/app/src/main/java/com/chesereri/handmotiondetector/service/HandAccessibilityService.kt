package com.chesereri.handmotiondetector.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.chesereri.handmotiondetector.vision.Gesture

/**
 * 无障碍服务：把识别到的手势转换为全局操作。
 *
 * 滑动使用 dispatchGesture 在屏幕中央模拟一次滑动；握拳映射为返回桌面。
 * 需要用户在系统设置中手动开启。
 */
class HandAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastDispatchAt = 0L
    private var lastDispatchedGesture = Gesture.NONE

    companion object {
        @Volatile
        private var instance: HandAccessibilityService? = null

        val isConnected: Boolean
            get() = instance != null

        /**
         * 返回 false 表示无障碍服务未启用；true 表示已投递（冷却期内也会丢弃并返回 true）。
         *
         * @param appPackage 手指手势要打开的应用包名。
         */
        fun dispatch(gesture: Gesture, appPackage: String? = null): Boolean {
            val service = instance ?: return false
            service.mainHandler.post { service.perform(gesture, appPackage) }
            return true
        }

        private const val SWIPE_DURATION_MS = 260L
        private const val DISPATCH_COOLDOWN_MS = 600L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun perform(gesture: Gesture, appPackage: String?) {
        if (gesture == Gesture.NONE) return
        val now = SystemClock.elapsedRealtime()
        // 只抑制短时间内重复的同一手势，避免一次动作被注入多次。
        if (gesture == lastDispatchedGesture && now - lastDispatchAt < DISPATCH_COOLDOWN_MS) return

        val handled = when (gesture) {
            Gesture.SWIPE_UP -> swipe(0f, 1f, 0f, -1f)
            Gesture.SWIPE_DOWN -> swipe(0f, -1f, 0f, 1f)
            Gesture.GRAB -> performGlobalAction(GLOBAL_ACTION_HOME)
            Gesture.FINGER_ONE, Gesture.FINGER_THREE, Gesture.FINGER_FOUR -> openApp(appPackage)
            Gesture.PEACE, Gesture.NONE -> false
        }
        if (handled) {
            lastDispatchAt = now
            lastDispatchedGesture = gesture
        }
    }

    private fun openApp(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun swipe(fromDx: Float, fromDy: Float, toDx: Float, toDy: Float): Boolean {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val halfX = width * 0.30f
        val halfY = height * 0.25f

        val startX = (centerX + fromDx * halfX).coerceIn(2f, width - 2f)
        val startY = (centerY + fromDy * halfY).coerceIn(2f, height - 2f)
        val endX = (centerX + toDx * halfX).coerceIn(2f, width - 2f)
        val endY = (centerY + toDy * halfY).coerceIn(2f, height - 2f)

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION_MS)
        val description = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(description, null, null)
    }
}

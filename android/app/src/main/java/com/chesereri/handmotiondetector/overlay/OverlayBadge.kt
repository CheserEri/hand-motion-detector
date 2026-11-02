package com.chesereri.handmotiondetector.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.chesereri.handmotiondetector.R
import com.chesereri.handmotiondetector.vision.Gesture
import com.chesereri.handmotiondetector.vision.GestureFrameResult

/** 可拖动的悬浮状态球，实时显示识别结果。 */
class OverlayBadge(
    context: Context,
    private val windowManager: WindowManager,
) : TextView(context) {

    val windowParams: WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(16)
        y = dp(120)
    }

    private var gestureLabel: String? = null
    private var gestureShownAt = 0L
    private var persistentStatus: String? = null
    private var warning: String? = null
    private var warningUntil = 0L

    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0

    init {
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setTextColor(TEXT)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(7), dp(12), dp(7))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(0xE60E141A.toInt())
            setStroke(dp(1), 0x665BE0A0)
        }
        text = context.getString(R.string.overlay_no_hand)
        setOnTouchListener { _, event -> handleTouch(event) }
    }

    fun update(result: GestureFrameResult) {
        if (persistentStatus != null) return

        val now = SystemClock.elapsedRealtime()
        val emitted = when (result.gesture) {
            Gesture.PEACE -> if (result.gesturesEnabled) "手势已开启" else "手势已关闭"
            else -> result.gesture.displayLabel()
        }
        if (emitted != null) {
            gestureLabel = emitted
            gestureShownAt = now
        }

        val showingGesture = gestureLabel != null && now - gestureShownAt < GESTURE_HOLD_MS
        val warningShown = warning != null && now < warningUntil
        text = when {
            warningShown -> warning
            showingGesture -> gestureLabel
            !result.gesturesEnabled -> "手势已关闭"
            result.handVisible -> result.direction.label
            else -> context.getString(R.string.overlay_no_hand)
        }
        setTextColor(
            when {
                warningShown -> WARNING
                showingGesture -> ACCENT
                !result.gesturesEnabled -> MUTED
                result.handVisible -> TEXT
                else -> MUTED
            },
        )
    }

    fun showWarning(message: String) {
        warning = message
        warningUntil = SystemClock.elapsedRealtime() + WARNING_HOLD_MS
        text = message
        setTextColor(WARNING)
    }

    /** 暂停、低电量等持续状态；传 null 恢复实时状态。 */
    fun setPersistentStatus(status: String?) {
        persistentStatus = status
        text = status ?: context.getString(R.string.overlay_no_hand)
        setTextColor(if (status != null) WARNING else MUTED)
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downX = windowParams.x
                downY = windowParams.y
            }

            MotionEvent.ACTION_MOVE -> {
                windowParams.x = (downX + (event.rawX - downRawX)).toInt()
                windowParams.y = (downY + (event.rawY - downRawY)).toInt()
                windowManager.updateViewLayout(this, windowParams)
            }
        }
        return true
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()

    private fun Gesture.displayLabel(): String? = when (this) {
        Gesture.SWIPE_UP -> "上滑"
        Gesture.SWIPE_DOWN -> "下滑"
        Gesture.GRAB -> "握拳"
        Gesture.FINGER_ONE -> "1 指"
        Gesture.FINGER_THREE -> "3 指"
        Gesture.FINGER_FOUR -> "4 指"
        Gesture.PEACE, Gesture.NONE -> null
    }

    private companion object {
        const val GESTURE_HOLD_MS = 700L
        const val WARNING_HOLD_MS = 1600L

        val ACCENT = 0xFF5BE0A0.toInt()
        val TEXT = 0xFFE6EDF3.toInt()
        val MUTED = 0xFF8A99A8.toInt()
        val WARNING = 0xFFFF7B72.toInt()
    }
}

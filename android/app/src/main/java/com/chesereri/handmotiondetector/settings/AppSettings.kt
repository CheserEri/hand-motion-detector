package com.chesereri.handmotiondetector.settings

import android.content.Context

/** 用户可调设置，持久化在 SharedPreferences。 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("gesture_settings", Context.MODE_PRIVATE)

    var overlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY, true)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY, value).apply()

    var invertVertical: Boolean
        get() = prefs.getBoolean(KEY_INVERT_VERTICAL, false)
        set(value) = prefs.edit().putBoolean(KEY_INVERT_VERTICAL, value).apply()

    /** 伸出 1 / 3 / 4 根手指时要打开的应用包名，空字符串表示未设置。 */
    var appForFingerOne: String
        get() = prefs.getString(KEY_APP_FINGER_ONE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_APP_FINGER_ONE, value).apply()

    var appForFingerThree: String
        get() = prefs.getString(KEY_APP_FINGER_THREE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_APP_FINGER_THREE, value).apply()

    var appForFingerFour: String
        get() = prefs.getString(KEY_APP_FINGER_FOUR, "") ?: ""
        set(value) = prefs.edit().putString(KEY_APP_FINGER_FOUR, value).apply()

    private companion object {
        const val KEY_OVERLAY = "overlay_enabled"
        const val KEY_INVERT_VERTICAL = "invert_vertical"
        const val KEY_APP_FINGER_ONE = "app_finger_one"
        const val KEY_APP_FINGER_THREE = "app_finger_three"
        const val KEY_APP_FINGER_FOUR = "app_finger_four"
    }
}

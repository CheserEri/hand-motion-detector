package com.chesereri.handmotiondetector.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.WindowManager
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.chesereri.handmotiondetector.BuildConfig
import com.chesereri.handmotiondetector.MainActivity
import com.chesereri.handmotiondetector.R
import com.chesereri.handmotiondetector.overlay.OverlayBadge
import com.chesereri.handmotiondetector.settings.AppSettings
import com.chesereri.handmotiondetector.vision.Gesture
import com.chesereri.handmotiondetector.vision.GestureEngine
import com.chesereri.handmotiondetector.vision.HandLandmarkerHelper
import com.chesereri.handmotiondetector.vision.LandmarkPoint
import com.chesereri.handmotiondetector.vision.toUprightBitmap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 前台摄像头服务：CameraX 低分辨率采集 → MediaPipe 手部关键点 → 手势引擎 →
 * 无障碍服务全局注入。
 *
 * 低功耗策略：320x240 + 约 15fps（无手时降到 8fps）+ 仅保留最新帧 +
 * 不渲染预览 + 灭屏停止 + 电量低于 15% 停止。
 */
class GestureCameraService : LifecycleService() {

    companion object {
        private const val TAG = "GestureCameraService"
        private const val CHANNEL_ID = "gesture_control"
        private const val NOTIFICATION_ID = 1001
        private const val ACTIVE_INTERVAL_MS = 66L
        private const val IDLE_INTERVAL_MS = 125L
        private const val LOW_BATTERY_LEVEL = 15

        const val ACTION_STOP = "com.chesereri.handmotiondetector.action.STOP"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        private var current: GestureCameraService? = null

        fun refreshSettings() {
            current?.applySettings()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var settings: AppSettings
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var handLandmarker: HandLandmarkerHelper
    private val gestureEngine = GestureEngine()

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var overlay: OverlayBadge? = null
    private var isFrontCamera = true
    private var screenOn = true
    private var batteryLow = false

    @Volatile
    private var lastHandAt = 0L

    private var lastInferenceAt = 0L
    private var lastWarningAt = 0L
    private var formatLogged = false
    private var receiversRegistered = false

    private val mediaPipeListener = object : HandLandmarkerHelper.Listener {
        override fun onLandmarks(landmarks: List<LandmarkPoint>, confidence: Float, timestampMs: Long) {
            val result = gestureEngine.process(landmarks.ifEmpty { null }, confidence, timestampMs)
            if (result.handVisible) lastHandAt = timestampMs

            if (BuildConfig.DEBUG) {
                val cx = if (landmarks.size == 21) (landmarks[0].x + landmarks[9].x) / 2f else -1f
                val cy = if (landmarks.size == 21) (landmarks[0].y + landmarks[9].y) / 2f else -1f
                val tipX = if (landmarks.size == 21) landmarks[8].x else -1f
                val tipY = if (landmarks.size == 21) landmarks[8].y else -1f
                Log.d(
                    TAG,
                    "vis=${if (result.handVisible) 1 else 0} " +
                        "c=${(cx * 1000).toInt()},${(cy * 1000).toInt()} " +
                        "tip=${(tipX * 1000).toInt()},${(tipY * 1000).toInt()} " +
                        "dir=${result.direction} " +
                        "spd=${(result.speed * 100).toInt() / 100f} " +
                        "curl=${(result.curl * 100).toInt() / 100f} " +
                        "n=${result.fingerCount} " +
                        "on=${if (result.gesturesEnabled) 1 else 0} " +
                        "g=${result.gesture}",
                )
            }

            mainHandler.post { overlay?.update(result) }

            // 比 ✌ 只切换开关，不向系统注入动作。
            if (result.gesture != Gesture.NONE && result.gesture != Gesture.PEACE) {
                val appPackage = when (result.gesture) {
                    Gesture.FINGER_ONE -> settings.appForFingerOne
                    Gesture.FINGER_THREE -> settings.appForFingerThree
                    Gesture.FINGER_FOUR -> settings.appForFingerFour
                    else -> null
                }
                if (appPackage != null && appPackage.isEmpty()) {
                    mainHandler.post { overlay?.showWarning(getString(R.string.overlay_no_app)) }
                    return
                }
                val dispatched = HandAccessibilityService.dispatch(result.gesture, appPackage)
                if (BuildConfig.DEBUG) Log.d(TAG, "dispatch ${result.gesture} ok=$dispatched")
                if (!dispatched) {
                    mainHandler.post { overlay?.showWarning(getString(R.string.overlay_no_accessibility)) }
                }
            }
        }

        override fun onError(message: String) {
            Log.w(TAG, message)
            val now = SystemClock.elapsedRealtime()
            if (now - lastWarningAt < 3000L) return
            lastWarningAt = now
            mainHandler.post {
                Toast.makeText(this@GestureCameraService, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    stopCamera()
                    postOverlayStatus(getString(R.string.overlay_paused))
                }

                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    startCamera()
                }
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val low = level in 0..LOW_BATTERY_LEVEL && !charging
            if (low == batteryLow) return

            batteryLow = low
            if (low) {
                stopCamera()
                postOverlayStatus(getString(R.string.overlay_low_battery))
            } else if (screenOn) {
                startCamera()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        handLandmarker = HandLandmarkerHelper(this, mediaPipeListener)

        startInForeground()
        registerReceivers()
        applySettings()
        addOverlayIfAllowed()

        current = this
        isRunning = true

        cameraExecutor.execute {
            val ready = handLandmarker.setup()
            if (!ready) {
                mainHandler.post {
                    Toast.makeText(
                        this,
                        "模型加载失败，请确认 assets/hand_landmarker.task 已放入工程",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        startCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        current = null
        unregisterReceivers()
        stopCamera()
        handLandmarker.close()
        cameraExecutor.shutdown()
        removeOverlay()
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, GestureCameraService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gesture)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun startCamera() {
        if (!screenOn || batteryLow || !::handLandmarker.isInitialized) return

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (!screenOn || batteryLow) return@addListener
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                @Suppress("DEPRECATION")
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(320, 240))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                analysis.setAnalyzer(cameraExecutor) { proxy -> analyze(proxy) }
                imageAnalysis = analysis

                provider.unbindAll()
                val hasFront = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                isFrontCamera = hasFront
                val selector = if (hasFront) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                provider.bindToLifecycle(this, selector, analysis)
                mainHandler.post { overlay?.setPersistentStatus(null) }
            } catch (t: Throwable) {
                Log.e(TAG, "camera bind failed", t)
                postOverlayStatus(getString(R.string.overlay_paused))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        lastInferenceAt = 0L
        lastHandAt = 0L
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            if (!screenOn || batteryLow) return

            val now = SystemClock.elapsedRealtime()
            val interval = if (now - lastHandAt <= 1000L) ACTIVE_INTERVAL_MS else IDLE_INTERVAL_MS
            if (now - lastInferenceAt < interval) return
            lastInferenceAt = now

            val bitmap = proxy.toUprightBitmap(isFrontCamera) ?: return
            if (BuildConfig.DEBUG && !formatLogged) {
                formatLogged = true
                Log.d(
                    TAG,
                    "frame ${proxy.width}x${proxy.height} rot=${proxy.imageInfo.rotationDegrees} " +
                        "mirror=$isFrontCamera",
                )
            }
            handLandmarker.detect(bitmap, now)
        } catch (t: Throwable) {
            Log.e(TAG, "analyze frame failed", t)
        } finally {
            proxy.close()
        }
    }

    private fun applySettings() {
        gestureEngine.updateConfig(
            GestureEngine.Config(
                invertVertical = settings.invertVertical,
            )
        )
        if (settings.overlayEnabled) {
            if (overlay == null) addOverlayIfAllowed()
        } else {
            removeOverlay()
        }
    }

    private fun addOverlayIfAllowed() {
        if (overlay != null) return
        if (!settings.overlayEnabled || !Settings.canDrawOverlays(this)) return
        val windowManager = getSystemService(WindowManager::class.java)
        val badge = OverlayBadge(this, windowManager)
        try {
            windowManager.addView(badge, badge.windowParams)
            overlay = badge
        } catch (t: Throwable) {
            Log.w(TAG, "add overlay failed", t)
        }
    }

    private fun removeOverlay() {
        val badge = overlay ?: return
        overlay = null
        try {
            getSystemService(WindowManager::class.java).removeView(badge)
        } catch (t: Throwable) {
            Log.w(TAG, "remove overlay failed", t)
        }
    }

    private fun postOverlayStatus(status: String) {
        mainHandler.post { overlay?.setPersistentStatus(status) }
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            screenFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            batteryFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiversRegistered = true
    }

    private fun unregisterReceivers() {
        if (!receiversRegistered) return
        receiversRegistered = false
        try {
            unregisterReceiver(screenReceiver)
            unregisterReceiver(batteryReceiver)
        } catch (t: Throwable) {
            Log.w(TAG, "unregister receivers failed", t)
        }
    }
}

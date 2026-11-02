package com.chesereri.handmotiondetector.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/**
 * MediaPipe HandLandmarker 封装。
 *
 * 使用 LIVE_STREAM 模式：只保留最新结果，避免帧队列堆积；优先 GPU 委托，
 * 失败时自动回退 CPU。
 */
class HandLandmarkerHelper(
    private val context: Context,
    private val listener: Listener,
) {

    interface Listener {
        fun onLandmarks(landmarks: List<LandmarkPoint>, confidence: Float, timestampMs: Long)

        fun onError(message: String)
    }

    private var landmarker: HandLandmarker? = null
    private var lastTimestampMs = 0L

    @Volatile
    private var closed = false

    /** 在后台线程调用，避免阻塞主线程。返回是否初始化成功。 */
    fun setup(): Boolean {
        if (landmarker != null) return true
        landmarker = try {
            create(Delegate.GPU)
        } catch (gpuError: Throwable) {
            Log.w(TAG, "GPU delegate unavailable, falling back to CPU", gpuError)
            try {
                create(Delegate.CPU)
            } catch (cpuError: Throwable) {
                Log.e(TAG, "MediaPipe init failed", cpuError)
                listener.onError("MediaPipe 初始化失败：${cpuError.message}")
                null
            }
        }
        return landmarker != null
    }

    fun detect(bitmap: Bitmap, timestampMs: Long) {
        val detector = landmarker ?: return
        if (closed || timestampMs <= lastTimestampMs) return
        lastTimestampMs = timestampMs
        try {
            detector.detectAsync(BitmapImageBuilder(bitmap).build(), timestampMs)
        } catch (t: Throwable) {
            listener.onError(t.message ?: "MediaPipe 推理失败")
        }
    }

    fun close() {
        closed = true
        try {
            landmarker?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close landmarker failed", t)
        }
        landmarker = null
    }

    private fun create(delegate: Delegate): HandLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(delegate)
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.7f)
            .setMinHandPresenceConfidence(0.7f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> dispatchResult(result) }
            .setErrorListener { error -> listener.onError(error.message ?: "MediaPipe 推理错误") }
            .build()

        return HandLandmarker.createFromOptions(context, options)
    }

    private fun dispatchResult(result: HandLandmarkerResult) {
        if (closed) return
        val hand = result.landmarks().firstOrNull()
        if (hand == null) {
            listener.onLandmarks(emptyList(), 0f, result.timestampMs())
            return
        }
        val landmarks = hand.map { LandmarkPoint(it.x(), it.y()) }
        val confidence = result.handednesses()
            .flatten()
            .maxOfOrNull { it.score() } ?: 1f
        listener.onLandmarks(landmarks, confidence, result.timestampMs())
    }

    private companion object {
        const val TAG = "HandLandmarker"
        const val MODEL_ASSET = "hand_landmarker.task"
    }
}

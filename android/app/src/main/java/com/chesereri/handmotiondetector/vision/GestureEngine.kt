package com.chesereri.handmotiondetector.vision

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

/** MediaPipe 归一化坐标点（0..1）。 */
data class LandmarkPoint(val x: Float, val y: Float)

/** 可被识别并注入系统的手势。 */
enum class Gesture {
    NONE,
    SWIPE_UP,
    SWIPE_DOWN,
    /** 握拳，用于返回桌面。 */
    GRAB,
    /** 伸出 1 / 3 / 4 根手指，用于打开用户指定的应用。 */
    FINGER_ONE,
    FINGER_THREE,
    FINGER_FOUR,
    /** 比 ✌：切换其他手势的启用状态，本身不注入系统动作。 */
    PEACE,
}

/** 用于悬浮窗展示的实时运动方向：只关心上下，左右不参与识别。 */
enum class HandDirection(val label: String) {
    NONE("静止"),
    UP("上"),
    DOWN("下"),
}

data class GestureFrameResult(
    val gesture: Gesture = Gesture.NONE,
    val handVisible: Boolean = false,
    val direction: HandDirection = HandDirection.NONE,
    /** 手长/秒，归一化速度。 */
    val speed: Float = 0f,
    /** 0=完全张开，1=完全握拳。 */
    val curl: Float = 0f,
    val confidence: Float = 0f,
    /** 四指伸直数量，-1 表示有手指处于中间态；用于调试与悬浮窗提示。 */
    val fingerCount: Int = -1,
    /** 其他手势当前是否启用（由比 ✌ 切换）。 */
    val gesturesEnabled: Boolean = true,
)

/**
 * 纯 Kotlin 手势识别引擎：不依赖 Android SDK，便于单元测试。
 *
 * 设计目标：低功耗（每帧只做轻量运算）、高识别率（手尺度归一化 + One-Euro 滤波 +
 * 指尖竖直位移判定 + 手势切割，避免一次动作被连续识别多次）。
 */
class GestureEngine(config: Config = Config()) {

    data class Config(
        val swipeMinDurationMs: Long = 90L,
        /** 停稳判定窗口：一次手势后手部需在该窗口内基本静止才能重新武装。 */
        val swipeSettleWindowMs: Long = 250L,
        /** 停稳判定：窗口内累计移动路程小于该手长比例（看总路程，避免"来回晃过零点"被误判为静止）。 */
        val swipeSettlePathDistance: Float = 0.25f,
        /** 手势触发后的最短静默时间，与停稳判定共同构成手势切割。 */
        val gestureRearmDelayMs: Long = 450L,
        /** 指尖动作的搜索窗口。 */
        val fingerSwipeWindowMs: Long = 500L,
        /** 一次有效挥动允许的最长时间，超过视为拖拽而不是快速挥动。 */
        val fingerSwipeMaxStrokeMs: Long = 400L,
        /** 食指尖最小竖直位移，单位为手长；只识别向上，可放宽一些。 */
        val fingerSwipeMinDisplacement: Float = 0.25f,
        /** 有效挥动的最小速度，单位手长/秒；缓慢移动不触发。 */
        val fingerSwipeMinSpeed: Float = 1.2f,
        /** 食指尖同向位移占比，用于排除来回摆动。 */
        val fingerSwipeConsistency: Float = 0.75f,
        /** 反向位移相对主挥动的上限：超过视为意图不明确，不触发。 */
        val fingerSwipeDominanceRatio: Float = 1.0f,
        /** 掌心需同向移动的最小位移，单位为手长，用于排除只动手指的抖动。 */
        val fingerSwipeMinPalmDisplacement: Float = 0.08f,
        /** 超过该弯曲度视为握拳，不参与上下滑判定。 */
        val fingerSwipeMaxCurl: Float = 0.5f,
        /** 握拳需连续保持的帧数。 */
        val grabStableFrames: Int = 5,
        /** 食指/中指弯曲角大于该值视为伸直，用于识别比 ✌。 */
        val peaceExtendedAngle: Float = 150f,
        /** 无名指/小指弯曲角小于该值视为收拢，用于识别比 ✌。 */
        val peaceCurledAngle: Float = 70f,
        /** ✌ 姿势需连续保持的帧数，避免抬手途中误切换。 */
        val peaceStableFrames: Int = 6,
        /** 手指伸直阈值，用于识别 1 / 3 / 4 根手指。 */
        val countExtendedAngle: Float = 135f,
        /** 手指收拢阈值；处于两者之间视为中间态，不参与计数。 */
        val countCurledAngle: Float = 95f,
        /** 手指数姿势需连续保持的帧数（约 0.27 秒）。 */
        val countStableFrames: Int = 4,
        /** 只有手基本停稳（手长/秒）时才识别握拳与手指数，避免挥动过程误触。 */
        val staticGestureMaxSpeed: Float = 0.9f,
        val minConfidence: Float = 0.5f,
        val invertVertical: Boolean = false,
    )

    @Volatile
    private var config: Config = config

    private data class TrackPoint(val t: Long, val x: Float, val y: Float)

    /** 指尖轨迹点，同时记录掌心位置与手指张开程度，用于区分手指滑动和握拳。 */
    private data class TipPoint(
        val t: Long,
        val x: Float,
        val y: Float,
        val palmX: Float,
        val palmY: Float,
        val curl: Float,
    )

    private val history = ArrayDeque<TrackPoint>()
    private val tipHistory = ArrayDeque<TipPoint>()
    private val filterX = OneEuroFilter()
    private val filterY = OneEuroFilter()
    private val filterTipX = OneEuroFilter()
    private val filterTipY = OneEuroFilter()

    private var smoothedScale = 0f
    private var missedFrames = 0

    /** 手势切割：触发一次后必须静默且停稳才会重新武装。 */
    private var armed = false
    private var rearmAt = 0L

    /** 握拳状态：发作一次后需张开手掌复位，避免握拳保持时重复触发。 */
    private var fistCommitted = false
    private var fistStableCount = 0

    /** 手指数状态：同一姿势只触发一次，改变姿势或收手后才能再次触发。 */
    private var countStableCount = 0
    private var countLatched: Int? = null
    private var countMissingCount = 0

    /** 其他手势总开关，由比 ✌ 切换。 */
    private var gesturesEnabled = true
    private var peaceStableCount = 0
    private var peaceLatched = false
    private var peaceMissingCount = 0

    fun updateConfig(newConfig: Config) {
        config = newConfig
    }

    fun reset() {
        resetTracking()
    }

    /**
     * 处理一帧手部关键点。
     *
     * @param landmarks 21 个归一化关键点；null 表示本帧没有检测到手。
     * @param confidence 手部存在置信度。
     * @param timestampMs 单调递增的毫秒时间戳（建议 elapsedRealtime）。
     */
    fun process(
        landmarks: List<LandmarkPoint>?,
        confidence: Float,
        timestampMs: Long,
    ): GestureFrameResult {
        val cfg = config

        if (landmarks == null || landmarks.size != HAND_LANDMARK_COUNT || confidence < cfg.minConfidence) {
            handleMissedFrame()
            return GestureFrameResult(handVisible = false, confidence = confidence)
        }

        val wrist = landmarks[0]
        val middleMcp = landmarks[9]
        val rawScale = hypot(middleMcp.x - wrist.x, middleMcp.y - wrist.y)
        if (rawScale < MIN_HAND_SCALE) {
            handleMissedFrame()
            return GestureFrameResult(handVisible = false, confidence = confidence)
        }

        missedFrames = 0
        smoothedScale = if (smoothedScale <= 0f) rawScale else smoothedScale * 0.8f + rawScale * 0.2f
        val scale = smoothedScale

        val windowStartX = ((wrist.x + middleMcp.x) / 2.0)
        val windowStartY = ((wrist.y + middleMcp.y) / 2.0)
        val cx = filterX.filter(windowStartX, timestampMs).toFloat()
        val cy = filterY.filter(windowStartY, timestampMs).toFloat()

        val bends = fingerBendAngles(landmarks)
        val avgBend = bends.average().toFloat()
        val curl = (1f - avgBend / 180f).coerceIn(0f, 1f)

        val tip = landmarks[8]
        val tipX = filterTipX.filter(tip.x.toDouble(), timestampMs).toFloat()
        val tipY = filterTipY.filter(tip.y.toDouble(), timestampMs).toFloat()

        history.addLast(TrackPoint(timestampMs, cx, cy))
        while (history.isNotEmpty() && timestampMs - history.first().t > HISTORY_MS) {
            history.removeFirst()
        }

        tipHistory.addLast(TipPoint(timestampMs, tipX, tipY, cx, cy, curl))
        while (tipHistory.isNotEmpty() && timestampMs - tipHistory.first().t > HISTORY_MS) {
            tipHistory.removeFirst()
        }

        val recent = history.filter { timestampMs - it.t <= DIRECTION_WINDOW_MS }
        val first = recent.firstOrNull()
        val dx = if (first != null) cx - first.x else 0f
        val dy = if (first != null) cy - first.y else 0f
        val durationMs = if (first != null) timestampMs - first.t else 0L
        val dtSec = durationMs / 1000f
        val displacement = hypot(dx, dy) / scale
        val speed = if (dtSec > 0.001f) displacement / dtSec else 0f
        // 只展示上下方向，左右位移统一视为静止，避免让人以为还在识别左右。
        val direction = when {
            displacement <= MIN_DIRECTION_DISPLACEMENT -> HandDirection.NONE
            abs(dy) < abs(dx) -> HandDirection.NONE
            dy < 0f -> HandDirection.UP
            else -> HandDirection.DOWN
        }

        var gesture = Gesture.NONE

        // 比 ✌ 是常驻开关手势，不受武装状态限制；稳定保持一次只切换一次。
        if (updatePeace(bends, cfg)) {
            gesturesEnabled = !gesturesEnabled
            resetStaticGestureState()
            gesture = Gesture.PEACE
            armed = false
            rearmAt = timestampMs + cfg.gestureRearmDelayMs
            clearMotionHistory(cx, cy, timestampMs)
        }

        if (gesture == Gesture.NONE && gesturesEnabled) {
            // 手势切割：一次触发后必须同时满足「最短静默」与「手部停稳」才重新武装。
            if (!armed && timestampMs >= rearmAt && isSettled(timestampMs, scale, cfg)) {
                armed = true
                clearMotionHistory(cx, cy, timestampMs)
            }

            // 只在武装状态下识别；未武装时手部回撤、收势的动作不会触发下一个手势。
            if (armed) {
                val swipe = detectFingerVerticalSwipe(scale, timestampMs, cfg)
                val pose = when {
                    swipe != null -> swipe
                    // 握住/伸手指时手必须基本停稳，避免挥动途中被判成手指数。
                    speed <= cfg.staticGestureMaxSpeed -> updateStaticPose(bends, landmarks, scale, cfg)
                    else -> {
                        resetStaticGestureProgress()
                        Gesture.NONE
                    }
                }
                gesture = swipe ?: pose
                if (gesture != Gesture.NONE) {
                    armed = false
                    rearmAt = timestampMs + cfg.gestureRearmDelayMs
                    clearMotionHistory(cx, cy, timestampMs)
                }
            }
        }

        return GestureFrameResult(
            gesture = gesture,
            handVisible = true,
            direction = direction,
            speed = speed,
            curl = curl,
            confidence = confidence,
            fingerCount = fingerCount(bends, cfg),
            gesturesEnabled = gesturesEnabled,
        )
    }

    private fun handleMissedFrame() {
        missedFrames++
        tipHistory.clear()
        // 连续丢手后复位：重新出现的手必须先停稳才会重新武装，避免"手刚入画"被当成滑动。
        if (missedFrames >= MISSED_FRAMES_RESET) {
            resetTracking()
        }
    }

    private fun resetTracking() {
        history.clear()
        tipHistory.clear()
        filterX.reset()
        filterY.reset()
        filterTipX.reset()
        filterTipY.reset()
        smoothedScale = 0f
        resetStaticGestureState()
        armed = false
        rearmAt = 0L
        peaceStableCount = 0
        peaceLatched = false
        peaceMissingCount = 0
    }

    private fun clearMotionHistory(cx: Float, cy: Float, timestampMs: Long) {
        history.clear()
        history.addLast(TrackPoint(timestampMs, cx, cy))
        tipHistory.clear()
        // 只清进度，保留已触发的手指数/握拳锁，防止保持同一姿势时重复触发。
        resetStaticGestureProgress()
    }

    private fun resetStaticGestureProgress() {
        fistStableCount = 0
        countStableCount = 0
    }

    private fun resetStaticGestureState() {
        fistCommitted = false
        countLatched = null
        countMissingCount = 0
        resetStaticGestureProgress()
    }

    /** 判断手部在最近一段时间内是否已停稳：看累计路程，来回摆动不算停稳。 */
    private fun isSettled(timestampMs: Long, scale: Float, cfg: Config): Boolean {
        val recent = history.filter { timestampMs - it.t <= cfg.swipeSettleWindowMs }
        if (recent.size < 2) return false
        if (recent.last().t - recent.first().t < cfg.swipeSettleWindowMs / 2) return false

        var path = 0f
        var prev = recent.first()
        for (point in recent.drop(1)) {
            path += hypot(point.x - prev.x, point.y - prev.y)
            prev = point
        }
        return path / scale < cfg.swipeSettlePathDistance
    }

    /**
     * 只识别向上的竖直挥动；向下挥动用于预备/收手，不参与识别。
     * 同时要求掌心同向移动、手指保持张开，避免握拳/伸指被误判成滑动。
     */
    private fun detectFingerVerticalSwipe(scale: Float, timestampMs: Long, cfg: Config): Gesture? {
        val window = tipHistory.filter { timestampMs - it.t <= cfg.fingerSwipeWindowMs }
        if (window.size < 3) return null
        if (timestampMs - window.first().t < cfg.swipeMinDurationMs) return null

        var bestDisplacement = 0f

        for (start in 0 until window.size - 1) {
            for (end in start + 1 until window.size) {
                val duration = window[end].t - window[start].t
                if (duration > cfg.fingerSwipeMaxStrokeMs) break
                if (duration < cfg.swipeMinDurationMs) continue

                val dy = window[end].y - window[start].y
                // 只接受向上挥动（归一化坐标 y 变小）。
                if (dy >= 0f) continue
                val ady = abs(dy)
                if (ady < cfg.fingerSwipeMinDisplacement * scale) continue

                // 握拳/伸指过程中的指尖位移不参与滑动判定。
                if (window[end].curl > cfg.fingerSwipeMaxCurl) continue

                val strokeSpeed = (ady / scale) / (duration / 1000f)
                if (strokeSpeed < cfg.fingerSwipeMinSpeed) continue

                if (!palmFollows(window, start, end, dy, scale, cfg)) continue
                if (!isVerticalRun(window, start, end, dy, cfg)) continue

                if (ady > bestDisplacement) bestDisplacement = ady
            }
        }

        if (bestDisplacement <= 0f) return null

        // 反向位移相对主挥动过大时视为意图不明确（例如来回摆），不触发。
        val oppositePath = oppositePath(window, -1)
        if (oppositePath > bestDisplacement * cfg.fingerSwipeDominanceRatio) return null

        return applyInversion(Gesture.SWIPE_UP, cfg)
    }

    /** 窗口内与主方向相反的累计位移。 */
    private fun oppositePath(window: List<TipPoint>, sign: Int): Float {
        var path = 0f
        var prev = window.first()
        for (point in window.drop(1)) {
            val step = point.y - prev.y
            if (step * sign < 0f) path += abs(step)
            prev = point
        }
        return path
    }

    /** 掌心需与指尖同向移动足够距离，排除只动手指或原地伸指。 */
    private fun palmFollows(
        window: List<TipPoint>,
        start: Int,
        end: Int,
        tipDy: Float,
        scale: Float,
        cfg: Config,
    ): Boolean {
        val palmDy = window[end].palmY - window[start].palmY
        if (palmDy * tipDy <= 0f) return false
        return abs(palmDy) >= cfg.fingerSwipeMinPalmDisplacement * scale
    }

    /** 段内竖直方向一致性：同向位移占比足够高，排除来回摆动。 */
    private fun isVerticalRun(
        window: List<TipPoint>,
        start: Int,
        end: Int,
        dy: Float,
        cfg: Config,
    ): Boolean {
        val sign = if (dy < 0f) -1f else 1f
        var total = 0f
        var aligned = 0f
        var prev = window[start]
        for (index in start + 1..end) {
            val step = window[index].y - prev.y
            total += abs(step)
            if (step * sign > 0f) aligned += abs(step)
            prev = window[index]
        }
        return total >= 1e-6f && aligned / total >= cfg.fingerSwipeConsistency
    }

    /**
     * 停稳后的静态姿势：握拳返回桌面，伸出 1 / 3 / 4 根手指打开指定应用。
     * 2 指（✌）由 [updatePeace] 单独处理，其余中间态忽略。
     */
    private fun updateStaticPose(
        bends: FloatArray,
        landmarks: List<LandmarkPoint>,
        scale: Float,
        cfg: Config,
    ): Gesture {
        val count = fingerCount(bends, cfg)
        if (count != 0) {
            // 手张开或改变姿势即复位握拳锁。
            fistCommitted = false
            fistStableCount = 0
        }
        return when (count) {
            0 -> {
                releaseFingerCountLatch()
                updateGrab(cfg)
            }
            1, 3, 4 -> {
                // 4 指与张开手掌只差拇指：拇指外伸时不算"4 根手指"，
                // 1 / 3 指形状本身与张开手掌不同，不再要求拇指收拢，降低误拒。
                if (count != 4 || isThumbFolded(landmarks, scale)) {
                    updateFingerCount(count, cfg)
                } else {
                    releaseFingerCountLatch()
                    Gesture.NONE
                }
            }
            else -> {
                releaseFingerCountLatch()
                Gesture.NONE
            }
        }
    }

    /** 拇指指尖到掌心轴线的垂直距离足够小，视为拇指收拢。 */
    private fun isThumbFolded(landmarks: List<LandmarkPoint>, scale: Float): Boolean {
        val wrist = landmarks[0]
        val middleMcp = landmarks[9]
        val thumbTip = landmarks[4]
        val axisX = middleMcp.x - wrist.x
        val axisY = middleMcp.y - wrist.y
        val axisLength = hypot(axisX, axisY)
        if (axisLength < 1e-4f) return true
        val relX = thumbTip.x - wrist.x
        val relY = thumbTip.y - wrist.y
        val perpendicular = abs(relX * axisY - relY * axisX) / axisLength
        return perpendicular / scale < THUMB_FOLDED_RATIO
    }

    /** 手指数：伸直的手指数量；任意一根处于中间态时返回 -1 表示不确定。 */
    private fun fingerCount(bends: FloatArray, cfg: Config): Int {
        var count = 0
        for (bend in bends) {
            when {
                bend >= cfg.countExtendedAngle -> count++
                bend <= cfg.countCurledAngle -> Unit
                else -> return -1
            }
        }
        return count
    }

    /** 四指全部收拢视为握拳；保持握拳不会重复触发，张开手掌后复位。 */
    private fun updateGrab(cfg: Config): Gesture {
        if (fistCommitted) return Gesture.NONE

        fistStableCount++
        if (fistStableCount < cfg.grabStableFrames) return Gesture.NONE

        fistStableCount = 0
        fistCommitted = true
        return Gesture.GRAB
    }

    /**
     * 手指数手势：同一姿势稳定保持才触发一次；保持不重复，
     * 换成另一种指数或收手后才会再次触发。
     */
    private fun updateFingerCount(count: Int, cfg: Config): Gesture {
        if (countLatched == count) {
            countStableCount = 0
            countMissingCount = 0
            return Gesture.NONE
        }
        countMissingCount = 0
        countStableCount++
        if (countStableCount < cfg.countStableFrames) return Gesture.NONE

        countStableCount = 0
        countLatched = count
        return when (count) {
            1 -> Gesture.FINGER_ONE
            3 -> Gesture.FINGER_THREE
            else -> Gesture.FINGER_FOUR
        }
    }

    /** 姿势未命中（例如 ✌、中间态）：短暂失配后解除手指数锁。 */
    private fun releaseFingerCountLatch() {
        countStableCount = 0
        if (countLatched == null) return
        countMissingCount++
        if (countMissingCount >= COUNT_RELEASE_FRAMES) {
            countLatched = null
            countMissingCount = 0
        }
    }

    /**
     * 比 ✌ 开关：食指、中指伸直，无名指、小指收拢。
     * 返回值 true 表示本次需要切换手势开关；保持同一姿势不会重复触发，
     * 只有先放下姿势再重新比 ✌ 才会再次切换。
     */
    private fun updatePeace(bends: FloatArray, cfg: Config): Boolean {
        val isPeace = bends[0] >= cfg.peaceExtendedAngle &&
            bends[1] >= cfg.peaceExtendedAngle &&
            bends[2] <= cfg.peaceCurledAngle &&
            bends[3] <= cfg.peaceCurledAngle

        if (!isPeace) {
            peaceStableCount = 0
            if (peaceLatched) {
                peaceMissingCount++
                if (peaceMissingCount >= PEACE_RELEASE_FRAMES) {
                    peaceLatched = false
                    peaceMissingCount = 0
                }
            }
            return false
        }

        peaceMissingCount = 0
        if (peaceLatched) return false

        peaceStableCount++
        if (peaceStableCount < cfg.peaceStableFrames) return false

        peaceLatched = true
        peaceStableCount = 0
        return true
    }

    /** 四指弯曲角：[食指, 中指, 无名指, 小指]，180° 为完全伸直。 */
    private fun fingerBendAngles(landmarks: List<LandmarkPoint>): FloatArray {
        val result = FloatArray(FINGER_JOINTS.size)
        for ((index, joints) in FINGER_JOINTS.withIndex()) {
            val (mcp, pip, tip) = joints
            result[index] = bendAngle(landmarks[mcp], landmarks[pip], landmarks[tip])
        }
        return result
    }

    private fun bendAngle(mcp: LandmarkPoint, pip: LandmarkPoint, tip: LandmarkPoint): Float {
        val v1x = mcp.x - pip.x
        val v1y = mcp.y - pip.y
        val v2x = tip.x - pip.x
        val v2y = tip.y - pip.y
        val n1 = hypot(v1x, v1y)
        val n2 = hypot(v2x, v2y)
        if (n1 < 1e-6f || n2 < 1e-6f) return 180f
        val cos = ((v1x * v2x + v1y * v2y) / (n1 * n2)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos.toDouble())).toFloat()
    }

    private fun applyInversion(gesture: Gesture, cfg: Config): Gesture = when (gesture) {
        Gesture.SWIPE_UP -> if (cfg.invertVertical) Gesture.SWIPE_DOWN else gesture
        Gesture.SWIPE_DOWN -> if (cfg.invertVertical) Gesture.SWIPE_UP else gesture
        else -> gesture
    }

    private companion object {
        const val HAND_LANDMARK_COUNT = 21
        const val MISSED_FRAMES_RESET = 8
        const val MIN_HAND_SCALE = 0.03f
        const val HISTORY_MS = 1500L
        const val MIN_DIRECTION_DISPLACEMENT = 0.04f

        /** 悬浮窗方向/速度统计窗口。 */
        const val DIRECTION_WINDOW_MS = 400L

        /** 比 ✌ 持续消失多少帧后解除锁定，允许下一次切换。 */
        const val PEACE_RELEASE_FRAMES = 3

        /** 手指数姿势失配多少帧后解除锁定，允许再次触发同一指数量。 */
        const val COUNT_RELEASE_FRAMES = 3

        /** 拇指指尖到掌心轴线距离小于该手长比例时视为收拢（比 1/3/4 的姿势）。 */
        const val THUMB_FOLDED_RATIO = 0.32f

        /** (MCP, PIP, TIP)：食指、中指、无名指、小指。 */
        val FINGER_JOINTS = listOf(
            Triple(5, 6, 8),
            Triple(9, 10, 12),
            Triple(13, 14, 16),
            Triple(17, 18, 20),
        )
    }
}

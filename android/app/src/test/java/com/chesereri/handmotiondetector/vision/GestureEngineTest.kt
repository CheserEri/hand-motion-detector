package com.chesereri.handmotiondetector.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureEngineTest {

    private val peace = listOf(true, true, false, false)
    private val oneFinger = listOf(true, false, false, false)
    private val threeFingers = listOf(true, true, true, false)
    private val fourFingers = listOf(true, true, true, true)

    @Test
    fun `swipe up is recognized after hand settles`() {
        val engine = GestureEngine()
        val gestures = mutableListOf<Gesture>()
        var time = 1_000L
        var y = 0.62f

        // 手先停稳：引擎武装后才允许识别。
        repeat(6) {
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }
        repeat(8) {
            y -= 0.07f
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }

        assertEquals(listOf(Gesture.SWIPE_UP), gestures.filter { it != Gesture.NONE })
    }

    @Test
    fun `downward movement is ignored`() {
        val engine = GestureEngine()
        val gestures = mutableListOf<Gesture>()
        var time = 1_000L
        var y = 0.38f

        repeat(6) {
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }
        repeat(8) {
            y += 0.07f
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }
        // 再向下直到停稳，也不应触发任何手势。
        repeat(12) {
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }

        assertEquals(emptyList<Gesture>(), gestures.filter { it != Gesture.NONE })
    }

    @Test
    fun `vertical inversion turns the up stroke into a down swipe`() {
        val engine = GestureEngine(GestureEngine.Config(invertVertical = true))
        val gestures = mutableListOf<Gesture>()
        var time = 1_000L
        var y = 0.62f

        repeat(6) {
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }
        repeat(8) {
            y -= 0.07f
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }

        assertEquals(listOf(Gesture.SWIPE_DOWN), gestures.filter { it != Gesture.NONE })
    }

    @Test
    fun `light upward wave is recognized`() {
        val engine = GestureEngine()
        val gestures = mutableListOf<Gesture>()
        var time = 1_000L
        var y = 0.60f

        repeat(6) {
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }
        // 幅度更小、更随意地向上挥一下。
        repeat(6) {
            y -= 0.04f
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }

        assertEquals(listOf(Gesture.SWIPE_UP), gestures.filter { it != Gesture.NONE })
    }

    @Test
    fun `slow movement does not trigger a swipe`() {
        val engine = GestureEngine()
        val gestures = mutableListOf<Gesture>()
        var time = 1_000L
        var y = 0.70f

        repeat(6) {
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }
        repeat(10) {
            y -= 0.01f
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }

        assertEquals(emptyList<Gesture>(), gestures.filter { it != Gesture.NONE })
    }

    @Test
    fun `horizontal movement does not trigger anything`() {
        val engine = GestureEngine()
        val gestures = mutableListOf<Gesture>()
        var time = 1_000L
        var x = 0.30f

        repeat(6) {
            gestures += feed(engine, x, 0.5f, time)
            time += 50
        }
        repeat(10) {
            x += 0.06f
            gestures += feed(engine, x, 0.5f, time)
            time += 50
        }

        assertEquals(emptyList<Gesture>(), gestures.filter { it != Gesture.NONE })
    }

    @Test
    fun `slow preparation before a fast up swipe only triggers up`() {
        val engine = GestureEngine()
        val gestures = mutableListOf<Gesture>()
        var time = 1_000L
        var y = 0.62f

        repeat(6) {
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }
        // 为了有空间向上，先缓慢向下移动（不应触发下滑）。
        repeat(6) {
            y += 0.02f
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }
        // 真正用力的上挥。
        repeat(8) {
            y -= 0.07f
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }

        assertEquals(listOf(Gesture.SWIPE_UP), gestures.filter { it != Gesture.NONE })
    }

    @Test
    fun `lowering hand right after a swipe does not trigger the opposite gesture`() {
        val engine = GestureEngine()
        val gestures = mutableListOf<Gesture>()
        var time = 1_000L
        var y = 0.62f

        repeat(6) {
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }
        repeat(8) {
            y -= 0.07f
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }
        // 手势后手自然放下：即使同样很快，也不应识别成下滑。
        repeat(10) {
            y += 0.07f
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }
        repeat(20) {
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }

        assertEquals(listOf(Gesture.SWIPE_UP), gestures.filter { it != Gesture.NONE })
    }

    @Test
    fun `one swipe per gesture until hand settles`() {
        val engine = GestureEngine()
        val gestures = mutableListOf<Gesture>()
        var time = 1_000L
        var y = 0.68f

        repeat(6) {
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }
        // 第一次上滑：触发后立即进入未武装状态。
        repeat(8) {
            y -= 0.07f
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }
        // 手继续上移（还没撤退/停稳），不应再次触发。
        repeat(10) {
            y -= 0.02f
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }

        assertEquals(1, gestures.count { it == Gesture.SWIPE_UP })

        // 停稳足够时间后重新武装，可再次识别下一个手势。
        repeat(16) {
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }
        repeat(8) {
            y -= 0.07f
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }

        assertEquals(2, gestures.count { it == Gesture.SWIPE_UP })
    }

    @Test
    fun `fist triggers grab once per close and resets after opening`() {
        val engine = GestureEngine()
        val gestures = mutableListOf<Gesture>()
        var time = 1_000L

        repeat(14) {
            gestures += feed(engine, 0.5f, 0.5f, time, closed = false)
            time += 50
        }
        // 握拳：触发返回桌面。
        repeat(10) {
            gestures += feed(engine, 0.5f, 0.5f, time, closed = true)
            time += 50
        }
        // 张开手掌：不再绑定动作，只复位握拳状态。
        repeat(30) {
            gestures += feed(engine, 0.5f, 0.5f, time, closed = false)
            time += 50
        }
        // 再次握拳可再次触发。
        repeat(10) {
            gestures += feed(engine, 0.5f, 0.5f, time, closed = true)
            time += 50
        }

        assertEquals(listOf(Gesture.GRAB, Gesture.GRAB), gestures.filter { it != Gesture.NONE })
    }

    @Test
    fun `peace sign toggles other gestures off and on`() {
        val engine = GestureEngine()
        val results = mutableListOf<GestureFrameResult>()
        var time = 1_000L
        var y = 0.62f

        // 先停稳武装。
        repeat(6) {
            results += feedResult(engine, 0.5f, y, time)
            time += 50
        }
        // 比 ✌ 关闭其他手势。
        repeat(6) {
            results += feedResult(engine, 0.5f, y, time, openFingers = peace)
            time += 50
        }
        assertEquals(Gesture.PEACE, results.last().gesture)
        assertFalse(results.last().gesturesEnabled)

        // 保持 ✌ 不应重复切换。
        repeat(8) {
            results += feedResult(engine, 0.5f, y, time, openFingers = peace)
            time += 50
        }
        assertEquals(1, results.count { it.gesture == Gesture.PEACE })

        // 关闭状态下做上滑无效。
        repeat(8) {
            y -= 0.07f
            results += feedResult(engine, 0.5f, y, time)
            time += 50
        }
        assertEquals(0, results.count { it.gesture == Gesture.SWIPE_UP })

        // 再次比 ✌ 重新开启。
        repeat(6) {
            results += feedResult(engine, 0.5f, y, time, openFingers = peace)
            time += 50
        }
        assertEquals(2, results.count { it.gesture == Gesture.PEACE })
        assertTrue(results.last().gesturesEnabled)
        val afterEnableIndex = results.size

        // 停稳后上滑恢复正常。
        repeat(18) {
            results += feedResult(engine, 0.5f, y, time)
            time += 50
        }
        repeat(8) {
            y -= 0.07f
            results += feedResult(engine, 0.5f, y, time)
            time += 50
        }
        assertEquals(1, results.drop(afterEnableIndex).count { it.gesture == Gesture.SWIPE_UP })
    }

    @Test
    fun `finger count gestures trigger once and can be repeated`() {
        val engine = GestureEngine()
        val gestures = mutableListOf<Gesture>()
        var time = 1_000L
        var y = 0.5f

        // 先停稳武装（张开手掌且拇指外伸，不应被当成手指数）。
        repeat(6) {
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }
        // 比 1。
        repeat(6) {
            gestures += feedResult(engine, 0.5f, y, time, openFingers = oneFinger, thumbFolded = true).gesture
            time += 50
        }
        assertEquals(1, gestures.count { it == Gesture.FINGER_ONE })

        // 保持 1 指不应重复触发。
        repeat(8) {
            gestures += feedResult(engine, 0.5f, y, time, openFingers = oneFinger, thumbFolded = true).gesture
            time += 50
        }
        assertEquals(1, gestures.count { it == Gesture.FINGER_ONE })

        // 张开手复位后比 3。
        repeat(12) {
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }
        repeat(8) {
            gestures += feedResult(engine, 0.5f, y, time, openFingers = threeFingers, thumbFolded = true).gesture
            time += 50
        }
        assertEquals(1, gestures.count { it == Gesture.FINGER_THREE })

        // 再复位后比 4。
        repeat(12) {
            gestures += feed(engine, 0.5f, y, time)
            time += 50
        }
        repeat(8) {
            gestures += feedResult(engine, 0.5f, y, time, openFingers = fourFingers, thumbFolded = true).gesture
            time += 50
        }
        assertEquals(1, gestures.count { it == Gesture.FINGER_FOUR })
    }

    @Test
    fun `open palm with extended thumb does not trigger finger count`() {
        val engine = GestureEngine()
        val gestures = mutableListOf<Gesture>()
        var time = 1_000L

        repeat(30) {
            gestures += feed(engine, 0.5f, 0.5f, time)
            time += 50
        }

        assertEquals(emptyList<Gesture>(), gestures.filter { it != Gesture.NONE })
    }

    @Test
    fun `no hand reports invisible and resets`() {
        val engine = GestureEngine()
        val result = engine.process(null, 0.1f, 1_000L)

        assertFalse(result.handVisible)
        assertEquals(Gesture.NONE, result.gesture)
    }

    private fun feedResult(
        engine: GestureEngine,
        x: Float,
        y: Float,
        timestampMs: Long,
        closed: Boolean = false,
        openFingers: List<Boolean>? = null,
        thumbFolded: Boolean = false,
    ): GestureFrameResult = engine.process(
        hand(x, y, closed, openFingers = openFingers, thumbFolded = thumbFolded),
        0.9f,
        timestampMs,
    )

    private fun feed(
        engine: GestureEngine,
        x: Float,
        y: Float,
        timestampMs: Long,
        closed: Boolean = false,
    ): Gesture = feedResult(engine, x, y, timestampMs, closed).gesture

    /**
     * 构造一只朝向画面上方的手：腕点(0) 与中指 MCP(9) 距离为 [scale]，
     * 四指分别处于伸直或握拳状态；传入 [openFingers] 可单独指定每根手指，
     * [thumbFolded] 控制拇指是否收在掌心（比 1/3/4 时需要收拢）。
     */
    private fun hand(
        centerX: Float,
        centerY: Float,
        closed: Boolean,
        scale: Float = 0.3f,
        openFingers: List<Boolean>? = null,
        thumbFolded: Boolean = false,
    ): List<LandmarkPoint> {
        val points = MutableList(21) { LandmarkPoint(centerX, centerY) }
        val wristY = centerY + scale / 2
        val mcpY = centerY - scale / 2
        points[0] = LandmarkPoint(centerX, wristY)
        points[9] = LandmarkPoint(centerX, mcpY)

        val thumbFoldedNow = thumbFolded || closed
        points[1] = LandmarkPoint(centerX - 0.1f * scale, wristY - 0.1f * scale)
        points[2] = LandmarkPoint(centerX - 0.15f * scale, wristY - 0.25f * scale)
        points[3] = LandmarkPoint(centerX - 0.2f * scale, wristY - 0.4f * scale)
        points[4] = if (thumbFoldedNow) {
            LandmarkPoint(centerX + 0.05f * scale, centerY)
        } else {
            LandmarkPoint(centerX - 0.45f * scale, centerY - 0.2f * scale)
        }

        val offsets = floatArrayOf(-0.18f, -0.06f, 0.06f, 0.18f)
        val mcpIds = intArrayOf(5, 9, 13, 17)
        val pipIds = intArrayOf(6, 10, 14, 18)
        val tipIds = intArrayOf(8, 12, 16, 20)

        for (i in 0 until 4) {
            val fingerX = centerX + offsets[i] * scale
            points[mcpIds[i]] = LandmarkPoint(fingerX, mcpY)
            val straight = openFingers?.get(i) ?: !closed
            if (!straight) {
                points[pipIds[i]] = LandmarkPoint(fingerX, mcpY - 0.1f * scale)
                points[tipIds[i]] = LandmarkPoint(fingerX, mcpY + 0.15f * scale)
            } else {
                points[pipIds[i]] = LandmarkPoint(fingerX, mcpY - 0.3f * scale)
                points[tipIds[i]] = LandmarkPoint(fingerX, mcpY - 0.65f * scale)
            }
        }
        return points
    }
}

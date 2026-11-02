package com.chesereri.handmotiondetector.vision

import kotlin.math.abs
import kotlin.math.exp

/**
 * One-Euro 滤波器：在低延迟与抖动抑制之间取平衡，适合手部关键点的归一化坐标。
 */
class OneEuroFilter(
    private val minCutoff: Double = 2.0,
    private val beta: Double = 1.0,
    private val dCutoff: Double = 2.0,
) {
    private var hasPrev = false
    private var prevValue = 0.0
    private var prevDerivative = 0.0
    private var prevTimeMs = 0L

    fun filter(value: Double, timestampMs: Long): Double {
        if (!hasPrev) {
            hasPrev = true
            prevValue = value
            prevTimeMs = timestampMs
            return value
        }
        val dt = (timestampMs - prevTimeMs).coerceAtLeast(1L) / 1000.0
        val derivative = (value - prevValue) / dt
        val filteredDerivative = lowPass(derivative, prevDerivative, alpha(dCutoff, dt))
        val cutoff = minCutoff + beta * abs(filteredDerivative)
        val filtered = lowPass(value, prevValue, alpha(cutoff, dt))

        prevValue = filtered
        prevDerivative = filteredDerivative
        prevTimeMs = timestampMs
        return filtered
    }

    fun reset() {
        hasPrev = false
        prevValue = 0.0
        prevDerivative = 0.0
        prevTimeMs = 0L
    }

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2.0 * Math.PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    private fun lowPass(value: Double, prev: Double, alpha: Double): Double =
        alpha * value + (1.0 - alpha) * prev
}

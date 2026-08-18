package com.renardoberou.spectralcamera.core.color

import kotlin.math.exp

/** Shared, conservative tone parameters for the pure reference implementation. */
data class ToneProfile(
    val toeStrength: Float = 0.18f,
    val toeLength: Float = 0.50f,
    val pivot: Float = 0.18f,
    val midSlope: Float = 1.0f,
    val shoulderStrength: Float = 0.35f,
    val shoulderLength: Float = 0.50f,
    val blackFloor: Float = 0f,
    val highlightChromaCompression: Float = 0f,
)

/** Monotonic toe/mid/shoulder reference math, independent of Android and GLSL. */
object ToneMath {
    fun map(value: Float, profile: ToneProfile = ToneProfile()): Float {
        val input = WorkingSpaceMath.finite(value).coerceAtLeast(0f)
        val toeDenominator = input + profile.toeStrength.coerceAtLeast(0f) *
            profile.toeLength.coerceAtLeast(0f)
        val toe = if (toeDenominator > 0f) input / toeDenominator else input
        val pivot = profile.pivot.coerceIn(0f, 1f)
        val mid = (pivot + (toe - pivot) * profile.midSlope.coerceAtLeast(0f))
            .coerceAtLeast(0f)
        val shoulderScale = 1f + profile.shoulderStrength.coerceAtLeast(0f) *
            profile.shoulderLength.coerceAtLeast(0f)
        val shoulder = if (shoulderScale.isFinite() && shoulderScale > 0f) {
            1f - exp(-mid / shoulderScale)
        } else {
            1f
        }
        val floor = profile.blackFloor.coerceIn(0f, 1f)
        return (floor + (1f - floor) * shoulder).coerceIn(0f, 1f)
    }

    fun finalDisplay(value: Float, profile: ToneProfile = ToneProfile()): Float =
        WorkingSpaceMath.finalDisplay(map(value, profile))
}

package com.renardoberou.spectralcamera.core.color

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.PI

/** Pure, deterministic hue-sector density primitives shared by analytic pipelines. */
object HueSectorMath {
    private fun finite(value: Float, fallback: Float = 0f): Float =
        if (value.isNaN() || value.isInfinite()) fallback else value

    /** Cosine-tapered circular weight; the sector boundary is half the width. */
    @JvmStatic
    fun sectorWeight(hueDegrees: Float, centerDegrees: Float, widthDegrees: Float = 60f): Float {
        val hue = finite(hueDegrees)
        val center = finite(centerDegrees)
        val width = finite(widthDegrees)
        if (width <= 0f) return 0f
        if (width >= 360f) return 1f
        val wrapped = ((hue - center + 180f) % 360f + 360f) % 360f
        val distance = abs(wrapped - 180f)
        if (distance >= width * 0.5f) return 0f
        return (cos(PI * distance / width).toFloat()).coerceIn(0f, 1f)
    }

    /** Triangular midtone weight. Black and white are deliberately protected. */
    @JvmStatic
    fun luminanceWeight(luminance: Float): Float {
        val value = finite(luminance).coerceIn(0f, 1f)
        return (1f - abs(2f * value - 1f)).coerceIn(0f, 1f)
    }

    /** Rational compression curve for a non-negative, already-normalized density. */
    @JvmStatic
    fun compressDensity(density: Float, compression: Float = 1f): Float {
        val value = finite(density).coerceIn(0f, 1f)
        val amount = max(0f, finite(compression))
        return (value / (1f + amount * value)).coerceIn(0f, 1f)
    }

    /** Hue- and luminance-weighted chroma, followed by bounded compression. */
    @JvmStatic
    fun colorDensity(
        hueDegrees: Float,
        chroma: Float,
        luminance: Float,
        centerDegrees: Float,
        widthDegrees: Float = 60f,
        densityGain: Float = 1f,
        compression: Float = 1f,
    ): Float {
        val normalizedChroma = finite(chroma).coerceIn(0f, 1f)
        val gain = max(0f, finite(densityGain))
        val weighted = (normalizedChroma * sectorWeight(hueDegrees, centerDegrees, widthDegrees) *
            luminanceWeight(luminance) * gain).coerceIn(0f, 1f)
        return compressDensity(weighted, compression)
    }
}

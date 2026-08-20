package com.renardoberou.spectralcamera.core.color

import kotlin.math.ln
import kotlin.math.pow

/** A small, Android-free RGB value used between renderer stages. */
data class Rgb(val r: Float, val g: Float, val b: Float)

/** Pure working-space conversions. Values above one retain scene headroom. */
object WorkingSpaceMath {
    private val LOG_BASE = ln(2f)

    fun finite(value: Float, fallback: Float = 0f): Float = when {
        value.isNaN() -> fallback
        value == Float.POSITIVE_INFINITY -> Float.MAX_VALUE
        value == Float.NEGATIVE_INFINITY -> fallback
        else -> value
    }

    fun normalize(value: Float): Float = finite(value).coerceAtLeast(0f)

    fun normalizeRgb(r: Float, g: Float, b: Float): Rgb = Rgb(
        normalize(r),
        normalize(g),
        normalize(b),
    )

    /** Log-like encoding with an exact inverse for all finite non-negative values. */
    fun linearToLog(value: Float): Float = ln(1f + normalize(value)) / LOG_BASE

    fun logToLinear(value: Float): Float {
        val maximumLog = ln(Float.MAX_VALUE) / LOG_BASE
        return (2f.pow(finite(value).coerceIn(0f, maximumLog)) - 1f)
            .coerceIn(0f, Float.MAX_VALUE)
    }

    /** Convert linear-light input to display sRGB, with the only final clamp. */
    fun finalDisplay(linear: Float): Float {
        val value = normalize(linear)
        val encoded = if (value <= 0.0031308f) {
            value * 12.92f
        } else {
            1.055f * value.pow(1f / 2.4f) - 0.055f
        }
        return finite(encoded).coerceIn(0f, 1f)
    }

    fun finalDisplay(rgb: Rgb): Rgb = Rgb(
        finalDisplay(rgb.r),
        finalDisplay(rgb.g),
        finalDisplay(rgb.b),
    )
}

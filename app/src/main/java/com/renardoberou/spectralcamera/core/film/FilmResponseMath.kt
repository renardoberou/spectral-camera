package com.renardoberou.spectralcamera.core.film

import kotlin.math.exp

/** CPU reference for the GLSL scene-to-film response functions. */
object FilmResponseMath {
    fun preFilmLuma(value: Float, strength: Float): Float {
        val v = value.coerceIn(0f, 1f)
        val s = strength.coerceAtLeast(0f)
        return (v * (1f + 0.5f * s) / (1f + s * v)).coerceIn(0f, 1f)
    }

    fun protectedReversalCurve(value: Float, curveMix: Float): Float {
        val v = value.coerceIn(0f, 1f)
        val smooth = v * v * (3f - 2f * v)
        val toeProtect = (1f - smoothstep(0.04f, 0.24f, v)) * 0.28f
        val protected = smooth + (v - smooth) * toeProtect
        val mix = curveMix.coerceIn(0f, 1f)
        return (v + (protected - v) * mix).coerceIn(0f, 1f)
    }

    fun softShoulderPeak(peak: Float): Float {
        val safe = peak.coerceAtLeast(0f)
        if (safe <= 0.92f) return safe
        return (0.92f + 0.08f * (1f - exp(-(safe - 0.92f) / 0.08f)))
            .coerceAtMost(1f)
    }

    fun redHighlightSaturationScale(outputLuma: Float, redDominance: Float): Float {
        val highlight = redDominance.coerceIn(0f, 1f) *
            smoothstep(0.52f, 0.90f, outputLuma.coerceIn(0f, 1f))
        return 1f - highlight * 0.30f
    }

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        if (edge0 == edge1) return if (value < edge0) 0f else 1f
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

package com.renardoberou.spectralcamera.core.color

import kotlin.math.abs
import kotlin.math.max

/**
 * Android-free reference math for the Aerochrome neutral-surface guard.
 *
 * The shader uses the same continuous signals. Keeping this small reference
 * implementation separate makes the wall/foliage/water regression fixture
 * deterministic without requiring an OpenGL context in JVM tests.
 */
data class AerochromeNeutralSignals(
    val lowChromaConfidence: Float,
    val greenBalanceConfidence: Float,
    val localContinuity: Float,
    val competingMaterialConfidence: Float,
    val neutralArtifactConfidence: Float,
)

object AerochromeNeutralMath {
    private const val NEUTRAL_SHARE = 0.3333f

    fun protectionWeight(
        source: Rgb,
        classification: Rgb = source,
        smoothLuma: Float = luma(source),
        foliageConfidence: Float = 0f,
        waterConfidence: Float = 0f,
    ): Float = signals(
        source = source,
        classification = classification,
        smoothLuma = smoothLuma,
        foliageConfidence = foliageConfidence,
        waterConfidence = waterConfidence,
    ).neutralArtifactConfidence

    fun signals(
        source: Rgb,
        classification: Rgb = source,
        smoothLuma: Float = luma(source),
        foliageConfidence: Float = 0f,
        waterConfidence: Float = 0f,
    ): AerochromeNeutralSignals {
        val sourceLuma = luma(source)
        val classificationTotal = clean(classification.r) +
            clean(classification.g) +
            clean(classification.b) + 0.001f
        val nr = clean(classification.r) / classificationTotal
        val ng = clean(classification.g) / classificationTotal
        val nb = clean(classification.b) / classificationTotal
        val chromaDistance = max(
            max(abs(nr - NEUTRAL_SHARE), abs(ng - NEUTRAL_SHARE)),
            abs(nb - NEUTRAL_SHARE),
        )
        val lowChroma = 1f - smoothStep(0.035f, 0.10f, chromaDistance)
        val greenBalance = 1f - smoothStep(0f, 0.05f, abs(ng - NEUTRAL_SHARE))
        val reliability = smoothStep(0.08f, 0.20f, sourceLuma)
        val continuity = 1f - smoothStep(
            0.015f,
            0.06f,
            abs(sourceLuma - clean(smoothLuma).coerceIn(0f, 1f)),
        )
        val competing = max(clean(foliageConfidence), clean(waterConfidence)).coerceIn(0f, 1f)
        val neutralArtifactConfidence = (
            lowChroma * greenBalance * reliability * continuity * (1f - competing)
            ).coerceIn(0f, 1f)
        return AerochromeNeutralSignals(
            lowChromaConfidence = lowChroma,
            greenBalanceConfidence = greenBalance,
            localContinuity = continuity,
            competingMaterialConfidence = competing,
            neutralArtifactConfidence = neutralArtifactConfidence,
        )
    }

    /** Blend a false-colour candidate toward a luma-preserving neutral film tone. */
    fun protectFalseColour(
        source: Rgb,
        falseColour: Rgb,
        classification: Rgb = source,
        smoothLuma: Float = luma(source),
        foliageConfidence: Float = 0f,
        waterConfidence: Float = 0f,
    ): Rgb {
        val weight = protectionWeight(
            source = source,
            classification = classification,
            smoothLuma = smoothLuma,
            foliageConfidence = foliageConfidence,
            waterConfidence = waterConfidence,
        ) * 0.85f
        val sourceLuma = luma(source)
        val neutralTone = Rgb(
            clamp(sourceLuma * 1.04f),
            clamp(sourceLuma),
            clamp(sourceLuma * 0.92f),
        )
        return Rgb(
            mix(falseColour.r, neutralTone.r, weight),
            mix(falseColour.g, neutralTone.g, weight),
            mix(falseColour.b, neutralTone.b, weight),
        )
    }

    fun luma(rgb: Rgb): Float =
        0.299f * clean(rgb.r) + 0.587f * clean(rgb.g) + 0.114f * clean(rgb.b)

    private fun mix(from: Float, to: Float, amount: Float): Float =
        from + (to - from) * amount.coerceIn(0f, 1f)

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        if (edge1 <= edge0) return if (value >= edge1) 1f else 0f
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun clamp(value: Float): Float = clean(value).coerceIn(0f, 1f)

    private fun clean(value: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
}

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
    val murkyChromaConfidence: Float,
    val greenBalanceConfidence: Float,
    val localContinuity: Float,
    val competingMaterialConfidence: Float,
    val neutralArtifactConfidence: Float,
    val vividBlueConfidence: Float,
    val murkyConfidence: Float,
    val foliageAuthority: Float,
    val waterAuthority: Float,
    val greyWideConfidence: Float,
    val neutralCreamConfidence: Float,
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
    ).neutralCreamConfidence * 0.85f

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
        val neutralChroma = 1f - smoothStep(0.035f, 0.10f, chromaDistance)
        val murkyLowChroma = 1f - smoothStep(0.045f, 0.10f, chromaDistance)
        val greenBalance = 1f - smoothStep(0f, 0.05f, abs(ng - NEUTRAL_SHARE))
        val reliability = smoothStep(0.08f, 0.20f, sourceLuma)
        val surfSmooth = 1f - smoothStep(
            0.015f,
            0.06f,
            abs(sourceLuma - clean(smoothLuma).coerceIn(0f, 1f)),
        )
        val greenDom = smoothStep(0f, 0.05f, ng - nr)
        val grn = smoothStep(-0.01f, 0.08f, ng - nb)
        val notBlue = 1f - smoothStep(0f, 0.06f, nb - max(nr, ng))
        val veg = (grn * notBlue * greenDom).coerceIn(0f, 1f)
        val oliveGreenBlue = smoothStep(0.12f, 0.22f, ng - nb)
        val nearNeutralRg = 1f - smoothStep(0.03f, 0.09f, abs(ng - nr))
        val oliveVeg = (oliveGreenBlue * nearNeutralRg * notBlue * (1f - veg)).coerceIn(0f, 1f)
        val blueC = smoothStep(0.03f, 0.10f, nb - max(nr, ng))
        val waterC = smoothStep(0.05f, 0.14f, ng - nr) *
            smoothStep(0.02f, 0.08f, nb - nr)
        val cyanC = smoothStep(0.025f, 0.09f, minOf(ng, nb) - nr) * surfSmooth
        val vividBlue = (
            waterC * max(blueC, smoothStep(0.02f, 0.08f, nb - nr)) + cyanC * 0.6f
            ).coerceIn(0f, 1f)
        val weakGreen = smoothStep(0.015f, 0.06f, ng - nr) *
            smoothStep(0f, 0.04f, ng - nb)
        val competing = (veg + oliveVeg).coerceIn(0f, 1f).coerceAtLeast(waterC).coerceIn(0f, 1f)
        val neutralArtifactConfidence = (
            neutralChroma * greenBalance * reliability * surfSmooth * (1f - competing)
            ).coerceIn(0f, 1f)
        val vividBlueAfterGuard = vividBlue * (1f - neutralArtifactConfidence * 0.90f)
        val murky = weakGreen * murkyLowChroma * surfSmooth * (1f - veg) * (1f - vividBlueAfterGuard)
        val chromaFloor = smoothStep(0.035f, 0.058f, chromaDistance)
        val waterStrong = max(vividBlueAfterGuard, murky * 1.2f).coerceIn(0f, 1f)
        val foliageAuthority = (veg + oliveVeg).coerceIn(0f, 1f) *
            chromaFloor * (1f - waterStrong * 0.9f)
        val greenNeutral = 1f - smoothStep(0f, 0.05f, abs(ng - NEUTRAL_SHARE))
        val greyWide = 1f - smoothStep(
            0.020f,
            mix(0.075f, 0.20f, greenNeutral),
            chromaDistance,
        )
        val neutralCream = max(greyWide * surfSmooth, neutralArtifactConfidence) *
            smoothStep(0.25f, 0.60f, sourceLuma) *
            (1f - foliageAuthority) *
            (1f - vividBlueAfterGuard) *
            (1f - murky)
        return AerochromeNeutralSignals(
            lowChromaConfidence = neutralChroma,
            murkyChromaConfidence = murkyLowChroma,
            greenBalanceConfidence = greenBalance,
            localContinuity = surfSmooth,
            competingMaterialConfidence = competing,
            neutralArtifactConfidence = neutralArtifactConfidence,
            vividBlueConfidence = vividBlueAfterGuard,
            murkyConfidence = murky,
            foliageAuthority = foliageAuthority,
            waterAuthority = waterC,
            greyWideConfidence = greyWide,
            neutralCreamConfidence = neutralCream.coerceIn(0f, 1f),
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
        )
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

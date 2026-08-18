package com.renardoberou.spectralcamera.core.color

import kotlin.math.exp

/** Weights used to combine soft material and tonal protection confidences. */
data class MaterialConfidenceWeights(
    val skin: Float = 1f,
    val foliage: Float = 1f,
    val sky: Float = 1f,
    val blueCyan: Float = 1f,
    val neutral: Float = 1f,
    val highlight: Float = 1f,
    val shadow: Float = 1f,
)

/** Android-free, deterministic confidence fields for broad RGB material classes. */
object MaterialConfidenceMath {
    private const val CHROMA_EPSILON = 0.0001f

    fun reliability(rgb: Rgb): Float = reliability(rgb.r, rgb.g, rgb.b)

    fun reliability(r: Float, g: Float, b: Float): Float =
        smoothStep(0.008f, 0.08f, luminance(r, g, b))

    fun skinConfidence(rgb: Rgb): Float = skinConfidence(rgb.r, rgb.g, rgb.b)
    fun foliageConfidence(rgb: Rgb): Float = foliageConfidence(rgb.r, rgb.g, rgb.b)
    fun skyConfidence(rgb: Rgb): Float = skyConfidence(rgb.r, rgb.g, rgb.b)
    fun blueCyanConfidence(rgb: Rgb): Float = blueCyanConfidence(rgb.r, rgb.g, rgb.b)
    fun neutralConfidence(rgb: Rgb): Float = neutralConfidence(rgb.r, rgb.g, rgb.b)
    fun highlightConfidence(rgb: Rgb): Float = highlightConfidence(rgb.r, rgb.g, rgb.b)
    fun shadowConfidence(rgb: Rgb): Float = shadowConfidence(rgb.r, rgb.g, rgb.b)

    fun skinConfidence(r: Float, g: Float, b: Float): Float = chromaConfidence(r, g, b, .48f, .30f, .22f)
    fun foliageConfidence(r: Float, g: Float, b: Float): Float = chromaConfidence(r, g, b, .15f, .65f, .20f)
    fun skyConfidence(r: Float, g: Float, b: Float): Float = chromaConfidence(r, g, b, .17f, .30f, .53f)
    fun blueCyanConfidence(r: Float, g: Float, b: Float): Float = chromaConfidence(r, g, b, .10f, .42f, .48f)

    fun neutralConfidence(r: Float, g: Float, b: Float): Float {
        val red = clean(r)
        val green = clean(g)
        val blue = clean(b)
        val total = red + green + blue + CHROMA_EPSILON
        val spread = (red - green) * (red - green) +
            (green - blue) * (green - blue) + (blue - red) * (blue - red)
        return (reliability(r, g, b) * exp(-spread / (total * total * 0.045f + CHROMA_EPSILON)))
            .coerceIn(0f, 1f)
    }

    fun highlightConfidence(r: Float, g: Float, b: Float): Float =
        reliability(r, g, b) * smoothStep(0.65f, 1.20f, luminance(r, g, b))

    fun shadowConfidence(r: Float, g: Float, b: Float): Float =
        reliability(r, g, b) * (1f - smoothStep(0.05f, 0.35f, luminance(r, g, b)))

    fun weightedProtection(
        rgb: Rgb,
        weights: MaterialConfidenceWeights = MaterialConfidenceWeights(),
    ): Float = weightedProtection(rgb.r, rgb.g, rgb.b, weights)

    fun weightedProtection(
        r: Float,
        g: Float,
        b: Float,
        weights: MaterialConfidenceWeights = MaterialConfidenceWeights(),
    ): Float {
        val values = floatArrayOf(
            skinConfidence(r, g, b), foliageConfidence(r, g, b), skyConfidence(r, g, b),
            blueCyanConfidence(r, g, b), neutralConfidence(r, g, b),
            highlightConfidence(r, g, b), shadowConfidence(r, g, b),
        )
        val factors = floatArrayOf(
            weights.skin, weights.foliage, weights.sky, weights.blueCyan,
            weights.neutral, weights.highlight, weights.shadow,
        )
        var numerator = 0f
        var denominator = 0f
        values.indices.forEach { index ->
            val weight = clean(factors[index])
            numerator += values[index] * weight
            denominator += weight
        }
        return if (denominator > 0f) (numerator / denominator).coerceIn(0f, 1f) else 0f
    }

    fun combinedProtection(rgb: Rgb, weights: MaterialConfidenceWeights = MaterialConfidenceWeights()): Float =
        weightedProtection(rgb, weights)

    private fun chromaConfidence(r: Float, g: Float, b: Float, tr: Float, tg: Float, tb: Float): Float {
        val red = clean(r)
        val green = clean(g)
        val blue = clean(b)
        val total = red + green + blue + CHROMA_EPSILON
        val distance = (red / total - tr) * (red / total - tr) +
            (green / total - tg) * (green / total - tg) +
            (blue / total - tb) * (blue / total - tb)
        return (reliability(r, g, b) * exp(-distance / 0.018f)).coerceIn(0f, 1f)
    }

    private fun luminance(r: Float, g: Float, b: Float): Float =
        0.2126f * clean(r) + 0.7152f * clean(g) + 0.0722f * clean(b)

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun clean(value: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 100f) else 0f
}

package com.renardoberou.spectralcamera.core.hdr

import com.renardoberou.spectralcamera.core.HdrToneMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class HdrBracketStep(
    val compensationIndex: Int,
    val evOffset: Float,
)

data class PixelShift(val dx: Int, val dy: Int)

/**
 * Confidence is deliberately conservative. A rejected estimate returns zero
 * shift and zero confidence, which makes the fusion fall back to the reference
 * exposure instead of producing a multi-image ghost.
 */
data class AlignmentEstimate(
    val shift: PixelShift,
    val confidence: Float,
    val normalizedError: Float,
    val validFraction: Float,
) {
    val accepted: Boolean get() = confidence >= 0.25f
}

object HdrBracketPlanner {
    fun planAuto(
        baseIndex: Int,
        supportedRange: IntRange,
        exposureStep: Float,
        requestedSpanStops: Float = 2f,
    ): List<HdrBracketStep> {
        if (supportedRange.first > supportedRange.last) return emptyList()
        val step = exposureStep.takeIf { it > 0f } ?: (1f / 3f)
        val base = baseIndex.coerceIn(supportedRange.first, supportedRange.last)
        if (supportedRange.last - supportedRange.first < 2) {
            return listOf(HdrBracketStep(base, 0f))
        }

        val requestedIndices = max(1, (requestedSpanStops / step).roundToInt())
        val targets = linkedSetOf(
            (base - requestedIndices).coerceIn(supportedRange.first, supportedRange.last),
            base,
            (base + requestedIndices).coerceIn(supportedRange.first, supportedRange.last),
        )

        if (targets.size < 3) {
            targets += supportedRange.first
            targets += supportedRange.last
            var radius = 1
            while (targets.size < 3 && radius <= supportedRange.last - supportedRange.first) {
                targets += (base - radius).coerceIn(supportedRange.first, supportedRange.last)
                targets += (base + radius).coerceIn(supportedRange.first, supportedRange.last)
                radius++
            }
        }

        return targets
            .sorted()
            .take(3)
            .map { index -> HdrBracketStep(index, (index - base) * step) }
    }

    fun planManual(
        baseShutterNs: Long,
        supportedRange: LongRange,
        requestedSpanStops: Float = 2f,
    ): List<Pair<Long, Float>> {
        if (baseShutterNs <= 0L || supportedRange.first > supportedRange.last) return emptyList()
        val base = baseShutterNs.coerceIn(supportedRange.first, supportedRange.last)
        return listOf(-requestedSpanStops, 0f, requestedSpanStops)
            .map { ev ->
                val shutter = (base.toDouble() * 2.0.pow(ev.toDouble()))
                    .roundToLongSafe()
                    .coerceIn(supportedRange.first, supportedRange.last)
                shutter to log2(shutter.toDouble() / base.toDouble()).toFloat()
            }
            .distinctBy { it.first }
            .sortedBy { it.first }
    }

    private fun Double.roundToLongSafe(): Long = when {
        isNaN() -> 0L
        this >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
        this <= Long.MIN_VALUE.toDouble() -> Long.MIN_VALUE
        else -> kotlin.math.round(this).toLong()
    }
}

object HdrMath {
    private const val EPSILON = 1e-6f

    fun srgbToLinear(value: Float): Float {
        val v = value.coerceAtLeast(0f)
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    fun linearToSrgb(value: Float): Float {
        val v = value.coerceAtLeast(0f)
        return if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
    }

    fun linearLuma(r: Float, g: Float, b: Float): Float =
        0.2126f * r + 0.7152f * g + 0.0722f * b

    fun wellExposedWeight(encodedLuma: Float): Float {
        val l = encodedLuma.coerceIn(0f, 1f)
        if (l <= 0.008f || l >= 0.992f) return 0.002f
        val distance = (l - 0.50f) / 0.24f
        return 0.025f + exp(-0.5f * distance * distance)
    }

    fun encodedChannelReliability(r: Float, g: Float, b: Float): Float {
        val maximum = max(r, max(g, b)).coerceIn(0f, 1f)
        val shadow = smoothstep(0.006f, 0.055f, maximum)
        val highlight = 1f - smoothstep(0.94f, 0.995f, maximum)
        return (0.015f + 0.985f * shadow * highlight).coerceIn(0.015f, 1f)
    }

    /** Strong disagreement is allowed to reach zero rather than leave a ghost floor. */
    fun deghostWeight(referenceRadianceLuma: Float, candidateRadianceLuma: Float): Float {
        val differenceStops = abs(
            log2((candidateRadianceLuma + EPSILON) / (referenceRadianceLuma + EPSILON)),
        )
        val normalized = differenceStops / 0.42f
        return exp(-0.5f * normalized * normalized).coerceIn(0f, 1f)
    }

    /** How badly the reference JPEG needs a darker exposure for highlight recovery. */
    fun highlightRecoveryNeed(maxEncodedChannel: Float): Float =
        smoothstep(0.88f, 0.985f, maxEncodedChannel)

    /** How badly the reference JPEG needs a brighter exposure for shadow recovery. */
    fun shadowRecoveryNeed(encodedLuma: Float): Float =
        1f - smoothstep(0.018f, 0.13f, encodedLuma)

    /** Suppresses exposure replacement on edges, where small registration errors become double outlines. */
    fun flatRegionGate(edgeStrength: Float): Float =
        1f - smoothstep(0.018f, 0.10f, edgeStrength)

    fun toneMapLuma(value: Float, whitePoint: Float, mode: HdrToneMap): Float {
        val x = value.coerceAtLeast(0f)
        val white = whitePoint.coerceAtLeast(1.01f)
        return when (mode) {
            HdrToneMap.NATURAL -> {
                val mapped = x * (1f + x / (white * white)) / (1f + x)
                mapped.coerceIn(0f, 1f)
            }
            HdrToneMap.FILMIC -> {
                val exposureBias = (0.75f * (6f / white).pow(0.08f)).coerceIn(0.62f, 0.88f)
                val n = x * exposureBias
                val mapped = (n * (2.51f * n + 0.03f)) /
                    (n * (2.43f * n + 0.59f) + 0.14f)
                mapped.coerceIn(0f, 1f)
            }
            HdrToneMap.LOW_CONTRAST -> {
                val strength = 4f
                val denominator = ln(1f + strength * white)
                if (denominator <= EPSILON) {
                    0f
                } else {
                    (ln(1f + strength * x) / denominator).coerceIn(0f, 1f)
                }
            }
        }
    }

    fun percentile(sortedValues: FloatArray, percentile: Float): Float {
        if (sortedValues.isEmpty()) return 0f
        val p = percentile.coerceIn(0f, 1f)
        val position = p * (sortedValues.size - 1)
        val low = position.toInt()
        val high = min(sortedValues.lastIndex, low + 1)
        val fraction = position - low
        return sortedValues[low] * (1f - fraction) + sortedValues[high] * fraction
    }

    fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        if (edge0 == edge1) return if (value < edge0) 0f else 1f
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/**
 * Median-threshold alignment, the exposure-invariant strategy traditionally
 * used for HDR brackets. It compares only pixels safely away from each image's
 * median and rejects ambiguous or boundary-hitting estimates.
 */
object HdrTranslationEstimator {
    fun estimate(
        reference: FloatArray,
        candidate: FloatArray,
        width: Int,
        height: Int,
        maxShift: Int = 8,
        sampleStep: Int = 2,
    ): PixelShift = estimateDetailed(
        reference = reference,
        candidate = candidate,
        width = width,
        height = height,
        maxShift = maxShift,
        sampleStep = sampleStep,
    ).shift

    fun estimateDetailed(
        reference: FloatArray,
        candidate: FloatArray,
        width: Int,
        height: Int,
        maxShift: Int = 8,
        sampleStep: Int = 2,
    ): AlignmentEstimate {
        require(reference.size == width * height)
        require(candidate.size == width * height)
        if (width < 24 || height < 24) {
            return AlignmentEstimate(PixelShift(0, 0), 0f, 1f, 0f)
        }

        val referenceMedian = median(reference)
        val candidateMedian = median(candidate)
        val exclusionBand = 0.09f
        val step = sampleStep.coerceAtLeast(1)
        val border = maxShift + 2
        var bestShift = PixelShift(0, 0)
        var bestError = Float.POSITIVE_INFINITY
        var bestValidFraction = 0f
        var secondError = Float.POSITIVE_INFINITY
        var zeroError = Float.POSITIVE_INFINITY

        for (dy in -maxShift..maxShift) {
            for (dx in -maxShift..maxShift) {
                var mismatches = 0
                var valid = 0
                var total = 0
                var y = border
                while (y < height - border) {
                    val cy = y + dy
                    var x = border
                    while (x < width - border) {
                        val cx = x + dx
                        val rv = reference[y * width + x]
                        val cv = candidate[cy * width + cx]
                        total++
                        if (abs(rv - referenceMedian) > exclusionBand &&
                            abs(cv - candidateMedian) > exclusionBand
                        ) {
                            valid++
                            if ((rv > referenceMedian) != (cv > candidateMedian)) mismatches++
                        }
                        x += step
                    }
                    y += step
                }
                val validFraction = if (total == 0) 0f else valid.toFloat() / total
                val error = if (valid == 0) {
                    Float.POSITIVE_INFINITY
                } else {
                    mismatches.toFloat() / valid + (1f - validFraction) * 0.04f
                }
                if (dx == 0 && dy == 0) zeroError = error
                if (error < bestError) {
                    secondError = bestError
                    bestError = error
                    bestShift = PixelShift(dx, dy)
                    bestValidFraction = validFraction
                } else if (error < secondError &&
                    (abs(dx - bestShift.dx) > 1 || abs(dy - bestShift.dy) > 1)
                ) {
                    secondError = error
                }
            }
        }

        if (!bestError.isFinite()) {
            return AlignmentEstimate(PixelShift(0, 0), 0f, 1f, bestValidFraction)
        }
        val boundaryHit = abs(bestShift.dx) == maxShift || abs(bestShift.dy) == maxShift
        val quality = 1f - HdrMath.smoothstep(0.16f, 0.38f, bestError)
        val distinct = if (secondError.isFinite()) {
            HdrMath.smoothstep(0.008f, 0.07f, secondError - bestError)
        } else {
            1f
        }
        val improvement = if (bestShift == PixelShift(0, 0)) {
            1f
        } else if (zeroError.isFinite()) {
            HdrMath.smoothstep(0.008f, 0.10f, zeroError - bestError)
        } else {
            0f
        }
        val coverage = HdrMath.smoothstep(0.08f, 0.28f, bestValidFraction)
        val confidence = if (boundaryHit) 0f else (quality * distinct * improvement * coverage)
            .coerceIn(0f, 1f)
        return if (confidence >= 0.25f) {
            AlignmentEstimate(bestShift, confidence, bestError, bestValidFraction)
        } else {
            AlignmentEstimate(PixelShift(0, 0), 0f, bestError, bestValidFraction)
        }
    }

    private fun median(values: FloatArray): Float {
        val copy = values.copyOf()
        copy.sort()
        return if (copy.isEmpty()) 0f else copy[copy.size / 2]
    }
}

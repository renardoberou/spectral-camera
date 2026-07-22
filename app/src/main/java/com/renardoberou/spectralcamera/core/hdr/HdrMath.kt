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
import kotlin.math.sqrt

data class HdrBracketStep(
    val compensationIndex: Int,
    val evOffset: Float,
)

data class PixelShift(val dx: Int, val dy: Int)

/** Pure bracket planning shared by auto-exposure capture and JVM tests. */
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

/** Testable transfer, weighting, and tone-map functions used by the HDR merger. */
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

    /** Weight peaks in the reliable middle of an encoded exposure. */
    fun wellExposedWeight(encodedLuma: Float): Float {
        val l = encodedLuma.coerceIn(0f, 1f)
        if (l <= 0.008f || l >= 0.992f) return 0.002f
        val distance = (l - 0.50f) / 0.24f
        return 0.025f + exp(-0.5f * distance * distance)
    }

    /**
     * Whole-pixel reliability guard. Luma alone misses a clipped single colour
     * channel, which can inject false colour into Aerochrome classification.
     */
    fun encodedChannelReliability(r: Float, g: Float, b: Float): Float {
        val maximum = max(r, max(g, b)).coerceIn(0f, 1f)
        val shadow = smoothstep(0.006f, 0.055f, maximum)
        val highlight = 1f - smoothstep(0.94f, 0.995f, maximum)
        return (0.015f + 0.985f * shadow * highlight).coerceIn(0.015f, 1f)
    }

    /** Downweight moving or misregistered content toward the reference exposure. */
    fun deghostWeight(referenceRadianceLuma: Float, candidateRadianceLuma: Float): Float {
        val differenceStops = abs(
            log2((candidateRadianceLuma + EPSILON) / (referenceRadianceLuma + EPSILON)),
        )
        val normalized = differenceStops / 0.80f
        return (0.02f + 0.98f * exp(-0.5f * normalized * normalized)).coerceIn(0.02f, 1f)
    }

    /**
     * Global luminance mappings. The HDR normalization stage keys scene median
     * near 0.18, so every curve deliberately leaves middle grey in a useful
     * photographic range before the stock-specific film curve runs.
     */
    fun toneMapLuma(value: Float, whitePoint: Float, mode: HdrToneMap): Float {
        val x = value.coerceAtLeast(0f)
        val white = whitePoint.coerceAtLeast(1.01f)
        return when (mode) {
            HdrToneMap.NATURAL -> {
                val mapped = x * (1f + x / (white * white)) / (1f + x)
                mapped.coerceIn(0f, 1f)
            }
            HdrToneMap.FILMIC -> {
                // Luminance-only ACES approximation with a conservative exposure
                // bias. Unlike x/white normalization, this keeps 18% grey from
                // collapsing toward black when the scene white point is high.
                val exposureBias = (0.75f * (6f / white).pow(0.08f)).coerceIn(0.62f, 0.88f)
                val n = x * exposureBias
                val mapped = (n * (2.51f * n + 0.03f)) /
                    (n * (2.43f * n + 0.59f) + 0.14f)
                mapped.coerceIn(0f, 1f)
            }
            HdrToneMap.LOW_CONTRAST -> {
                // A stronger log compression with a four-times exposure scale
                // preserves keyed middle grey while fitting severe highlights.
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
 * Translation-only alignment on log-radiance thumbnails. Exposure-normalized
 * log luminance makes the estimate insensitive to the bracket brightness.
 */
object HdrTranslationEstimator {
    fun estimate(
        reference: FloatArray,
        candidate: FloatArray,
        width: Int,
        height: Int,
        maxShift: Int = 12,
        sampleStep: Int = 2,
    ): PixelShift {
        require(reference.size == width * height)
        require(candidate.size == width * height)
        if (width < 16 || height < 16) return PixelShift(0, 0)

        var best = PixelShift(0, 0)
        var bestScore = Float.POSITIVE_INFINITY
        val border = maxShift + 2
        val step = sampleStep.coerceAtLeast(1)

        for (dy in -maxShift..maxShift) {
            for (dx in -maxShift..maxShift) {
                var sum = 0f
                var sumSquares = 0f
                var count = 0
                var y = border
                while (y < height - border) {
                    val cy = y + dy
                    var x = border
                    while (x < width - border) {
                        val cx = x + dx
                        val difference = reference[y * width + x] - candidate[cy * width + cx]
                        val robust = abs(difference).coerceAtMost(1.5f)
                        sum += robust
                        sumSquares += robust * robust
                        count++
                        x += step
                    }
                    y += step
                }
                if (count == 0) continue
                val mean = sum / count
                val rms = sqrt(sumSquares / count)
                val score = mean + rms * 0.15f
                if (score < bestScore) {
                    bestScore = score
                    best = PixelShift(dx, dy)
                }
            }
        }
        return best
    }
}

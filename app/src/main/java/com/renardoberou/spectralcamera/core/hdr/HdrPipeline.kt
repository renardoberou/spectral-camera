package com.renardoberou.spectralcamera.core.hdr

import android.graphics.Bitmap
import com.renardoberou.spectralcamera.core.HdrToneMap
import com.renardoberou.spectralcamera.core.camera.CapturedExposure
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Low-resolution logarithmic headroom field used to build the processed gain map. */
data class HdrGainField(
    val width: Int,
    val height: Int,
    val stops: FloatArray,
    val maxStops: Float,
) {
    init {
        require(width > 0 && height > 0)
        require(stops.size == width * height)
        require(maxStops > 0f)
    }

    fun sampleNormalized(x: Float, y: Float): Float {
        val px = (x.coerceIn(0f, 1f) * (width - 1)).coerceIn(0f, (width - 1).toFloat())
        val py = (y.coerceIn(0f, 1f) * (height - 1)).coerceIn(0f, (height - 1).toFloat())
        val x0 = px.toInt()
        val y0 = py.toInt()
        val x1 = min(width - 1, x0 + 1)
        val y1 = min(height - 1, y0 + 1)
        val fx = px - x0
        val fy = py - y0
        val top = stops[y0 * width + x0] * (1f - fx) + stops[y0 * width + x1] * fx
        val bottom = stops[y1 * width + x0] * (1f - fx) + stops[y1 * width + x1] * fx
        return top * (1f - fy) + bottom * fy
    }
}

data class HdrMergeResult(
    val workingBitmap: Bitmap,
    val gainField: HdrGainField,
    val alignment: List<PixelShift>,
    val dynamicRangeStops: Float,
    val whitePoint: Float,
)

/**
 * Computational HDR stage.
 *
 * JPEG brackets are inverse-transferred to an exposure-normalized linear-light
 * radiance estimate. This is materially better than encoded-RGB averaging but
 * remains an approximation to true sensor-linear RAW because the phone ISP has
 * already applied camera response, white balance and local processing.
 */
object HdrPipeline {
    private const val THUMB_MAX_EDGE = 240
    private const val SAMPLE_STEP = 16
    private const val MAX_GAIN_STOPS = 4f
    private const val EPSILON = 1e-6f

    fun merge(
        frames: List<CapturedExposure>,
        referenceIndex: Int,
        toneMap: HdrToneMap,
    ): HdrMergeResult {
        require(frames.size >= 2) { "HDR merge requires at least two exposures" }
        require(referenceIndex in frames.indices)
        val width = frames.first().bitmap.width
        val height = frames.first().bitmap.height
        require(frames.all { it.bitmap.width == width && it.bitmap.height == height }) {
            "HDR exposures must have identical prepared dimensions"
        }

        val alignment = estimateAlignment(frames, referenceIndex, width, height)
        val crop = commonCrop(width, height, alignment)
        require(crop.width > 32 && crop.height > 32) { "HDR alignment left no usable common image area" }

        val exposureScales = FloatArray(frames.size) { index -> 2f.pow(frames[index].evOffset) }
        val sampleValues = sampleRadianceLuma(frames, alignment, crop, exposureScales, referenceIndex)
        sampleValues.sort()
        val black = HdrMath.percentile(sampleValues, 0.005f).coerceAtLeast(0f)
        val median = HdrMath.percentile(sampleValues, 0.50f)
        val high = HdrMath.percentile(sampleValues, 0.997f)
        val low = HdrMath.percentile(sampleValues, 0.01f).coerceAtLeast(EPSILON)
        val usableMedian = (median - black).coerceAtLeast(0.015f)
        val keyScale = (0.18f / usableMedian).coerceIn(0.30f, 6.0f)
        val whitePoint = ((high - black).coerceAtLeast(usableMedian) * keyScale).coerceIn(1.10f, 16f)
        val dynamicRange = log2((high + EPSILON) / (low + EPSILON)).coerceIn(0f, 20f)

        val gainScale = min(0.25f, THUMB_MAX_EDGE * 4f / max(crop.width, crop.height).toFloat())
            .coerceAtLeast(1f / max(crop.width, crop.height).toFloat())
        val gainWidth = max(1, (crop.width * gainScale).roundToInt())
        val gainHeight = max(1, (crop.height * gainScale).roundToInt())
        val gainSums = FloatArray(gainWidth * gainHeight)
        val gainCounts = IntArray(gainWidth * gainHeight)
        val rows = Array(frames.size) { IntArray(crop.width) }
        val radiance = FloatArray(4)
        val outputRow = IntArray(crop.width)
        val bitmap = Bitmap.createBitmap(crop.width, crop.height, Bitmap.Config.ARGB_8888)

        // Write one row directly into the Bitmap instead of holding a second
        // full-frame IntArray. At 12 MP this removes roughly 48 MB of peak heap.
        for (y in 0 until crop.height) {
            loadAlignedRows(frames, alignment, crop, y, rows)
            for (x in 0 until crop.width) {
                mergeRadiance(rows, x, exposureScales, referenceIndex, radiance)
                val sceneLuma = radiance[3]
                val normalizedLuma = (sceneLuma - black).coerceAtLeast(0f) * keyScale
                val sceneScale = if (sceneLuma > EPSILON) normalizedLuma / sceneLuma else 0f
                val normalizedR = radiance[0] * sceneScale
                val normalizedG = radiance[1] * sceneScale
                val normalizedB = radiance[2] * sceneScale
                val mappedLuma = HdrMath.toneMapLuma(normalizedLuma, whitePoint, toneMap)
                val toneScale = if (normalizedLuma > EPSILON) mappedLuma / normalizedLuma else 0f
                val mappedR = normalizedR * toneScale
                val mappedG = normalizedG * toneScale
                val mappedB = normalizedB * toneScale

                val r = (HdrMath.linearToSrgb(mappedR).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val g = (HdrMath.linearToSrgb(mappedG).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val b = (HdrMath.linearToSrgb(mappedB).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                outputRow[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

                val gainStops = log2((normalizedLuma + EPSILON) / (mappedLuma + EPSILON))
                    .coerceIn(0f, MAX_GAIN_STOPS)
                val gx = min(gainWidth - 1, x * gainWidth / crop.width)
                val gy = min(gainHeight - 1, y * gainHeight / crop.height)
                val gainIndex = gy * gainWidth + gx
                gainSums[gainIndex] += gainStops
                gainCounts[gainIndex]++
            }
            bitmap.setPixels(outputRow, 0, crop.width, 0, y, crop.width, 1)
        }

        val gainStops = FloatArray(gainSums.size) { index ->
            if (gainCounts[index] == 0) 0f else gainSums[index] / gainCounts[index]
        }
        return HdrMergeResult(
            workingBitmap = bitmap,
            gainField = HdrGainField(gainWidth, gainHeight, gainStops, MAX_GAIN_STOPS),
            alignment = alignment,
            dynamicRangeStops = dynamicRange,
            whitePoint = whitePoint,
        )
    }

    private fun estimateAlignment(
        frames: List<CapturedExposure>,
        referenceIndex: Int,
        fullWidth: Int,
        fullHeight: Int,
    ): List<PixelShift> {
        val thumbSize = thumbnailSize(fullWidth, fullHeight)
        val logLuma = frames.map { frame -> logRadianceThumbnail(frame, thumbSize.first, thumbSize.second) }
        val reference = logLuma[referenceIndex]
        return frames.indices.map { index ->
            if (index == referenceIndex) {
                PixelShift(0, 0)
            } else {
                val thumbShift = HdrTranslationEstimator.estimate(
                    reference = reference,
                    candidate = logLuma[index],
                    width = thumbSize.first,
                    height = thumbSize.second,
                    maxShift = 12,
                    sampleStep = 2,
                )
                val fullDx = (thumbShift.dx * fullWidth.toFloat() / thumbSize.first).roundToInt()
                val fullDy = (thumbShift.dy * fullHeight.toFloat() / thumbSize.second).roundToInt()
                PixelShift(
                    dx = fullDx.coerceIn(-fullWidth / 16, fullWidth / 16),
                    dy = fullDy.coerceIn(-fullHeight / 16, fullHeight / 16),
                )
            }
        }
    }

    private fun thumbnailSize(width: Int, height: Int): Pair<Int, Int> {
        val scale = min(1f, THUMB_MAX_EDGE.toFloat() / max(width, height).toFloat())
        return max(16, (width * scale).roundToInt()) to max(16, (height * scale).roundToInt())
    }

    private fun logRadianceThumbnail(frame: CapturedExposure, width: Int, height: Int): FloatArray {
        val source = frame.bitmap
        val scaled = if (source.width == width && source.height == height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, width, height, true)
        }
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== source) scaled.recycle()
        val exposureScale = 2f.pow(frame.evOffset)
        return FloatArray(pixels.size) { index ->
            val pixel = pixels[index]
            val r = HdrMath.srgbToLinear(((pixel ushr 16) and 0xFF) / 255f) / exposureScale
            val g = HdrMath.srgbToLinear(((pixel ushr 8) and 0xFF) / 255f) / exposureScale
            val b = HdrMath.srgbToLinear((pixel and 0xFF) / 255f) / exposureScale
            ln(HdrMath.linearLuma(r, g, b).coerceAtLeast(1e-5f))
        }
    }

    private data class CommonCrop(val left: Int, val top: Int, val width: Int, val height: Int)

    private fun commonCrop(width: Int, height: Int, shifts: List<PixelShift>): CommonCrop {
        var left = 0
        var top = 0
        var right = width
        var bottom = height
        shifts.forEach { shift ->
            left = max(left, -shift.dx)
            top = max(top, -shift.dy)
            right = min(right, width - shift.dx)
            bottom = min(bottom, height - shift.dy)
        }
        return CommonCrop(left, top, right - left, bottom - top)
    }

    private fun sampleRadianceLuma(
        frames: List<CapturedExposure>,
        alignment: List<PixelShift>,
        crop: CommonCrop,
        exposureScales: FloatArray,
        referenceIndex: Int,
    ): FloatArray {
        val sampleWidth = ceil(crop.width / SAMPLE_STEP.toDouble()).toInt()
        val sampleHeight = ceil(crop.height / SAMPLE_STEP.toDouble()).toInt()
        val values = FloatArray(sampleWidth * sampleHeight)
        val rows = Array(frames.size) { IntArray(crop.width) }
        val radiance = FloatArray(4)
        var count = 0
        var y = 0
        while (y < crop.height) {
            loadAlignedRows(frames, alignment, crop, y, rows)
            var x = 0
            while (x < crop.width) {
                mergeRadiance(rows, x, exposureScales, referenceIndex, radiance)
                values[count++] = radiance[3]
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        return values.copyOf(count)
    }

    private fun loadAlignedRows(
        frames: List<CapturedExposure>,
        alignment: List<PixelShift>,
        crop: CommonCrop,
        outputY: Int,
        rows: Array<IntArray>,
    ) {
        frames.indices.forEach { index ->
            val sourceX = crop.left + alignment[index].dx
            val sourceY = crop.top + outputY + alignment[index].dy
            frames[index].bitmap.getPixels(
                rows[index],
                0,
                crop.width,
                sourceX,
                sourceY,
                crop.width,
                1,
            )
        }
    }

    /** Writes linear radiance r, g, b and luma into [out]. */
    private fun mergeRadiance(
        rows: Array<IntArray>,
        x: Int,
        exposureScales: FloatArray,
        referenceIndex: Int,
        out: FloatArray,
    ) {
        val referencePixel = rows[referenceIndex][x]
        val referenceScale = exposureScales[referenceIndex]
        val referenceR = HdrMath.srgbToLinear(((referencePixel ushr 16) and 0xFF) / 255f) / referenceScale
        val referenceG = HdrMath.srgbToLinear(((referencePixel ushr 8) and 0xFF) / 255f) / referenceScale
        val referenceB = HdrMath.srgbToLinear((referencePixel and 0xFF) / 255f) / referenceScale
        val referenceLuma = HdrMath.linearLuma(referenceR, referenceG, referenceB)

        var sumR = 0f
        var sumG = 0f
        var sumB = 0f
        var sumWeight = 0f
        rows.indices.forEach { index ->
            val pixel = rows[index][x]
            val sr = ((pixel ushr 16) and 0xFF) / 255f
            val sg = ((pixel ushr 8) and 0xFF) / 255f
            val sb = (pixel and 0xFF) / 255f
            val linearR = HdrMath.srgbToLinear(sr)
            val linearG = HdrMath.srgbToLinear(sg)
            val linearB = HdrMath.srgbToLinear(sb)
            val encodedLuma = 0.2126f * sr + 0.7152f * sg + 0.0722f * sb
            val scale = exposureScales[index]
            val radianceR = linearR / scale
            val radianceG = linearG / scale
            val radianceB = linearB / scale
            val radianceLuma = HdrMath.linearLuma(radianceR, radianceG, radianceB)
            var weight = HdrMath.wellExposedWeight(encodedLuma) *
                HdrMath.encodedChannelReliability(sr, sg, sb)
            if (index == referenceIndex) {
                // Reference is the stable fallback, but a clipped reference must
                // not overpower a well-exposed bracket frame.
                weight = max(weight, 0.12f * HdrMath.encodedChannelReliability(sr, sg, sb))
            } else {
                weight *= HdrMath.deghostWeight(referenceLuma, radianceLuma)
            }
            sumR += radianceR * weight
            sumG += radianceG * weight
            sumB += radianceB * weight
            sumWeight += weight
        }

        if (sumWeight <= EPSILON) {
            out[0] = referenceR
            out[1] = referenceG
            out[2] = referenceB
        } else {
            out[0] = sumR / sumWeight
            out[1] = sumG / sumWeight
            out[2] = sumB / sumWeight
        }
        out[3] = HdrMath.linearLuma(out[0], out[1], out[2])
    }
}

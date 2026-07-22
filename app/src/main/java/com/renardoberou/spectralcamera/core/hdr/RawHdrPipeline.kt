package com.renardoberou.spectralcamera.core.hdr

import android.graphics.Bitmap
import android.graphics.Matrix
import com.renardoberou.spectralcamera.core.HdrToneMap
import com.renardoberou.spectralcamera.core.camera.RawSensorFrame
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Result of the sensor-linear Bayer-domain HDR path. */
data class RawHdrMergeResult(
    val workingBitmap: Bitmap,
    val gainField: HdrGainField,
    val alignmentRawPixels: List<PixelShift>,
    val dynamicRangeStops: Float,
    val whitePoint: Float,
)

/**
 * True RAW HDR path.
 *
 * RAW_SENSOR code values are black-subtracted, white-level normalized and
 * exposure-normalized while still in the Bayer mosaic. Translation alignment
 * is constrained to whole 2x2 CFA cells so every merged sample remains the same
 * colour channel. Fusion therefore occurs before demosaic and colour conversion.
 */
object RawHdrPipeline {
    private const val THUMB_MAX_EDGE = 220
    private const val SAMPLE_CELL_STEP = 12
    private const val MAX_GAIN_STOPS = 5f
    private const val EPSILON = 1e-7f

    fun merge(
        frames: List<RawSensorFrame>,
        referenceIndex: Int,
        toneMap: HdrToneMap,
        frontFacing: Boolean,
    ): RawHdrMergeResult {
        require(frames.size >= 2) { "RAW HDR needs at least two sensor frames" }
        require(referenceIndex in frames.indices)
        val reference = frames[referenceIndex]
        val arrangement = BayerArrangement.fromCameraValue(reference.cfaArrangement)
            ?: throw IllegalArgumentException("Unsupported non-Bayer RAW arrangement: ${reference.cfaArrangement}")
        require(frames.all {
            it.width == reference.width &&
                it.height == reference.height &&
                it.cfaArrangement == reference.cfaArrangement
        }) { "RAW HDR frames must share dimensions and CFA arrangement" }

        val cellShifts = estimateCellAlignment(frames, referenceIndex, arrangement)
        val shifts = cellShifts.map { PixelShift(it.dx * 2, it.dy * 2) }
        val crop = commonEvenCrop(frames, shifts)
        require(crop.width >= 8 && crop.height >= 8) { "RAW alignment left no usable active area" }

        val referenceExposure = RawHdrMath.exposureProduct(
            reference.exposureTimeNs,
            reference.sensitivityIso,
        )
        val exposureScales = DoubleArray(frames.size) { index ->
            RawHdrMath.exposureProduct(
                frames[index].exposureTimeNs,
                frames[index].sensitivityIso,
            ) / referenceExposure
        }

        val samples = sampleMergedLuma(
            frames = frames,
            shifts = shifts,
            crop = crop,
            exposureScales = exposureScales,
            referenceIndex = referenceIndex,
            arrangement = arrangement,
            reference = reference,
        )
        samples.sort()
        val black = HdrMath.percentile(samples, 0.003f).coerceAtLeast(0f)
        val median = HdrMath.percentile(samples, 0.50f)
        val high = HdrMath.percentile(samples, 0.998f)
        val low = HdrMath.percentile(samples, 0.01f).coerceAtLeast(EPSILON)
        val usableMedian = (median - black).coerceAtLeast(0.01f)
        val keyScale = (0.18f / usableMedian).coerceIn(0.20f, 12f)
        val whitePoint = ((high - black).coerceAtLeast(usableMedian) * keyScale)
            .coerceIn(1.10f, 32f)
        val dynamicRange = log2((high + EPSILON) / (low + EPSILON)).coerceIn(0f, 24f)

        // Bilinear demosaic consumes one pixel of border on every side.
        val outputWidth = crop.width - 2
        val outputHeight = crop.height - 2
        val gainScale = min(0.20f, 720f / max(outputWidth, outputHeight).toFloat())
            .coerceAtLeast(1f / max(outputWidth, outputHeight).toFloat())
        val gainWidth = max(1, (outputWidth * gainScale).roundToInt())
        val gainHeight = max(1, (outputHeight * gainScale).roundToInt())
        val gainSums = FloatArray(gainWidth * gainHeight)
        val gainCounts = IntArray(gainWidth * gainHeight)

        val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val outputRow = IntArray(outputWidth)
        var previous = mergedMosaicRow(frames, shifts, crop, 0, exposureScales, referenceIndex)
        var current = mergedMosaicRow(frames, shifts, crop, 1, exposureScales, referenceIndex)
        var next = mergedMosaicRow(frames, shifts, crop, 2, exposureScales, referenceIndex)
        val greenGain = (reference.whiteBalanceGains[1] + reference.whiteBalanceGains[2]) * 0.5f

        for (outY in 0 until outputHeight) {
            val rawY = crop.top + outY + 1
            for (outX in 0 until outputWidth) {
                val rawX = crop.left + outX + 1
                val localX = outX + 1
                val rgb = demosaicAt(
                    arrangement = arrangement,
                    rawX = rawX,
                    rawY = rawY,
                    localX = localX,
                    previous = previous,
                    current = current,
                    next = next,
                )
                val sensorR = rgb[0] * reference.whiteBalanceGains[0]
                val sensorG = rgb[1] * greenGain
                val sensorB = rgb[2] * reference.whiteBalanceGains[3]
                val transformed = RawHdrMath.transformLinearRgb(
                    sensorR,
                    sensorG,
                    sensorB,
                    reference.colorTransform,
                )
                val linearR = transformed[0].coerceAtLeast(0f)
                val linearG = transformed[1].coerceAtLeast(0f)
                val linearB = transformed[2].coerceAtLeast(0f)
                val sceneLuma = HdrMath.linearLuma(linearR, linearG, linearB)
                val normalizedLuma = (sceneLuma - black).coerceAtLeast(0f) * keyScale
                val sceneScale = if (sceneLuma > EPSILON) normalizedLuma / sceneLuma else 0f
                val mappedLuma = HdrMath.toneMapLuma(normalizedLuma, whitePoint, toneMap)
                val toneScale = if (normalizedLuma > EPSILON) mappedLuma / normalizedLuma else 0f
                val totalScale = sceneScale * toneScale
                val r = (HdrMath.linearToSrgb(linearR * totalScale).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val g = (HdrMath.linearToSrgb(linearG * totalScale).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val b = (HdrMath.linearToSrgb(linearB * totalScale).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                outputRow[outX] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

                val gainStops = log2((normalizedLuma + EPSILON) / (mappedLuma + EPSILON))
                    .coerceIn(0f, MAX_GAIN_STOPS)
                val gx = min(gainWidth - 1, outX * gainWidth / outputWidth)
                val gy = min(gainHeight - 1, outY * gainHeight / outputHeight)
                val gainIndex = gy * gainWidth + gx
                gainSums[gainIndex] += gainStops
                gainCounts[gainIndex]++
            }
            output.setPixels(outputRow, 0, outputWidth, 0, outY, outputWidth, 1)
            if (outY < outputHeight - 1) {
                previous = current
                current = next
                next = mergedMosaicRow(
                    frames,
                    shifts,
                    crop,
                    outY + 3,
                    exposureScales,
                    referenceIndex,
                )
            }
        }

        val gainStops = FloatArray(gainSums.size) { index ->
            if (gainCounts[index] == 0) 0f else gainSums[index] / gainCounts[index]
        }
        val upright = rotateAndMirror(output, reference.rotationDegrees, frontFacing)
        return RawHdrMergeResult(
            workingBitmap = upright,
            gainField = HdrGainField(gainWidth, gainHeight, gainStops, MAX_GAIN_STOPS),
            alignmentRawPixels = shifts,
            dynamicRangeStops = dynamicRange,
            whitePoint = whitePoint,
        )
    }

    private data class RawCrop(val left: Int, val top: Int, val width: Int, val height: Int)

    private fun commonEvenCrop(frames: List<RawSensorFrame>, shifts: List<PixelShift>): RawCrop {
        var left = Int.MIN_VALUE
        var top = Int.MIN_VALUE
        var right = Int.MAX_VALUE
        var bottom = Int.MAX_VALUE
        frames.indices.forEach { index ->
            val frame = frames[index]
            val shift = shifts[index]
            left = max(left, frame.cropLeft - shift.dx)
            top = max(top, frame.cropTop - shift.dy)
            right = min(right, frame.cropLeft + frame.cropWidth - shift.dx)
            bottom = min(bottom, frame.cropTop + frame.cropHeight - shift.dy)
        }
        left = if ((left and 1) == 0) left else left + 1
        top = if ((top and 1) == 0) top else top + 1
        right = if ((right and 1) == 0) right else right - 1
        bottom = if ((bottom and 1) == 0) bottom else bottom - 1
        return RawCrop(left, top, right - left, bottom - top)
    }

    private fun estimateCellAlignment(
        frames: List<RawSensorFrame>,
        referenceIndex: Int,
        arrangement: BayerArrangement,
    ): List<PixelShift> {
        val reference = frames[referenceIndex]
        val cellWidth = reference.cropWidth / 2
        val cellHeight = reference.cropHeight / 2
        val scale = min(1f, THUMB_MAX_EDGE.toFloat() / max(cellWidth, cellHeight).toFloat())
        val thumbWidth = max(16, (cellWidth * scale).roundToInt())
        val thumbHeight = max(16, (cellHeight * scale).roundToInt())
        val thumbs = frames.map { rawLogLumaThumbnail(it, arrangement, thumbWidth, thumbHeight) }
        val referenceThumb = thumbs[referenceIndex]
        return frames.indices.map { index ->
            if (index == referenceIndex) {
                PixelShift(0, 0)
            } else {
                val thumbShift = HdrTranslationEstimator.estimate(
                    reference = referenceThumb,
                    candidate = thumbs[index],
                    width = thumbWidth,
                    height = thumbHeight,
                    maxShift = 12,
                    sampleStep = 2,
                )
                PixelShift(
                    dx = (thumbShift.dx * cellWidth.toFloat() / thumbWidth).roundToInt()
                        .coerceIn(-cellWidth / 16, cellWidth / 16),
                    dy = (thumbShift.dy * cellHeight.toFloat() / thumbHeight).roundToInt()
                        .coerceIn(-cellHeight / 16, cellHeight / 16),
                )
            }
        }
    }

    private fun rawLogLumaThumbnail(
        frame: RawSensorFrame,
        arrangement: BayerArrangement,
        width: Int,
        height: Int,
    ): FloatArray {
        val exposure = RawHdrMath.exposureProduct(frame.exposureTimeNs, frame.sensitivityIso)
        val greenGain = (frame.whiteBalanceGains[1] + frame.whiteBalanceGains[2]) * 0.5f
        return FloatArray(width * height) { index ->
            val tx = index % width
            val ty = index / width
            val cellX = (tx + 0.5f) * (frame.cropWidth / 2f) / width
            val cellY = (ty + 0.5f) * (frame.cropHeight / 2f) / height
            val rawX = (frame.cropLeft + cellX.toInt() * 2).coerceIn(0, frame.width - 2)
            val rawY = (frame.cropTop + cellY.toInt() * 2).coerceIn(0, frame.height - 2)
            val cell = cellRgb(frame, arrangement, rawX, rawY)
            val transformed = RawHdrMath.transformLinearRgb(
                cell[0] * frame.whiteBalanceGains[0],
                cell[1] * greenGain,
                cell[2] * frame.whiteBalanceGains[3],
                frame.colorTransform,
            )
            val luma = HdrMath.linearLuma(
                transformed[0].coerceAtLeast(0f),
                transformed[1].coerceAtLeast(0f),
                transformed[2].coerceAtLeast(0f),
            ) / exposure.toFloat().coerceAtLeast(1f)
            ln(luma.coerceAtLeast(1e-8f))
        }
    }

    private fun cellRgb(
        frame: RawSensorFrame,
        arrangement: BayerArrangement,
        x: Int,
        y: Int,
    ): FloatArray {
        var r = 0f
        var g = 0f
        var b = 0f
        var gc = 0
        for (dy in 0..1) {
            for (dx in 0..1) {
                val sx = x + dx
                val sy = y + dy
                val value = normalizedRaw(frame, sx, sy)
                when (RawHdrMath.channelAt(arrangement, sx, sy)) {
                    BayerChannel.RED -> r = value
                    BayerChannel.BLUE -> b = value
                    BayerChannel.GREEN_EVEN,
                    BayerChannel.GREEN_ODD,
                    -> {
                        g += value
                        gc++
                    }
                }
            }
        }
        return floatArrayOf(r, if (gc == 0) 0f else g / gc, b)
    }

    private fun sampleMergedLuma(
        frames: List<RawSensorFrame>,
        shifts: List<PixelShift>,
        crop: RawCrop,
        exposureScales: DoubleArray,
        referenceIndex: Int,
        arrangement: BayerArrangement,
        reference: RawSensorFrame,
    ): FloatArray {
        val sampleWidth = ceil(crop.width / 2.0 / SAMPLE_CELL_STEP).toInt()
        val sampleHeight = ceil(crop.height / 2.0 / SAMPLE_CELL_STEP).toInt()
        val samples = FloatArray(sampleWidth * sampleHeight)
        val greenGain = (reference.whiteBalanceGains[1] + reference.whiteBalanceGains[2]) * 0.5f
        var count = 0
        var y = crop.top
        while (y < crop.top + crop.height - 1) {
            var x = crop.left
            while (x < crop.left + crop.width - 1) {
                var r = 0f
                var g = 0f
                var b = 0f
                var gc = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val sx = x + dx
                        val sy = y + dy
                        val value = mergedRawAt(
                            frames,
                            shifts,
                            sx,
                            sy,
                            exposureScales,
                            referenceIndex,
                        )
                        when (RawHdrMath.channelAt(arrangement, sx, sy)) {
                            BayerChannel.RED -> r = value
                            BayerChannel.BLUE -> b = value
                            BayerChannel.GREEN_EVEN,
                            BayerChannel.GREEN_ODD,
                            -> {
                                g += value
                                gc++
                            }
                        }
                    }
                }
                val transformed = RawHdrMath.transformLinearRgb(
                    r * reference.whiteBalanceGains[0],
                    (if (gc == 0) 0f else g / gc) * greenGain,
                    b * reference.whiteBalanceGains[3],
                    reference.colorTransform,
                )
                samples[count++] = HdrMath.linearLuma(
                    transformed[0].coerceAtLeast(0f),
                    transformed[1].coerceAtLeast(0f),
                    transformed[2].coerceAtLeast(0f),
                )
                x += SAMPLE_CELL_STEP * 2
            }
            y += SAMPLE_CELL_STEP * 2
        }
        return samples.copyOf(count)
    }

    private fun mergedMosaicRow(
        frames: List<RawSensorFrame>,
        shifts: List<PixelShift>,
        crop: RawCrop,
        row: Int,
        exposureScales: DoubleArray,
        referenceIndex: Int,
    ): FloatArray {
        val y = crop.top + row
        return FloatArray(crop.width) { localX ->
            mergedRawAt(
                frames,
                shifts,
                crop.left + localX,
                y,
                exposureScales,
                referenceIndex,
            )
        }
    }

    private fun mergedRawAt(
        frames: List<RawSensorFrame>,
        shifts: List<PixelShift>,
        outputX: Int,
        outputY: Int,
        exposureScales: DoubleArray,
        referenceIndex: Int,
    ): Float {
        val referenceFrame = frames[referenceIndex]
        val referenceShift = shifts[referenceIndex]
        val referenceCode = normalizedRaw(
            referenceFrame,
            outputX + referenceShift.dx,
            outputY + referenceShift.dy,
        )
        val referenceRadiance = referenceCode / exposureScales[referenceIndex].toFloat()
        var weighted = 0f
        var weightSum = 0f
        frames.indices.forEach { index ->
            val frame = frames[index]
            val shift = shifts[index]
            val native = normalizedRaw(frame, outputX + shift.dx, outputY + shift.dy)
            val radiance = native / exposureScales[index].toFloat()
            var weight = RawHdrMath.rawWellExposedWeight(native)
            if (index == referenceIndex) {
                weight = max(weight, 0.10f)
            } else {
                weight *= HdrMath.deghostWeight(referenceRadiance, radiance)
            }
            weighted += radiance * weight
            weightSum += weight
        }
        return if (weightSum <= EPSILON) referenceRadiance else weighted / weightSum
    }

    private fun normalizedRaw(frame: RawSensorFrame, x: Int, y: Int): Float {
        val black = frame.blackLevels[RawHdrMath.parityIndex(x, y)]
        return RawHdrMath.normalizeCode(frame.codeAt(x, y), black, frame.whiteLevel)
    }

    private fun demosaicAt(
        arrangement: BayerArrangement,
        rawX: Int,
        rawY: Int,
        localX: Int,
        previous: FloatArray,
        current: FloatArray,
        next: FloatArray,
    ): FloatArray {
        val center = current[localX]
        val left = current[localX - 1]
        val right = current[localX + 1]
        val up = previous[localX]
        val down = next[localX]
        val ul = previous[localX - 1]
        val ur = previous[localX + 1]
        val dl = next[localX - 1]
        val dr = next[localX + 1]
        return when (RawHdrMath.channelAt(arrangement, rawX, rawY)) {
            BayerChannel.RED -> floatArrayOf(
                center,
                (left + right + up + down) * 0.25f,
                (ul + ur + dl + dr) * 0.25f,
            )
            BayerChannel.BLUE -> floatArrayOf(
                (ul + ur + dl + dr) * 0.25f,
                (left + right + up + down) * 0.25f,
                center,
            )
            BayerChannel.GREEN_EVEN,
            BayerChannel.GREEN_ODD,
            -> {
                val horizontal = RawHdrMath.channelAt(arrangement, rawX - 1, rawY)
                if (horizontal == BayerChannel.RED) {
                    floatArrayOf((left + right) * 0.5f, center, (up + down) * 0.5f)
                } else {
                    floatArrayOf((up + down) * 0.5f, center, (left + right) * 0.5f)
                }
            }
        }
    }

    private fun rotateAndMirror(bitmap: Bitmap, rotationDegrees: Int, frontFacing: Boolean): Bitmap {
        if (rotationDegrees == 0 && !frontFacing) return bitmap
        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
            if (frontFacing) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }
}

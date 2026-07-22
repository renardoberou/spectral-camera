package com.renardoberou.spectralcamera.core.capture

import android.graphics.Bitmap
import com.renardoberou.spectralcamera.core.OutputMode
import com.renardoberou.spectralcamera.core.export.OutputGeometry
import com.renardoberou.spectralcamera.core.export.OutputPipeline
import com.renardoberou.spectralcamera.core.hdr.HdrMath
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Two-frame multiple exposure applied before the spectral film engine.
 *
 * Each source is treated as one stop under a normal exposure, then the two
 * linear-light exposures are added. This is the digital equivalent of making
 * two half-exposures on one frame rather than blending two already-processed
 * film looks.
 */
object DoubleExposurePipeline {
    fun prepareFrame(source: Bitmap, mode: OutputMode): Bitmap {
        val prepared = OutputPipeline.prepareForRender(source, mode)
        val cap = when (mode) {
            OutputMode.FULL_RESOLUTION,
            OutputMode.HQ_1080,
            -> 3072
            OutputMode.FAST_1080 -> 1920
        }
        val longEdge = max(prepared.width, prepared.height)
        if (longEdge <= cap) return prepared
        val scale = cap / longEdge.toFloat()
        return Bitmap.createScaledBitmap(
            prepared,
            (prepared.width * scale).roundToInt().coerceAtLeast(1),
            (prepared.height * scale).roundToInt().coerceAtLeast(1),
            true,
        ).also {
            if (prepared !== source && !prepared.isRecycled) prepared.recycle()
        }
    }

    fun makeOverlay(source: Bitmap, maxLongEdge: Int = 1080): Bitmap {
        val longEdge = max(source.width, source.height)
        if (longEdge <= maxLongEdge) return source.copy(Bitmap.Config.ARGB_8888, false)
        val scale = maxLongEdge / longEdge.toFloat()
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    fun combine(first: Bitmap, second: Bitmap): Bitmap {
        val matchedSecond = matchTo(first, second)
        val width = first.width
        val height = first.height
        val firstRow = IntArray(width)
        val secondRow = IntArray(width)
        val outputRow = IntArray(width)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (y in 0 until height) {
            first.getPixels(firstRow, 0, width, 0, y, width, 1)
            matchedSecond.getPixels(secondRow, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                outputRow[x] = blendPixel(firstRow[x], secondRow[x])
            }
            output.setPixels(outputRow, 0, width, 0, y, width, 1)
        }
        if (matchedSecond !== second && !matchedSecond.isRecycled) matchedSecond.recycle()
        return output
    }

    private fun matchTo(reference: Bitmap, source: Bitmap): Bitmap {
        if (source.width == reference.width && source.height == reference.height) return source
        val crop = OutputGeometry.centerCrop(
            sourceWidth = source.width,
            sourceHeight = source.height,
            targetWidth = reference.width,
            targetHeight = reference.height,
        )
        val cropped = if (
            crop.left == 0 && crop.top == 0 &&
            crop.width == source.width && crop.height == source.height
        ) {
            source
        } else {
            Bitmap.createBitmap(source, crop.left, crop.top, crop.width, crop.height)
        }
        if (cropped.width == reference.width && cropped.height == reference.height) return cropped
        return Bitmap.createScaledBitmap(cropped, reference.width, reference.height, true).also {
            if (cropped !== source && !cropped.isRecycled) cropped.recycle()
        }
    }

    private fun blendPixel(first: Int, second: Int): Int {
        val r = blendChannel((first ushr 16) and 0xFF, (second ushr 16) and 0xFF)
        val g = blendChannel((first ushr 8) and 0xFF, (second ushr 8) and 0xFF)
        val b = blendChannel(first and 0xFF, second and 0xFF)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun blendChannel(first: Int, second: Int): Int {
        val firstLinear = HdrMath.srgbToLinear(first / 255f)
        val secondLinear = HdrMath.srgbToLinear(second / 255f)
        // Two half-exposures: preserve headroom and avoid a crude screen blend.
        val combined = (firstLinear + secondLinear) * 0.5f
        return (HdrMath.linearToSrgb(combined).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    }
}

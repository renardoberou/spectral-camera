package com.renardoberou.spectralcamera.core.doubleexposure

import android.graphics.Bitmap
import com.renardoberou.spectralcamera.core.hdr.HdrMath
import kotlin.math.roundToInt

/** Pure channel math kept JVM-testable. */
object DoubleExposureMath {
    /**
     * Both frames receive one stop of compensation, then combine like two light
     * exposures on one emulsion. The screen term retains both bright structures
     * without the hard clipping of a plain sum.
     */
    fun blendLinear(first: Float, second: Float): Float {
        val a = first.coerceIn(0f, 1f) * 0.5f
        val b = second.coerceIn(0f, 1f) * 0.5f
        return (1f - (1f - a) * (1f - b)).coerceIn(0f, 1f)
    }
}

/**
 * Creative double exposure happens before synthetic NIR and film rendering.
 * This mirrors exposing the same emulsion twice, instead of compositing two
 * already-developed Aerochrome/IR images afterward.
 */
object DoubleExposurePipeline {
    fun combine(first: Bitmap, second: Bitmap): Bitmap {
        val matchedSecond = matchSize(second, first.width, first.height)
        val output = Bitmap.createBitmap(first.width, first.height, Bitmap.Config.ARGB_8888)
        val firstRow = IntArray(first.width)
        val secondRow = IntArray(first.width)
        val outputRow = IntArray(first.width)

        for (y in 0 until first.height) {
            first.getPixels(firstRow, 0, first.width, 0, y, first.width, 1)
            matchedSecond.getPixels(secondRow, 0, first.width, 0, y, first.width, 1)
            for (x in 0 until first.width) {
                val a = firstRow[x]
                val b = secondRow[x]
                val ar = HdrMath.srgbToLinear(((a ushr 16) and 0xFF) / 255f)
                val ag = HdrMath.srgbToLinear(((a ushr 8) and 0xFF) / 255f)
                val ab = HdrMath.srgbToLinear((a and 0xFF) / 255f)
                val br = HdrMath.srgbToLinear(((b ushr 16) and 0xFF) / 255f)
                val bg = HdrMath.srgbToLinear(((b ushr 8) and 0xFF) / 255f)
                val bb = HdrMath.srgbToLinear((b and 0xFF) / 255f)

                val r = (HdrMath.linearToSrgb(DoubleExposureMath.blendLinear(ar, br)) * 255f + 0.5f)
                    .toInt().coerceIn(0, 255)
                val g = (HdrMath.linearToSrgb(DoubleExposureMath.blendLinear(ag, bg)) * 255f + 0.5f)
                    .toInt().coerceIn(0, 255)
                val blue = (HdrMath.linearToSrgb(DoubleExposureMath.blendLinear(ab, bb)) * 255f + 0.5f)
                    .toInt().coerceIn(0, 255)
                outputRow[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or blue
            }
            output.setPixels(outputRow, 0, first.width, 0, y, first.width, 1)
        }

        if (matchedSecond !== second && !matchedSecond.isRecycled) matchedSecond.recycle()
        return output
    }

    /** Center-crop and resize the second exposure without stretching it. */
    fun matchSize(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        if (source.width == targetWidth && source.height == targetHeight) return source
        val sourceAspect = source.width.toDouble() / source.height.toDouble()
        val targetAspect = targetWidth.toDouble() / targetHeight.toDouble()
        val crop = if (sourceAspect > targetAspect) {
            val width = (source.height * targetAspect).roundToInt().coerceIn(1, source.width)
            Bitmap.createBitmap(source, (source.width - width) / 2, 0, width, source.height)
        } else {
            val height = (source.width / targetAspect).roundToInt().coerceIn(1, source.height)
            Bitmap.createBitmap(source, 0, (source.height - height) / 2, source.width, height)
        }
        if (crop.width == targetWidth && crop.height == targetHeight) return crop
        return Bitmap.createScaledBitmap(crop, targetWidth, targetHeight, true).also {
            if (crop !== source && !crop.isRecycled) crop.recycle()
        }
    }
}

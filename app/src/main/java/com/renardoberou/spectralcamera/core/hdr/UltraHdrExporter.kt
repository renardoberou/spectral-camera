package com.renardoberou.spectralcamera.core.hdr

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Bitmap with an attached gain map plus the owned gain-map pixels. */
data class UltraHdrImage(
    val bitmap: Bitmap,
    val gainmapContents: Bitmap,
) {
    fun recycle() {
        if (!bitmap.isRecycled) bitmap.recycle()
        if (!gainmapContents.isRecycled) gainmapContents.recycle()
    }
}

/**
 * Rebuilds the gain map after the Aerochrome/IR transform.
 *
 * A pre-film gain map is not valid after false-colour or monochrome filtering.
 * This stage samples scene headroom from the HDR merge but gates it by the final
 * processed luminance, preventing intentionally dark EIR skies and IR water
 * from becoming luminous HDR patches.
 */
object UltraHdrExporter {
    fun attachIfSupported(processed: Bitmap, gainField: HdrGainField): UltraHdrImage? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return attachApi34(processed, gainField)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun attachApi34(processed: Bitmap, gainField: HdrGainField): UltraHdrImage? {
        // A gain field with the reciprocal/raw-sensor aspect can paint recovered
        // brightness onto unrelated parts of a portrait image. Save a correct
        // SDR JPEG instead of attaching any spatially inconsistent gain map.
        val processedAspect = processed.width.toFloat() / processed.height
        val fieldAspect = gainField.width.toFloat() / gainField.height
        val relativeAspectError = abs(fieldAspect / processedAspect - 1f)
        if (relativeAspectError > 0.08f) return null

        val longEdge = max(processed.width, processed.height)
        val scale = min(0.25f, 1024f / longEdge.toFloat())
            .coerceAtLeast(1f / longEdge.toFloat())
        val gainWidth = max(1, (processed.width * scale).roundToInt())
        val gainHeight = max(1, (processed.height * scale).roundToInt())
        val preview = Bitmap.createScaledBitmap(processed, gainWidth, gainHeight, true)
        val previewPixels = IntArray(gainWidth * gainHeight)
        preview.getPixels(previewPixels, 0, gainWidth, 0, 0, gainWidth, gainHeight)
        if (preview !== processed) preview.recycle()

        val gainPixels = IntArray(previewPixels.size)
        for (y in 0 until gainHeight) {
            val ny = (y + 0.5f) / gainHeight.toFloat()
            for (x in 0 until gainWidth) {
                val nx = (x + 0.5f) / gainWidth.toFloat()
                val pixel = previewPixels[y * gainWidth + x]
                val r = ((pixel ushr 16) and 0xFF) / 255f
                val g = ((pixel ushr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                val finalLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val visibilityGate = HdrMath.smoothstep(0.07f, 0.62f, finalLuma)
                val highlightGate = 0.35f + 0.65f * HdrMath.smoothstep(0.30f, 0.90f, finalLuma)
                val stops = gainField.sampleNormalized(nx, ny) * visibilityGate * highlightGate
                val encoded = ((stops / gainField.maxStops).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                gainPixels[y * gainWidth + x] =
                    (0xFF shl 24) or (encoded shl 16) or (encoded shl 8) or encoded
            }
        }

        val gainBitmap = Bitmap.createBitmap(gainPixels, gainWidth, gainHeight, Bitmap.Config.ARGB_8888)
        val base = processed.copy(Bitmap.Config.ARGB_8888, false) ?: run {
            gainBitmap.recycle()
            return null
        }
        val ratioMax = 2f.pow(gainField.maxStops)
        val gainmap = Gainmap(gainBitmap).apply {
            setRatioMin(1f, 1f, 1f)
            setRatioMax(ratioMax, ratioMax, ratioMax)
            setGamma(1f, 1f, 1f)
            setEpsilonSdr(1f / 1024f, 1f / 1024f, 1f / 1024f)
            setEpsilonHdr(1f / 1024f, 1f / 1024f, 1f / 1024f)
            setMinDisplayRatioForHdrTransition(1f)
            setDisplayRatioForFullHdr(min(ratioMax, 4f))
        }
        base.setGainmap(gainmap)
        return UltraHdrImage(base, gainBitmap)
    }
}

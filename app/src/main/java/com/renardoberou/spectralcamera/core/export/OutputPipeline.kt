package com.renardoberou.spectralcamera.core.export

import android.graphics.Bitmap
import com.renardoberou.spectralcamera.core.OutputMode
import kotlin.math.roundToInt

/** Integer image size used by the pure, JVM-testable output geometry. */
data class PixelSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
    }
}

/** Centered crop rectangle in source-pixel coordinates. */
data class CropRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    init {
        require(left >= 0 && top >= 0) { "Crop origin must be non-negative" }
        require(width > 0 && height > 0) { "Crop dimensions must be positive" }
    }
}

/**
 * Pure output geometry. Keeping this separate from Bitmap operations makes
 * mod-16 camera sizes such as 1920x1088 deterministic and unit-testable.
 */
object OutputGeometry {
    fun fullHdSize(sourceWidth: Int, sourceHeight: Int): PixelSize =
        if (sourceWidth >= sourceHeight) PixelSize(1920, 1080) else PixelSize(1080, 1920)

    fun centerCrop(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): CropRect {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(targetWidth > 0 && targetHeight > 0)

        val sourceAspect = sourceWidth.toDouble() / sourceHeight.toDouble()
        val targetAspect = targetWidth.toDouble() / targetHeight.toDouble()

        return if (sourceAspect > targetAspect) {
            val cropWidth = (sourceHeight * targetAspect)
                .roundToInt()
                .coerceIn(1, sourceWidth)
            CropRect(
                left = (sourceWidth - cropWidth) / 2,
                top = 0,
                width = cropWidth,
                height = sourceHeight,
            )
        } else {
            val cropHeight = (sourceWidth / targetAspect)
                .roundToInt()
                .coerceIn(1, sourceHeight)
            CropRect(
                left = 0,
                top = (sourceHeight - cropHeight) / 2,
                width = sourceWidth,
                height = cropHeight,
            )
        }
    }
}

/**
 * CPU-side preparation and finishing around the shared GPU film renderer.
 *
 * HQ_1080 preserves source detail through classification and film rendering,
 * then progressively downsamples the processed result. FAST_1080 downsamples
 * before the GPU render. Both produce an exact orientation-aware Full HD file.
 */
object OutputPipeline {
    fun prepareForRender(source: Bitmap, mode: OutputMode): Bitmap = when (mode) {
        OutputMode.FULL_RESOLUTION -> source
        OutputMode.HQ_1080 -> cropToFullHdAspect(source)
        OutputMode.FAST_1080 -> {
            val cropped = cropToFullHdAspect(source)
            val target = OutputGeometry.fullHdSize(cropped.width, cropped.height)
            if (cropped.width == target.width && cropped.height == target.height) {
                cropped
            } else {
                Bitmap.createScaledBitmap(cropped, target.width, target.height, true).also {
                    if (cropped !== source) cropped.recycle()
                }
            }
        }
    }

    fun finalizeExport(rendered: Bitmap, mode: OutputMode): Bitmap = when (mode) {
        OutputMode.FULL_RESOLUTION -> rendered
        OutputMode.HQ_1080 -> progressiveDownsampleToFullHd(rendered)
        OutputMode.FAST_1080 -> scaleToExactFullHd(rendered)
    }

    private fun cropToFullHdAspect(source: Bitmap): Bitmap {
        val target = OutputGeometry.fullHdSize(source.width, source.height)
        val crop = OutputGeometry.centerCrop(
            sourceWidth = source.width,
            sourceHeight = source.height,
            targetWidth = target.width,
            targetHeight = target.height,
        )
        if (crop.left == 0 && crop.top == 0 && crop.width == source.width && crop.height == source.height) {
            return source
        }
        return Bitmap.createBitmap(source, crop.left, crop.top, crop.width, crop.height)
    }

    private fun scaleToExactFullHd(source: Bitmap): Bitmap {
        val target = OutputGeometry.fullHdSize(source.width, source.height)
        if (source.width == target.width && source.height == target.height) return source
        return Bitmap.createScaledBitmap(source, target.width, target.height, true)
    }

    /**
     * Repeated two-to-one reductions preserve fine structure better than one
     * very large bilinear jump while remaining available on every supported API.
     * Only intermediate bitmaps created here are recycled; ownership of the
     * supplied render remains with the caller.
     */
    private fun progressiveDownsampleToFullHd(source: Bitmap): Bitmap {
        val target = OutputGeometry.fullHdSize(source.width, source.height)
        if (source.width == target.width && source.height == target.height) return source

        var current = source
        var ownsCurrent = false
        while (current.width / 2 >= target.width && current.height / 2 >= target.height) {
            val nextWidth = (current.width / 2).coerceAtLeast(target.width)
            val nextHeight = (current.height / 2).coerceAtLeast(target.height)
            val next = Bitmap.createScaledBitmap(current, nextWidth, nextHeight, true)
            if (ownsCurrent) current.recycle()
            current = next
            ownsCurrent = true
        }

        if (current.width == target.width && current.height == target.height) return current
        val finalBitmap = Bitmap.createScaledBitmap(current, target.width, target.height, true)
        if (ownsCurrent) current.recycle()
        return finalBitmap
    }
}

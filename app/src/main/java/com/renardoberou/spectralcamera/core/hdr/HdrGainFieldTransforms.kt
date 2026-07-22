package com.renardoberou.spectralcamera.core.hdr

import com.renardoberou.spectralcamera.core.OutputMode
import com.renardoberou.spectralcamera.core.export.OutputGeometry
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Geometry operations for the scene-headroom field used by Ultra HDR.
 *
 * RAW_SENSOR frames arrive in sensor orientation, while their developed bitmap
 * is rotated/mirrored and may then be center-cropped for HQ/Fast 1080. The gain
 * field must undergo the identical geometry; normalized sampling alone cannot
 * correct a 90-degree rotation or a 4:3-to-16:9 crop.
 */
fun HdrGainField.orientLikeBitmap(
    rotationDegrees: Int,
    mirrorHorizontal: Boolean,
): HdrGainField {
    val rotation = ((rotationDegrees % 360) + 360) % 360
    require(rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270) {
        "Gain-field rotation must be a right angle"
    }
    var current = when (rotation) {
        90 -> rotate90Clockwise()
        180 -> rotate180()
        270 -> rotate270Clockwise()
        else -> this
    }
    if (mirrorHorizontal) current = current.mirrorHorizontal()
    return current
}

/** Apply the same pre-render aspect crop used by [com.renardoberou.spectralcamera.core.export.OutputPipeline]. */
fun HdrGainField.prepareForOutput(
    sourceWidth: Int,
    sourceHeight: Int,
    mode: OutputMode,
): HdrGainField {
    require(sourceWidth > 0 && sourceHeight > 0)
    if (mode == OutputMode.FULL_RESOLUTION) return this

    val target = OutputGeometry.fullHdSize(sourceWidth, sourceHeight)
    val crop = OutputGeometry.centerCrop(
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        targetWidth = target.width,
        targetHeight = target.height,
    )
    if (
        crop.left == 0 && crop.top == 0 &&
        crop.width == sourceWidth && crop.height == sourceHeight
    ) {
        return this
    }

    val outputWidth = max(1, (width * crop.width.toFloat() / sourceWidth).roundToInt())
    val outputHeight = max(1, (height * crop.height.toFloat() / sourceHeight).roundToInt())
    val output = FloatArray(outputWidth * outputHeight)
    for (y in 0 until outputHeight) {
        val sourceY = (
            crop.top + (y + 0.5f) * crop.height / outputHeight.toFloat()
            ) / sourceHeight.toFloat()
        for (x in 0 until outputWidth) {
            val sourceX = (
                crop.left + (x + 0.5f) * crop.width / outputWidth.toFloat()
                ) / sourceWidth.toFloat()
            output[y * outputWidth + x] = sampleNormalized(sourceX, sourceY)
        }
    }
    return HdrGainField(outputWidth, outputHeight, output, maxStops)
}

private fun HdrGainField.rotate90Clockwise(): HdrGainField {
    val outputWidth = height
    val outputHeight = width
    val output = FloatArray(outputWidth * outputHeight)
    for (y in 0 until outputHeight) {
        for (x in 0 until outputWidth) {
            val sourceX = y
            val sourceY = height - 1 - x
            output[y * outputWidth + x] = stops[sourceY * width + sourceX]
        }
    }
    return HdrGainField(outputWidth, outputHeight, output, maxStops)
}

private fun HdrGainField.rotate180(): HdrGainField {
    val output = FloatArray(stops.size)
    for (y in 0 until height) {
        for (x in 0 until width) {
            output[y * width + x] = stops[(height - 1 - y) * width + (width - 1 - x)]
        }
    }
    return HdrGainField(width, height, output, maxStops)
}

private fun HdrGainField.rotate270Clockwise(): HdrGainField {
    val outputWidth = height
    val outputHeight = width
    val output = FloatArray(outputWidth * outputHeight)
    for (y in 0 until outputHeight) {
        for (x in 0 until outputWidth) {
            val sourceX = width - 1 - y
            val sourceY = x
            output[y * outputWidth + x] = stops[sourceY * width + sourceX]
        }
    }
    return HdrGainField(outputWidth, outputHeight, output, maxStops)
}

private fun HdrGainField.mirrorHorizontal(): HdrGainField {
    val output = FloatArray(stops.size)
    for (y in 0 until height) {
        for (x in 0 until width) {
            output[y * width + x] = stops[y * width + (width - 1 - x)]
        }
    }
    return HdrGainField(width, height, output, maxStops)
}

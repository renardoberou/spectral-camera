package com.renardoberou.spectralcamera.core.camera

import android.graphics.Bitmap
import java.io.File

data class CapturedExposure(
    val bitmap: Bitmap,
    val evOffset: Float,
)

data class RawSensorFrame(
    val width: Int,
    val height: Int,
    val pixels: ShortArray,
    val cropLeft: Int,
    val cropTop: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    val cfaArrangement: Int,
    val blackLevels: FloatArray,
    val whiteLevel: Float,
    val exposureTimeNs: Long,
    val sensitivityIso: Int,
    val whiteBalanceGains: FloatArray,
    val colorTransform: FloatArray,
    val rotationDegrees: Int,
    val timestampNs: Long,
    val evOffset: Float,
    val dngFile: File? = null,
) {
    init {
        require(width > 0 && height > 0)
        require(pixels.size == width * height)
        require(cropLeft >= 0 && cropTop >= 0)
        require(cropWidth > 0 && cropHeight > 0)
        require(cropLeft + cropWidth <= width && cropTop + cropHeight <= height)
        require(blackLevels.size == 4)
        require(whiteBalanceGains.size == 4)
        require(colorTransform.size == 9)
        require(whiteLevel > 0f)
        require(exposureTimeNs > 0L)
        require(sensitivityIso > 0)
    }

    fun codeAt(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 65535
}

data class CapturedFrame(
    val exposures: List<CapturedExposure> = emptyList(),
    val rawExposures: List<RawSensorFrame> = emptyList(),
    val referenceIndex: Int = 0,
    val rawSidecarFile: File? = null,
) {
    init {
        require(exposures.isNotEmpty() xor rawExposures.isNotEmpty())
        val count = if (exposures.isNotEmpty()) exposures.size else rawExposures.size
        require(referenceIndex in 0 until count)
    }

    val referenceBitmap: Bitmap? get() = exposures.getOrNull(referenceIndex)?.bitmap
    val isHdrBracket: Boolean get() = exposures.size > 1
    val isRawHdrBracket: Boolean get() = rawExposures.size > 1
    val rawFiles: List<File> get() = buildList {
        rawSidecarFile?.let(::add)
        rawExposures.mapNotNullTo(this) { it.dngFile }
    }
}

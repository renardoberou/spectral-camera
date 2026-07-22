package com.renardoberou.spectralcamera.core.camera

import android.graphics.Bitmap
import java.io.File

/** One decoded JPEG frame and its exposure offset relative to the reference. */
data class CapturedExposure(
    val bitmap: Bitmap,
    val evOffset: Float,
)

/**
 * One shutter action. Standard capture contains one exposure; computational HDR
 * contains an ordered bracket whose [referenceIndex] identifies the normal
 * exposure. RAW sidecar capture remains a single-exposure workflow in this
 * cycle, so [rawSidecarFile] and a multi-frame bracket are never both present.
 */
data class CapturedFrame(
    val exposures: List<CapturedExposure>,
    val referenceIndex: Int = 0,
    val rawSidecarFile: File? = null,
) {
    init {
        require(exposures.isNotEmpty()) { "A captured frame needs at least one exposure" }
        require(referenceIndex in exposures.indices) { "Reference index must address an exposure" }
    }

    val referenceBitmap: Bitmap get() = exposures[referenceIndex].bitmap
    val isHdrBracket: Boolean get() = exposures.size > 1
}

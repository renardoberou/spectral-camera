package com.renardoberou.spectralcamera.core.camera

import android.graphics.Bitmap
import java.io.File

/**
 * One camera shutter result.
 *
 * [bitmap] is the upright JPEG-derived source used by the existing GPU film
 * pipeline. [rawSidecarFile] is an untouched temporary DNG captured alongside
 * it when the active camera supports CameraX RAW+JPEG; the media layer copies
 * that file to durable storage and the caller then deletes the temporary file.
 */
data class CapturedFrame(
    val bitmap: Bitmap,
    val rawSidecarFile: File? = null,
)

package com.renardoberou.spectralcamera.core.camera

import android.hardware.camera2.CameraCharacteristics

/** Safe access used by capability gates before an active Camera2 lens is resolved. */
internal fun <T> CameraCharacteristics?.get(
    key: CameraCharacteristics.Key<T>,
): T? = this?.get(key)

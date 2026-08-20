package com.renardoberou.spectralcamera.core.gl

import android.util.Log
import com.renardoberou.spectralcamera.BuildConfig
import com.renardoberou.spectralcamera.core.CameraSettings
import com.renardoberou.spectralcamera.core.GrainPolicy

internal object SpectralGrainTrace {
    private const val TAG = "SpectralGrainTrace"

    fun uiSelection(policy: GrainPolicy) = log("ui selection grain=${policy.name} strength=${policy.strength}")

    fun viewModelOwned(sequence: Long, settings: CameraSettings) = log(
        "viewmodel owned seq=$sequence grain=${GrainPolicy.fromPersistedValue(settings.adjustments.grain).name} " +
            "strength=${settings.adjustments.grain}",
    )

    fun composeEmission(sequence: Long, settings: CameraSettings) = log(
        "compose emission seq=$sequence grain=${GrainPolicy.fromPersistedValue(settings.adjustments.grain).name} " +
            "strength=${settings.adjustments.grain}",
    )

    fun glPublish(sequence: Long, settings: CameraSettings) = log(
        "gl publish seq=$sequence grain=${GrainPolicy.fromPersistedValue(settings.adjustments.grain).name} " +
            "strength=${settings.adjustments.grain}",
    )

    fun glConsume(sequence: Long, settings: CameraSettings) = log(
        "gl consume seq=$sequence grain=${GrainPolicy.fromPersistedValue(settings.adjustments.grain).name} " +
            "strength=${settings.adjustments.grain}",
    )

    fun glDraw(sequence: Long, frame: Long, settings: CameraSettings, postLookLuma: Float? = null) = log(
        "gl draw seq=$sequence frame=$frame " +
            "grain=${GrainPolicy.fromPersistedValue(settings.adjustments.grain).name} " +
            "strength=${settings.adjustments.grain}" +
            (postLookLuma?.let { " postLookLuma=$it" } ?: ""),
    )

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}

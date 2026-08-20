package com.renardoberou.spectralcamera.core.gl

import android.util.Log
import com.renardoberou.spectralcamera.BuildConfig
import com.renardoberou.spectralcamera.core.CameraSettings
import com.renardoberou.spectralcamera.core.GrainPolicy

internal object SpectralGrainTrace {
    private const val TAG = "SpectralGrainTrace"
    private var lastDrawSequence = Long.MIN_VALUE

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

    fun glDraw(
        sequence: Long,
        frame: Long,
        settings: CameraSettings,
        viewportWidth: Int? = null,
        viewportHeight: Int? = null,
        grainSeed: Float? = null,
        postLookLuma: Float? = null,
    ) {
        if (sequence == lastDrawSequence && frame % 30L != 0L) return
        lastDrawSequence = sequence
        log(
            "gl draw seq=$sequence frame=$frame " +
                "grain=${GrainPolicy.fromPersistedValue(settings.adjustments.grain).name} " +
                "strength=${settings.adjustments.grain}" +
                (viewportWidth?.let { " viewport=${it}x${viewportHeight ?: 0}" } ?: "") +
                (grainSeed?.let {
                    val strength = GrainPolicy.renderStrength(settings)
                    // Proxy is the shader's mid-tone effective amplitude before
                    // the per-stock bias. It is deliberately labelled a proxy:
                    // the per-pixel gLuma/density is only available in GLSL.
                    " grainSeed=$it grainAmpProxy=${strength * 0.10f}"
                } ?: "") +
                (postLookLuma?.let { " postLookLuma=$it" } ?: ""),
        )
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}

package com.renardoberou.spectralcamera.core

/** Named photographic-grain policies backed by the existing persisted floats. */
enum class GrainPolicy(val strength: Float) {
    OFF(0f),
    FINE(0.25f),
    MEDIUM(0.5f),
    COARSE(0.85f),
    EXTREME(1.25f),
    ;

    companion object {
        fun captureStrength(settings: CameraSettings): Float =
            fromPersistedValue(settings.adjustments.grain).strength

        fun fromPersistedValue(value: Float): GrainPolicy =
            entries.minBy { kotlin.math.abs(it.strength - value) }
    }
}
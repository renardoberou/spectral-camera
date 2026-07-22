package com.renardoberou.spectralcamera.core.focus

import com.renardoberou.spectralcamera.core.FocusDistanceCalibration
import kotlin.math.pow

/** Pure focus-position math shared by Camera2 control and JVM tests. */
object FocusMath {
    /**
     * Camera2 expresses manual focus in diopters: 0 is infinity and the lens's
     * reported maximum is its nearest position. Squaring the normalized control
     * gives photographers more useful precision in the distant half of travel.
     */
    fun normalizedToDiopters(position: Float, maximumDiopters: Float): Float {
        val normalized = position.coerceIn(0f, 1f)
        return maximumDiopters.coerceAtLeast(0f) * normalized.pow(2)
    }

    fun approximateDistanceMeters(diopters: Float): Float? =
        diopters.takeIf { it > 0.0001f }?.let { 1f / it }

    fun positionLabel(
        position: Float,
        maximumDiopters: Float,
        calibration: FocusDistanceCalibration,
    ): String {
        val normalized = position.coerceIn(0f, 1f)
        if (normalized <= 0.005f) return "∞"
        val diopters = normalizedToDiopters(normalized, maximumDiopters)
        val meters = approximateDistanceMeters(diopters)
        return if (
            meters != null &&
            calibration != FocusDistanceCalibration.UNCALIBRATED
        ) {
            when {
                meters >= 10f -> "≈ ${meters.toInt()} m"
                meters >= 1f -> "≈ ${String.format("%.1f", meters)} m"
                else -> "≈ ${(meters * 100f).toInt()} cm"
            }
        } else {
            "${(normalized * 100f).toInt()}% toward near"
        }
    }
}

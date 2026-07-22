package com.renardoberou.spectralcamera.core.focus

import com.renardoberou.spectralcamera.core.FocusDistanceCalibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusMathTest {
    @Test
    fun infinityIsAlwaysZeroDiopters() {
        assertEquals(0f, FocusMath.normalizedToDiopters(0f, 10f), 0f)
    }

    @Test
    fun nearestPositionUsesReportedMaximum() {
        assertEquals(8f, FocusMath.normalizedToDiopters(1f, 8f), 0.0001f)
    }

    @Test
    fun squaredControlPreservesMoreFarDistancePrecision() {
        assertEquals(2.5f, FocusMath.normalizedToDiopters(0.5f, 10f), 0.0001f)
        assertTrue(FocusMath.normalizedToDiopters(0.25f, 10f) < 1f)
    }

    @Test
    fun calibratedPositionCanShowApproximateDistance() {
        val label = FocusMath.positionLabel(
            position = 0.5f,
            maximumDiopters = 4f,
            calibration = FocusDistanceCalibration.CALIBRATED,
        )
        assertTrue(label.contains("m") || label.contains("cm"))
    }

    @Test
    fun uncalibratedPositionDoesNotClaimPhysicalDistance() {
        val label = FocusMath.positionLabel(
            position = 0.5f,
            maximumDiopters = 4f,
            calibration = FocusDistanceCalibration.UNCALIBRATED,
        )
        assertEquals("50% toward near", label)
    }
}

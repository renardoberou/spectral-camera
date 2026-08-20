package com.renardoberou.spectralcamera.core.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkingSpaceMathTest {
    @Test
    fun blackMapsToFiniteBlack() {
        val black = WorkingSpaceMath.normalizeRgb(Float.NaN, Float.NEGATIVE_INFINITY, -1f)
        assertEquals(Rgb(0f, 0f, 0f), black)
        assertEquals(0f, WorkingSpaceMath.linearToLog(0f), 0f)
        assertEquals(0f, WorkingSpaceMath.logToLinear(0f), 0f)
    }

    @Test
    fun supportedLinearLogRoundTripIsBounded() {
        listOf(0f, 0.001f, 0.18f, 0.5f, 1f, 1.25f).forEach { value ->
            val roundTrip = WorkingSpaceMath.logToLinear(WorkingSpaceMath.linearToLog(value))
            assertEquals(value, roundTrip, 0.00001f)
        }
    }

    @Test
    fun normalizationKeepsPositiveHeadroomUntilFinalDisplayClamp() {
        val normalized = WorkingSpaceMath.normalizeRgb(1.5f, 0.5f, 0.25f)
        assertEquals(1.5f, normalized.r, 0f)
        assertTrue(normalized.r > 1f)
        assertEquals(1f, WorkingSpaceMath.finalDisplay(normalized.r), 0f)
    }

    @Test
    fun finalDisplayIsFiniteNonNegativeAndClamped() {
        listOf(Float.NaN, Float.NEGATIVE_INFINITY, -1f, 0f, 0.5f, 1f, 4f, Float.POSITIVE_INFINITY)
            .forEach { value ->
                val output = WorkingSpaceMath.finalDisplay(value)
                assertTrue("output=$output for input=$value", output.isFinite())
                assertTrue(output in 0f..1f)
            }
    }
}

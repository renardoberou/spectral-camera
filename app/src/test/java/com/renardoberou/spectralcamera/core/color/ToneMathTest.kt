package com.renardoberou.spectralcamera.core.color

import org.junit.Assert.assertTrue
import org.junit.Test

class ToneMathTest {
    @Test
    fun finiteMonotonicInputProducesFiniteMonotonicOutput() {
        val profile = ToneProfile()
        var previous = -1f
        for (step in 0..200) {
            val input = step / 20f
            val output = ToneMath.map(input, profile)
            assertTrue(output.isFinite())
            assertTrue("input=$input output=$output", output in 0f..1f)
            assertTrue("input=$input previous=$previous output=$output", output + 1e-6f >= previous)
            previous = output
        }
    }

    @Test
    fun shoulderHasLowerSlopeThanTheMidTones() {
        val profile = ToneProfile(shoulderStrength = 0.85f, shoulderLength = 0.35f)
        val midSlope = ToneMath.map(0.55f, profile) - ToneMath.map(0.45f, profile)
        val highlightSlope = ToneMath.map(3.0f, profile) - ToneMath.map(2.9f, profile)
        assertTrue("mid=$midSlope highlight=$highlightSlope", highlightSlope < midSlope)
    }

    @Test
    fun blackFloorAndHeadroomAreHandledWithoutNonFiniteOutput() {
        val profile = ToneProfile(blackFloor = 0.03f)
        assertTrue(ToneMath.map(Float.NaN, profile).isFinite())
        assertTrue(ToneMath.map(Float.POSITIVE_INFINITY, profile).isFinite())
        assertTrue(ToneMath.map(-4f, profile) >= 0f)
        assertTrue(ToneMath.map(1f, profile) < 1f)
        assertTrue(ToneMath.map(8f, profile) > ToneMath.map(1f, profile))
    }

    @Test
    fun finalDisplayToneClampsOnlyAtTheDisplayBoundary() {
        val profile = ToneProfile()
        assertTrue(ToneMath.map(1.5f, profile) <= 1f)
        assertTrue(ToneMath.finalDisplay(1.5f, profile) in 0f..1f)
    }
}

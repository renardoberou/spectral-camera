package com.renardoberou.spectralcamera.core.hdr

import com.renardoberou.spectralcamera.core.HdrToneMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class HdrMathTest {
    @Test
    fun autoBracketProducesMinusTwoZeroPlusTwoStops() {
        val plan = HdrBracketPlanner.planAuto(
            baseIndex = 0,
            supportedRange = -9..9,
            exposureStep = 1f / 3f,
        )
        assertEquals(listOf(-6, 0, 6), plan.map { it.compensationIndex })
        assertEquals(-2f, plan[0].evOffset, 0.001f)
        assertEquals(0f, plan[1].evOffset, 0.001f)
        assertEquals(2f, plan[2].evOffset, 0.001f)
    }

    @Test
    fun narrowAutoRangeFallsBackWithoutDuplicateFrames() {
        val plan = HdrBracketPlanner.planAuto(
            baseIndex = 0,
            supportedRange = -1..1,
            exposureStep = 0.5f,
        )
        assertEquals(listOf(-1, 0, 1), plan.map { it.compensationIndex })
        assertEquals(3, plan.map { it.compensationIndex }.distinct().size)
    }

    @Test
    fun manualBracketClampsToRealShutterRange() {
        val plan = HdrBracketPlanner.planManual(
            baseShutterNs = 8_000_000L,
            supportedRange = 2_000_000L..20_000_000L,
        )
        assertEquals(listOf(2_000_000L, 8_000_000L, 20_000_000L), plan.map { it.first })
        assertTrue(plan.first().second < 0f)
        assertEquals(0f, plan[1].second, 0.001f)
        assertTrue(plan.last().second > 1f)
    }

    @Test
    fun srgbLinearRoundTripIsStable() {
        listOf(0f, 0.01f, 0.18f, 0.5f, 0.9f, 1f).forEach { value ->
            val roundTrip = HdrMath.linearToSrgb(HdrMath.srgbToLinear(value))
            assertEquals(value, roundTrip, 0.0001f)
        }
    }

    @Test
    fun everyToneMapIsMonotonicAndBounded() {
        HdrToneMap.values().forEach { mode ->
            var previous = -1f
            for (step in 0..200) {
                val input = step / 20f
                val mapped = HdrMath.toneMapLuma(input, whitePoint = 6f, mode = mode)
                assertTrue("$mode at $input", mapped in 0f..1f)
                assertTrue("$mode must be monotonic", mapped + 1e-6f >= previous)
                previous = mapped
            }
        }
    }

    @Test
    fun deghostWeightProtectsReferenceOnLargeDisagreement() {
        val matching = HdrMath.deghostWeight(0.20f, 0.21f)
        val moving = HdrMath.deghostWeight(0.20f, 1.20f)
        assertTrue(matching > 0.95f)
        assertTrue(moving < 0.20f)
    }

    @Test
    fun translationEstimatorRecoversKnownShift() {
        val width = 48
        val height = 40
        val expected = PixelShift(dx = 3, dy = -2)
        val reference = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            (sin(x * 0.41) + sin(y * 0.57) + ((x * y) % 7) * 0.15).toFloat()
        }
        val candidate = FloatArray(width * height) { 6f }
        for (y in 4 until height - 4) {
            for (x in 4 until width - 4) {
                val cx = x + expected.dx
                val cy = y + expected.dy
                if (cx in 0 until width && cy in 0 until height) {
                    candidate[cy * width + cx] = reference[y * width + x]
                }
            }
        }

        val actual = HdrTranslationEstimator.estimate(
            reference = reference,
            candidate = candidate,
            width = width,
            height = height,
            maxShift = 6,
            sampleStep = 1,
        )
        assertTrue("expected=$expected actual=$actual", abs(actual.dx - expected.dx) <= 1)
        assertTrue("expected=$expected actual=$actual", abs(actual.dy - expected.dy) <= 1)
    }
}

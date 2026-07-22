package com.renardoberou.spectralcamera.core.film

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmResponseMathTest {
    @Test
    fun preFilmCurveAnchorsMiddleGrey() {
        assertEquals(0.5f, FilmResponseMath.preFilmLuma(0.5f, 0.20f), 0.0001f)
    }

    @Test
    fun preFilmCurveLiftsShadowsAndCompressesHighlights() {
        assertTrue(FilmResponseMath.preFilmLuma(0.10f, 0.20f) > 0.10f)
        assertTrue(FilmResponseMath.preFilmLuma(0.90f, 0.20f) < 0.90f)
    }

    @Test
    fun preFilmCurveIsMonotonicAndBounded() {
        var previous = 0f
        for (step in 0..100) {
            val output = FilmResponseMath.preFilmLuma(step / 100f, 0.24f)
            assertTrue(output in 0f..1f)
            assertTrue(output + 0.00001f >= previous)
            previous = output
        }
    }

    @Test
    fun protectedReversalCurveKeepsUsefulToeSeparation() {
        val low = FilmResponseMath.protectedReversalCurve(0.10f, 0.55f)
        val lower = FilmResponseMath.protectedReversalCurve(0.06f, 0.55f)
        assertTrue(low > lower)
        assertTrue(low > 0.06f)
    }

    @Test
    fun outputShoulderIsIdentityBelowKneeAndAsymptoticAboveWhite() {
        assertEquals(0.80f, FilmResponseMath.softShoulderPeak(0.80f), 0f)
        assertTrue(FilmResponseMath.softShoulderPeak(1.0f) in 0.96f..0.98f)
        assertTrue(FilmResponseMath.softShoulderPeak(2.0f) < 1.0f)
    }

    @Test
    fun redHighlightDesaturationIsSelective() {
        assertEquals(1f, FilmResponseMath.redHighlightSaturationScale(0.25f, 1f), 0.0001f)
        assertTrue(FilmResponseMath.redHighlightSaturationScale(0.90f, 1f) <= 0.71f)
        assertEquals(1f, FilmResponseMath.redHighlightSaturationScale(0.90f, 0f), 0.0001f)
    }
}

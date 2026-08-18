package com.renardoberou.spectralcamera.core.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HueSectorMathTest {
    @Test
    fun sectorWeight_isContinuousAcrossZeroDegrees() {
        val justBelow = HueSectorMath.sectorWeight(359f, 0f, 60f)
        val justAbove = HueSectorMath.sectorWeight(1f, 0f, 60f)
        assertEquals(justBelow, justAbove, 0.0001f)
        assertTrue(justBelow > 0.99f)
    }

    @Test
    fun sectorWeight_normalizesNegativeModuloForWrappedInputs() {
        assertEquals(
            HueSectorMath.sectorWeight(180f, 180f, 60f),
            HueSectorMath.sectorWeight(-181f, 180f, 60f),
            0.0001f,
        )
    }

    @Test
    fun sectorWeight_isZeroOutsideSectorAndOneAtCenter() {
        assertEquals(1f, HueSectorMath.sectorWeight(120f, 120f, 60f), 0.0001f)
        assertEquals(0f, HueSectorMath.sectorWeight(151f, 120f, 60f), 0.0001f)
    }

    @Test
    fun density_keepsNeutralPixelsStable() {
        assertEquals(
            0f,
            HueSectorMath.colorDensity(20f, 0f, 0.5f, 20f),
            0.0001f
        )
        assertEquals(
            0f,
            HueSectorMath.colorDensity(20f, -1f, 0.5f, 20f),
            0.0001f
        )
    }

    @Test
    fun density_weightsMidtonesMoreThanExtremes() {
        val shadow = HueSectorMath.colorDensity(20f, 1f, 0f, 20f)
        val midtone = HueSectorMath.colorDensity(20f, 1f, 0.5f, 20f)
        val highlight = HueSectorMath.colorDensity(20f, 1f, 1f, 20f)
        assertTrue(midtone > shadow)
        assertTrue(midtone > highlight)
    }

    @Test
    fun density_isFiniteAndBoundedWithExtremeInputs() {
        val value = HueSectorMath.colorDensity(Float.NaN, 100f, -20f, Float.POSITIVE_INFINITY)
        assertTrue(value.isFinite())
        assertTrue(value in 0f..1f)
    }

    @Test
    fun compression_isMonotonicAndBounded() {
        val low = HueSectorMath.compressDensity(0.25f, 2f)
        val high = HueSectorMath.compressDensity(1f, 2f)
        assertTrue(low in 0f..1f)
        assertTrue(high in 0f..1f)
        assertTrue(high > low)
    }
}

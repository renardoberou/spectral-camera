package com.renardoberou.spectralcamera.core.doubleexposure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleExposureMathTest {
    @Test
    fun twoBlackFramesRemainBlack() {
        assertEquals(0f, DoubleExposureMath.blendLinear(0f, 0f), 0.0001f)
    }

    @Test
    fun eitherBrightFrameRemainsVisibleAtCompensatedLevel() {
        assertEquals(0.5f, DoubleExposureMath.blendLinear(1f, 0f), 0.0001f)
        assertEquals(0.5f, DoubleExposureMath.blendLinear(0f, 1f), 0.0001f)
    }

    @Test
    fun twoBrightFramesDoNotHardClip() {
        val combined = DoubleExposureMath.blendLinear(1f, 1f)
        assertEquals(0.75f, combined, 0.0001f)
        assertTrue(combined < 1f)
    }

    @Test
    fun blendIsSymmetric() {
        val first = DoubleExposureMath.blendLinear(0.2f, 0.8f)
        val second = DoubleExposureMath.blendLinear(0.8f, 0.2f)
        assertEquals(first, second, 0.0001f)
    }
}

package com.renardoberou.spectralcamera.core.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class DoubleExposureMathTest {
    @Test
    fun twoBlackFramesRemainBlack() {
        assertEquals(0f, DoubleExposureMath.blendLinear(0f, 0f), 0.0001f)
    }

    @Test
    fun oneBrightFrameReceivesOneStopCompensation() {
        assertEquals(0.5f, DoubleExposureMath.blendLinear(1f, 0f), 0.0001f)
        assertEquals(0.5f, DoubleExposureMath.blendLinear(0f, 1f), 0.0001f)
    }

    @Test
    fun twoBrightFramesReachOneCombinedExposure() {
        assertEquals(1f, DoubleExposureMath.blendLinear(1f, 1f), 0.0001f)
    }

    @Test
    fun blendIsSymmetric() {
        assertEquals(
            DoubleExposureMath.blendLinear(0.2f, 0.8f),
            DoubleExposureMath.blendLinear(0.8f, 0.2f),
            0.0001f,
        )
    }
}

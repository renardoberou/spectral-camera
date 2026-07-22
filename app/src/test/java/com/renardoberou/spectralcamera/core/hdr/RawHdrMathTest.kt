package com.renardoberou.spectralcamera.core.hdr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawHdrMathTest {
    @Test
    fun everyBayerPatternMapsACompleteTwoByTwoCell() {
        BayerArrangement.values().forEach { arrangement ->
            val channels = listOf(
                RawHdrMath.channelAt(arrangement, 0, 0),
                RawHdrMath.channelAt(arrangement, 1, 0),
                RawHdrMath.channelAt(arrangement, 0, 1),
                RawHdrMath.channelAt(arrangement, 1, 1),
            )
            assertTrue(channels.contains(BayerChannel.RED))
            assertTrue(channels.contains(BayerChannel.BLUE))
            assertEquals(2, channels.count {
                it == BayerChannel.GREEN_EVEN || it == BayerChannel.GREEN_ODD
            })
        }
    }

    @Test
    fun rggbMatchesCamera2Definition() {
        assertEquals(BayerChannel.RED, RawHdrMath.channelAt(BayerArrangement.RGGB, 0, 0))
        assertEquals(BayerChannel.GREEN_EVEN, RawHdrMath.channelAt(BayerArrangement.RGGB, 1, 0))
        assertEquals(BayerChannel.GREEN_ODD, RawHdrMath.channelAt(BayerArrangement.RGGB, 0, 1))
        assertEquals(BayerChannel.BLUE, RawHdrMath.channelAt(BayerArrangement.RGGB, 1, 1))
    }

    @Test
    fun rawNormalizationSubtractsBlackAndUsesWhiteRange() {
        assertEquals(0f, RawHdrMath.normalizeCode(64, black = 64f, white = 1023f), 0.0001f)
        assertEquals(1f, RawHdrMath.normalizeCode(1023, black = 64f, white = 1023f), 0.0001f)
        assertEquals(0.5f, RawHdrMath.normalizeCode(543, black = 64f, white = 1022f), 0.002f)
    }

    @Test
    fun clippedRawSamplesReceiveAlmostNoWeight() {
        assertTrue(RawHdrMath.rawWellExposedWeight(0.42f) > 0.95f)
        assertTrue(RawHdrMath.rawWellExposedWeight(0f) < 0.01f)
        assertTrue(RawHdrMath.rawWellExposedWeight(1f) < 0.01f)
    }

    @Test
    fun exposureProductTracksTimeAndIso() {
        val reference = RawHdrMath.exposureProduct(10_000_000L, 100)
        val plusOneStopTime = RawHdrMath.exposureProduct(20_000_000L, 100)
        val plusOneStopIso = RawHdrMath.exposureProduct(10_000_000L, 200)
        assertEquals(2.0, plusOneStopTime / reference, 0.0001)
        assertEquals(2.0, plusOneStopIso / reference, 0.0001)
    }

    @Test
    fun identityColorTransformLeavesSensorRgbUnchanged() {
        val identity = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
        )
        assertArrayEquals(
            floatArrayOf(0.2f, 0.4f, 0.8f),
            RawHdrMath.transformLinearRgb(0.2f, 0.4f, 0.8f, identity),
            0.0001f,
        )
    }
}

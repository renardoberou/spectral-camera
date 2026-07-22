package com.renardoberou.spectralcamera.core.hdr

import com.renardoberou.spectralcamera.core.OutputMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrGainFieldTransformsTest {
    @Test
    fun rotate90MatchesClockwiseBitmapRotation() {
        val source = HdrGainField(
            width = 2,
            height = 3,
            stops = floatArrayOf(
                1f, 2f,
                3f, 4f,
                5f, 6f,
            ),
            maxStops = 5f,
        )

        val rotated = source.orientLikeBitmap(rotationDegrees = 90, mirrorHorizontal = false)

        assertEquals(3, rotated.width)
        assertEquals(2, rotated.height)
        assertArrayEquals(
            floatArrayOf(
                5f, 3f, 1f,
                6f, 4f, 2f,
            ),
            rotated.stops,
            0f,
        )
    }

    @Test
    fun frontCameraMirrorHappensAfterRotation() {
        val source = HdrGainField(
            width = 2,
            height = 3,
            stops = floatArrayOf(
                1f, 2f,
                3f, 4f,
                5f, 6f,
            ),
            maxStops = 5f,
        )

        val transformed = source.orientLikeBitmap(rotationDegrees = 90, mirrorHorizontal = true)

        assertArrayEquals(
            floatArrayOf(
                1f, 3f, 5f,
                2f, 4f, 6f,
            ),
            transformed.stops,
            0f,
        )
    }

    @Test
    fun hq1080CropsGainFieldToSameLandscapeAspect() {
        val source = HdrGainField(
            width = 8,
            height = 6,
            stops = FloatArray(48) { index -> (index / 8).toFloat() },
            maxStops = 5f,
        )

        val cropped = source.prepareForOutput(
            sourceWidth = 4000,
            sourceHeight = 3000,
            mode = OutputMode.HQ_1080,
        )

        // A tiny 8x6 field rounds the ideal 16:9 crop to 8x5 (1.6:1).
        assertTrue(cropped.width.toFloat() / cropped.height >= 1.55f)
        assertTrue(cropped.width.toFloat() / cropped.height < 1.9f)
        // The 16:9 crop removes the extreme top and bottom of the 4:3 field.
        assertTrue(cropped.stops.first() > 0f)
        assertTrue(cropped.stops.last() < 5f)
    }

    @Test
    fun fullResolutionLeavesGeometryUntouched() {
        val source = HdrGainField(2, 2, floatArrayOf(1f, 2f, 3f, 4f), 5f)
        val output = source.prepareForOutput(4000, 3000, OutputMode.FULL_RESOLUTION)
        assertTrue(output === source)
    }
}

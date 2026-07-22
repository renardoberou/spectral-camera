package com.renardoberou.spectralcamera.core.export

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputGeometryTest {
    @Test
    fun landscapeFourByThreeCropsToSixteenByNine() {
        assertEquals(
            CropRect(left = 0, top = 375, width = 4000, height = 2250),
            OutputGeometry.centerCrop(4000, 3000, 1920, 1080),
        )
    }

    @Test
    fun mod16FullHdSourceLosesOnlyTheAlignmentRows() {
        assertEquals(
            CropRect(left = 0, top = 4, width = 1920, height = 1080),
            OutputGeometry.centerCrop(1920, 1088, 1920, 1080),
        )
    }

    @Test
    fun portraitSourceUsesPortraitFullHdAndCenteredCrop() {
        assertEquals(PixelSize(1080, 1920), OutputGeometry.fullHdSize(3000, 4000))
        assertEquals(
            CropRect(left = 375, top = 0, width = 2250, height = 4000),
            OutputGeometry.centerCrop(3000, 4000, 1080, 1920),
        )
    }

    @Test
    fun landscapeSourceUsesLandscapeFullHd() {
        assertEquals(PixelSize(1920, 1080), OutputGeometry.fullHdSize(8160, 6144))
    }
}

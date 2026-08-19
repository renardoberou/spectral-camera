package com.renardoberou.spectralcamera.ui.screens

import com.renardoberou.spectralcamera.core.LookFamily
import com.renardoberou.spectralcamera.core.SpectralPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactCameraContractsTest {
    @Test
    fun compactTopCameraControlsContainOnlyEssentialAdjustments() {
        assertEquals(
            listOf("Exposure", "Focus", "WB"),
            compactCameraActionInventory,
        )
    }

    @Test
    fun presetFamiliesFollowApprovedCatalogOrder() {
        assertEquals(
            listOf(LookFamily.STANDARD_FILM, LookFamily.AEROCHROME, LookFamily.MONOCHROME_IR),
            compactPresetFamilyOrder,
        )
    }

    @Test
    fun presetGridUsesTwoColumnsOnPortraitAndAtLeastThreeWhenWide() {
        assertEquals(2, presetGridColumnCount(360))
        assertEquals(3, presetGridColumnCount(600))
        assertEquals(4, presetGridColumnCount(840))
    }

    @Test
    fun activePresetIsRevealedWithoutChangingCatalogOrder() {
        val current = SpectralPreset.AEROCHROME_DENSE
        val ordered = presetsForLazyGrid(current)

        assertEquals(
            compactPresetFamilyOrder,
            ordered.map { it.family }.distinct(),
        )
        assertEquals(SpectralPreset.AEROCHROME_DENSE, ordered.first { it == current })
        assertEquals(SpectralPreset.values().toList().toSet(), ordered.toSet())
        assertEquals(ordered.size, ordered.distinct().size)
    }

    @Test
    fun grainOptionsIncludeExtremeInApprovedOrder() {
        assertEquals(listOf("Off", "Fine", "Medium", "Coarse", "Extreme"), grainOptionLabels)
        assertTrue(grainOptionValues.contains(1.25f))
    }
}

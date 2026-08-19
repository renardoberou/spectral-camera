package com.renardoberou.spectralcamera.ui.screens

import com.renardoberou.spectralcamera.core.LookFamily
import com.renardoberou.spectralcamera.core.SpectralPreset
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactCameraContractsTest {
    private val liveCameraSource = File(
        "src/main/java/com/renardoberou/spectralcamera/ui/screens/LiveCameraScreen.kt",
    ).readText()

    @Test
    fun compactTopCameraControlsContainEssentialAdjustmentsAndMore() {
        assertEquals(
            listOf("Exposure", "Focus", "WB", "More"),
            compactCameraActionInventory,
        )
    }

    @Test
    fun moreIsAdjacentToWhiteBalanceWithNoLowerDuplicateRow() {
        val compactControls = liveCameraSource
            .substringAfter("Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {")
            .substringBefore("                if (capabilities?.exposureSupported == true && showExposure)")

        assertTrue(compactControls.indexOf("Text(\"WB ") < compactControls.indexOf("Text(\"More\")"))
        assertEquals(1, liveCameraSource.windowed("Text(\"More\")".length).count { it == "Text(\"More\")" })
    }

    @Test
    fun cameraActionPanelHasNoDuplicateGalleryShortcut() {
        assertTrue(!liveCameraSource.contains("PhotoLibrary"))
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

    @Test
    fun moreOneShotActionsDismissWhileSheetActionsTransitionToDedicatedSheets() {
        assertEquals(MoreActionTransition.DISMISS, moreActionTransition(MoreAction.SAVE_ORIGINAL))
        assertEquals(MoreActionTransition.DISMISS, moreActionTransition(MoreAction.IMPORT))
        assertEquals(MoreActionTransition.DISMISS, moreActionTransition(MoreAction.DOUBLE_EXPOSURE))
        assertEquals(MoreActionTransition.DISMISS, moreActionTransition(MoreAction.ZEBRA))
        assertEquals(MoreActionTransition.PRESETS, moreActionTransition(MoreAction.PRESETS))
        assertEquals(MoreActionTransition.ADJUSTMENTS, moreActionTransition(MoreAction.ADJUSTMENTS))
    }
}

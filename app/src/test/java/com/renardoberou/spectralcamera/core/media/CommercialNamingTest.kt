package com.renardoberou.spectralcamera.core.media

import com.renardoberou.spectralcamera.core.SpectralPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CommercialNamingTest {
    @Test
    fun everyPresetHasACommercialDisplayNameWithoutResearchStockTerms() {
        val prohibited = Regex("(?i)\\b(?:kodak|ilford|rollei|konica|aerochrome|ektar|cinestill|tri[- ]?x|portra|vision3|eir)\\b")

        assertFalse(prohibited.containsMatchIn("Natural Portrait"))

        SpectralPreset.entries.forEach { preset ->
            val label = CommercialNaming.presetLabel(preset)
            assertFalse("$preset leaked a research term in '$label'", prohibited.containsMatchIn(label))
        }
    }

    @Test
    fun researchPresetIdsMapToStableCommercialLabels() {
        assertEquals("Deep Infrared", CommercialNaming.presetLabel(SpectralPreset.HIGH_CONTRAST_IR))
        assertEquals("Vivid Colour", CommercialNaming.presetLabel(SpectralPreset.EKTAR_100))
        assertEquals("Natural Portrait", CommercialNaming.presetLabel(SpectralPreset.PORTRA_400))
    }

    @Test
    fun metadataDescriptionUsesCommercialPresetLabelAndKeepsStableProfileId() {
        val description = CommercialNaming.metadataProfile(
            preset = SpectralPreset.CINESTILL_800T,
            sensorLabel = "Simulated IR",
            outputLabel = "Full Resolution",
        )

        assertEquals(
            "profile_id=tungsten_halation • Simulated IR • Tungsten Halation • Full Resolution",
            description,
        )
    }
}

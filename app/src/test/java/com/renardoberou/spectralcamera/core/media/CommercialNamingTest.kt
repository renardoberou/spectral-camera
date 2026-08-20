package com.renardoberou.spectralcamera.core.media

import com.renardoberou.spectralcamera.core.SpectralPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CommercialNamingTest {
    @Test
    fun presetAndExportLabelsUseTheCommercialSpectralPresetLabels() {
        val oldDisplayNames = setOf(
            "Infrared Mono",
            "Deep Infrared",
            "Soft Infrared",
            "Balanced Infrared",
            "Fine Infrared",
            "Vintage Infrared",
            "False Colour Classic",
            "False Colour Soft",
            "False Colour Dense",
            "False Colour Gold",
            "False Colour Faded",
            "False Colour Vivid",
            "Vivid Colour",
            "Tungsten Halation",
            "Documentary Mono",
            "Natural Portrait",
            "Archive Colour",
            "Cinematic Neutral",
            "Warm Negative",
        )

        SpectralPreset.entries.forEach { preset ->
            assertEquals(preset.label, CommercialNaming.presetLabel(preset))
            assertEquals(preset.label, CommercialNaming.exportLabel(preset))
            assertFalse("old display name leaked for $preset", CommercialNaming.presetLabel(preset) in oldDisplayNames)
        }
    }

    @Test
    fun metadataDescriptionUsesCommercialLabelAndKeepsStableProfileId() {
        val description = CommercialNaming.metadataProfile(
            preset = SpectralPreset.CINESTILL_800T,
            sensorLabel = "Simulated IR",
            outputLabel = "Full Resolution",
        )

        assertEquals(
            "profile_id=tungsten_halation • Simulated IR • Street Chrome 800 • Full Resolution",
            description,
        )
    }
}

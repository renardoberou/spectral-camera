package com.renardoberou.spectralcamera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetCatalogContractTest {
    @Test
    fun `catalog exposes families in approved order and every preset exactly once`() {
        assertEquals(
            listOf(LookFamily.STANDARD_FILM, LookFamily.AEROCHROME, LookFamily.MONOCHROME_IR),
            PresetCatalog.families,
        )

        val catalogPresets = PresetCatalog.families.flatMap(PresetCatalog::presetsFor)
        assertEquals(SpectralPreset.entries.toSet(), catalogPresets.toSet())
        assertEquals(SpectralPreset.entries.size, catalogPresets.size)
        assertEquals(catalogPresets.size, catalogPresets.distinct().size)
    }

    @Test
    fun `catalog preserves stable preset ordering within each family`() {
        assertEquals(
            listOf(
                SpectralPreset.EKTAR_100,
                SpectralPreset.CINESTILL_800T,
                SpectralPreset.TRI_X_400,
                SpectralPreset.PORTRA_400,
                SpectralPreset.ARCHIVE_CHROME,
                SpectralPreset.CINEMATIC_NEUTRAL,
                SpectralPreset.WARM_NEGATIVE,
            ),
            PresetCatalog.presetsFor(LookFamily.STANDARD_FILM),
        )
        assertEquals(
            listOf(
                SpectralPreset.AEROCHROME_FALSE_COLOR,
                SpectralPreset.AEROCHROME_SOFT,
                SpectralPreset.AEROCHROME_DENSE,
                SpectralPreset.AEROCHROME_GOLD,
                SpectralPreset.AEROCHROME_FADED,
                SpectralPreset.AEROCHROME_VIVID,
            ),
            PresetCatalog.presetsFor(LookFamily.AEROCHROME),
        )
        assertEquals(
            listOf(
                SpectralPreset.B_W_INFRARED,
                SpectralPreset.HIGH_CONTRAST_IR,
                SpectralPreset.WHITE_FOLIAGE_DARK_SKY,
                SpectralPreset.MONO_IR_MODERATE,
                SpectralPreset.MONO_IR_FINE_GRAIN,
                SpectralPreset.MONO_IR_SOFT_VINTAGE,
            ),
            PresetCatalog.presetsFor(LookFamily.MONOCHROME_IR),
        )
    }

    @Test
    fun `metadata uses approved commercial names and behavior descriptions`() {
        val expectedLabels = mapOf(
            SpectralPreset.B_W_INFRARED to "Silver IR 400",
            SpectralPreset.HIGH_CONTRAST_IR to "Deep IR 400",
            SpectralPreset.WHITE_FOLIAGE_DARK_SKY to "Extended Red 200",
            SpectralPreset.MONO_IR_MODERATE to "Balanced IR 32",
            SpectralPreset.MONO_IR_FINE_GRAIN to "Fine IR 400",
            SpectralPreset.MONO_IR_SOFT_VINTAGE to "Soft IR 400",
            SpectralPreset.AEROCHROME_FALSE_COLOR to "AeroIR Classic 400",
            SpectralPreset.AEROCHROME_SOFT to "AeroIR Soft 400",
            SpectralPreset.AEROCHROME_DENSE to "AeroIR Dense 400",
            SpectralPreset.AEROCHROME_GOLD to "AeroIR Amber 400",
            SpectralPreset.AEROCHROME_FADED to "AeroIR Faded 400",
            SpectralPreset.AEROCHROME_VIVID to "AeroIR Vivid 400",
            SpectralPreset.EKTAR_100 to "Vivid Negative 100",
            SpectralPreset.CINESTILL_800T to "Street Chrome 800",
            SpectralPreset.TRI_X_400 to "Grit Monochrome 400",
            SpectralPreset.PORTRA_400 to "Portrait Negative 400",
            SpectralPreset.ARCHIVE_CHROME to "Archive Chrome —",
            SpectralPreset.CINEMATIC_NEUTRAL to "Cinematic Neutral —",
            SpectralPreset.WARM_NEGATIVE to "Warm Negative 400",
        )

        assertEquals(expectedLabels, SpectralPreset.entries.associateWith { PresetCatalog.metadataFor(it).label })
        assertTrue(SpectralPreset.entries.all { PresetCatalog.metadataFor(it).description.isNotBlank() })
        assertTrue(SpectralPreset.entries.all { PresetCatalog.metadataFor(it).description.contains("simulation", ignoreCase = true) })
        val prohibitedNames = listOf("Kodak", "Ilford", "CineStill", "Rollei")
        assertFalse(SpectralPreset.entries.any { preset ->
            prohibitedNames.any { name -> PresetCatalog.metadataFor(preset).label.contains(name, ignoreCase = true) }
        })
    }

    @Test
    fun `camera settings default to Warm Negative`() {
        assertEquals(SpectralPreset.WARM_NEGATIVE, CameraSettings().preset)
    }
}

package com.renardoberou.spectralcamera.core.color

import com.renardoberou.spectralcamera.core.FilmLookLibrary
import com.renardoberou.spectralcamera.core.SharedFilmProfile
import com.renardoberou.spectralcamera.core.SpectralPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FujiProfileContractTest {
    @Test
    fun newVisiblePresetsBelongToStandardFilmAndHaveOriginalNames() {
        val presets = listOf(
            SpectralPreset.ARCHIVE_CHROME,
            SpectralPreset.CINEMATIC_NEUTRAL,
            SpectralPreset.WARM_NEGATIVE,
        )

        assertTrue(presets.all { it.family.name == "STANDARD_FILM" })
        assertEquals(listOf("Archive Chrome", "Cinematic Neutral", "Warm Negative"), presets.map { it.label })
        assertEquals(3, presets.map { it.label }.toSet().size)
    }

    @Test
    fun existingStandardLooksKeepIdentitySharedProfileDefaults() {
        val existing = listOf(
            SpectralPreset.EKTAR_100,
            SpectralPreset.CINESTILL_800T,
            SpectralPreset.TRI_X_400,
            SpectralPreset.PORTRA_400,
        )

        existing.forEach { preset ->
            assertEquals(SharedFilmProfile.IDENTITY, FilmLookLibrary.standardLookFor(preset).sharedProfile)
        }
    }

    @Test
    fun newProfilesAreDataDrivenAndDistinct() {
        val profiles = listOf(
            FilmLookLibrary.standardLookFor(SpectralPreset.ARCHIVE_CHROME).sharedProfile,
            FilmLookLibrary.standardLookFor(SpectralPreset.CINEMATIC_NEUTRAL).sharedProfile,
            FilmLookLibrary.standardLookFor(SpectralPreset.WARM_NEGATIVE).sharedProfile,
        )

        assertTrue(profiles.all { it != SharedFilmProfile.IDENTITY })
        assertNotEquals(profiles[0], profiles[1])
        assertNotEquals(profiles[1], profiles[2])
        assertNotEquals(profiles[0], profiles[2])
    }

    @Test
    fun spectralFamiliesReceiveTheSharedPostTransformRefinement() {
        assertNotEquals(
            SharedFilmProfile.IDENTITY,
            FilmLookLibrary.aeroLookFor(SpectralPreset.AEROCHROME_FALSE_COLOR).sharedProfile,
        )
        assertNotEquals(
            SharedFilmProfile.IDENTITY,
            FilmLookLibrary.monoLookFor(SpectralPreset.B_W_INFRARED).sharedProfile,
        )
    }
}

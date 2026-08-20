package com.renardoberou.spectralcamera.ui.screens

import com.renardoberou.spectralcamera.core.SpectralPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetThumbnailCatalogTest {
    @Test
    fun everyPresetHasDistinctNonFallbackThumbnail() {
        val entries = SpectralPreset.entries.map(PresetThumbnailCatalog::thumbnailFor)

        assertEquals(SpectralPreset.entries.size, entries.size)
        assertTrue(entries.all { it.resourceId != PresetThumbnailCatalog.fallbackResourceId })
        assertEquals(entries.size, entries.map { it.resourceId }.distinct().size)
    }

    @Test
    fun mappingIsStableByPresetEnumId() {
        val first = SpectralPreset.entries.associate { it.name to PresetThumbnailCatalog.thumbnailFor(it) }
        val second = SpectralPreset.entries.associate { it.name to PresetThumbnailCatalog.thumbnailFor(it) }

        assertEquals(first, second)
        assertTrue(first.keys.all { it.isNotBlank() })
    }

    @Test
    fun thumbnailResourcesAreBounded256pxAssetsAndFallbackExists() {
        assertNotEquals(0, PresetThumbnailCatalog.fallbackResourceId)
        assertTrue(PresetThumbnailCatalog.fallbackResourceName.contains("fallback"))
        assertFalse(PresetThumbnailCatalog.allResourceNames.any { it.contains("full_resolution") })
        assertTrue(PresetThumbnailCatalog.allResourceNames.all { it.endsWith("_thumbnail") })
        assertTrue(PresetThumbnailCatalog.thumbnailPixelSize == 256)
    }
}
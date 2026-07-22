package com.renardoberou.spectralcamera.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLensCatalogTest {
    private val candidates = listOf(
        CameraLensCandidate("auto", false, "0", null, true, 26f),
        CameraLensCandidate("ultra", false, "0", "2", false, 14f),
        CameraLensCandidate("main", false, "0", "3", false, 26f),
        CameraLensCandidate("tele", false, "0", "4", false, 52f),
        CameraLensCandidate("front", true, "1", null, false, 24f),
    )

    @Test
    fun labelsPhysicalLensesRelativeToMain() {
        val options = CameraLensCatalog.buildOptions(candidates)
        assertEquals("Auto rear", options.first().label)
        assertTrue(options.any { it.label.startsWith("0.5×") && it.label.contains("Ultra-wide") })
        assertTrue(options.any { it.label.startsWith("1×") && it.label.contains("Main") })
        assertTrue(options.any { it.label.startsWith("2×") && it.label.contains("Tele") })
        assertEquals("Selfie", options.last().label)
    }

    @Test
    fun requestedPhysicalLensWins() {
        val options = CameraLensCatalog.buildOptions(candidates)
        assertEquals("tele", CameraLensCatalog.choose(options, "tele", preferFront = false)?.id)
    }

    @Test
    fun emptyRearSelectionPreservesLogicalAutoBehavior() {
        val options = CameraLensCatalog.buildOptions(candidates)
        assertEquals("auto", CameraLensCatalog.choose(options, "", preferFront = false)?.id)
    }

    @Test
    fun frontMigrationSelectsSelfie() {
        val options = CameraLensCatalog.buildOptions(candidates)
        assertEquals("front", CameraLensCatalog.choose(options, "", preferFront = true)?.id)
    }

    @Test
    fun computesPlausibleEquivalentFocalLength() {
        val equivalent = CameraLensCatalog.equivalentFocalLengthMm(4.5f, 6.4f, 4.8f)
        assertNotNull(equivalent)
        assertTrue(requireNotNull(equivalent) in 23f..26f)
    }
}

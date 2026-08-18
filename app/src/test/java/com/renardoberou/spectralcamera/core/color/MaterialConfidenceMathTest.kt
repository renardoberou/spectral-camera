package com.renardoberou.spectralcamera.core.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialConfidenceMathTest {
    private val samples = listOf(
        Rgb(0.72f, 0.42f, 0.30f),
        Rgb(0.12f, 0.42f, 0.10f),
        Rgb(0.22f, 0.38f, 0.72f),
        Rgb(0.10f, 0.45f, 0.62f),
        Rgb(0.35f, 0.35f, 0.35f),
        Rgb(0.02f, 0.02f, 0.02f),
        Rgb(1.5f, 1.4f, 1.3f),
    )

    @Test
    fun everyConfidenceIsFiniteAndBounded() {
        samples.forEach { rgb ->
            val values = listOf(
                MaterialConfidenceMath.skinConfidence(rgb),
                MaterialConfidenceMath.foliageConfidence(rgb),
                MaterialConfidenceMath.skyConfidence(rgb),
                MaterialConfidenceMath.blueCyanConfidence(rgb),
                MaterialConfidenceMath.neutralConfidence(rgb),
                MaterialConfidenceMath.highlightConfidence(rgb),
                MaterialConfidenceMath.shadowConfidence(rgb),
                MaterialConfidenceMath.reliability(rgb),
            )
            values.forEach { value ->
                assertTrue("rgb=$rgb value=$value", value.isFinite() && value in 0f..1f)
            }
        }
    }

    @Test
    fun nearBlackHasNoChromaticMaterialConfidence() {
        val black = Rgb(0f, 0f, 0f)
        assertEquals(0f, MaterialConfidenceMath.reliability(black), 0f)
        assertEquals(0f, MaterialConfidenceMath.skinConfidence(black), 0f)
        assertEquals(0f, MaterialConfidenceMath.neutralConfidence(Rgb(0.001f, 0.001f, 0.001f)), 0.001f)
    }

    @Test
    fun representativeMaterialsPreferTheirOwnConfidence() {
        val skin = Rgb(0.72f, 0.42f, 0.30f)
        val foliage = Rgb(0.12f, 0.42f, 0.10f)
        val sky = Rgb(0.22f, 0.38f, 0.72f)
        val blueCyan = Rgb(0.10f, 0.45f, 0.62f)
        assertTrue(MaterialConfidenceMath.skinConfidence(skin) > MaterialConfidenceMath.foliageConfidence(skin))
        assertTrue(MaterialConfidenceMath.foliageConfidence(foliage) > MaterialConfidenceMath.skinConfidence(foliage))
        assertTrue(MaterialConfidenceMath.skyConfidence(sky) > MaterialConfidenceMath.foliageConfidence(sky))
        assertTrue(MaterialConfidenceMath.blueCyanConfidence(blueCyan) > MaterialConfidenceMath.neutralConfidence(blueCyan))
    }

    @Test
    fun luminanceConfidenceIsSoftAndReliabilityGated() {
        val dark = Rgb(0.08f, 0.08f, 0.08f)
        val bright = Rgb(1.2f, 1.1f, 1.0f)
        assertTrue(MaterialConfidenceMath.shadowConfidence(dark) > MaterialConfidenceMath.highlightConfidence(dark))
        assertTrue(MaterialConfidenceMath.highlightConfidence(bright) > MaterialConfidenceMath.shadowConfidence(bright))
        assertTrue(MaterialConfidenceMath.shadowConfidence(Rgb(0.001f, 0.001f, 0.001f)) < 0.01f)
    }

    @Test
    fun weightedProtectionIsBoundedAndHonorsWeights() {
        val rgb = Rgb(0.72f, 0.42f, 0.30f)
        val skinOnly = MaterialConfidenceWeights(
            skin = 1f, foliage = 0f, sky = 0f, blueCyan = 0f,
            neutral = 0f, highlight = 0f, shadow = 0f,
        )
        assertEquals(MaterialConfidenceMath.skinConfidence(rgb), MaterialConfidenceMath.weightedProtection(rgb, skinOnly), 0.000001f)
        assertTrue(MaterialConfidenceMath.weightedProtection(rgb).isFinite())
        assertTrue(MaterialConfidenceMath.weightedProtection(rgb) in 0f..1f)
    }
}

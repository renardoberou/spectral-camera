package com.renardoberou.spectralcamera.core.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

class AerochromeNeutralMathTest {
    @Test
    fun neutralProtectionIsFiniteBoundedAndContinuousAcrossARepresentativeWallRamp() {
        val ramp = listOf(
            Rgb(0.42f, 0.44f, 0.45f),
            Rgb(0.46f, 0.47f, 0.49f),
            Rgb(0.50f, 0.51f, 0.53f),
            Rgb(0.54f, 0.55f, 0.57f),
            Rgb(0.58f, 0.59f, 0.61f),
        )

        val weights = ramp.map { sample ->
            AerochromeNeutralMath.protectionWeight(
                source = sample,
                classification = sample,
                smoothLuma = sample.luma(),
            )
        }

        weights.forEach { weight ->
            assertTrue(weight.isFinite() && weight in 0f..1f)
        }
        weights.zipWithNext().forEach { (left, right) ->
            assertTrue("unexpected neutral-mask seam: $left -> $right", abs(right - left) < 0.35f)
        }
    }

    @Test
    fun neutralProtectionReducesObservedBlueLilacArtifactWhilePreservingLuma() {
        val source = Rgb(0.54f, 0.55f, 0.57f)
        val observedFalseColour = Rgb(0.28f, 0.31f, 0.82f)
        val protected = AerochromeNeutralMath.protectFalseColour(
            source = source,
            falseColour = observedFalseColour,
            classification = source,
            smoothLuma = source.luma(),
        )

        val sourceLuma = source.luma()
        val protectedChroma = max(
            max(protected.r, protected.g),
            protected.b,
        ) - minOf(protected.r, protected.g, protected.b)
        val observedChroma = max(
            max(observedFalseColour.r, observedFalseColour.g),
            observedFalseColour.b,
        ) - minOf(observedFalseColour.r, observedFalseColour.g, observedFalseColour.b)

        assertTrue("fixture must represent the confirmed artifact", observedChroma > 0.40f)
        assertTrue("neutral correction did not reduce false chroma", protectedChroma < observedChroma * 0.45f)
        assertEquals(sourceLuma, protected.luma(), 0.08f)
    }

    @Test
    fun colorfulFoliageAndWaterRetainFalseColourAuthority() {
        val foliage = Rgb(0.12f, 0.42f, 0.10f)
        val foliageFalseColour = Rgb(0.96f, 0.08f, 0.45f)
        val protectedFoliage = AerochromeNeutralMath.protectFalseColour(
            source = foliage,
            falseColour = foliageFalseColour,
            classification = foliage,
            smoothLuma = foliage.luma(),
            foliageConfidence = 1f,
        )

        val water = Rgb(0.08f, 0.40f, 0.72f)
        val waterFalseColour = Rgb(0.04f, 0.12f, 0.78f)
        val protectedWater = AerochromeNeutralMath.protectFalseColour(
            source = water,
            falseColour = waterFalseColour,
            classification = water,
            smoothLuma = water.luma(),
            waterConfidence = 1f,
        )

        assertEquals(foliageFalseColour, protectedFoliage)
        assertEquals(waterFalseColour, protectedWater)
    }

    @Test
    fun neutralProtectionFallsAwayAtArchitecturalEdges() {
        val source = Rgb(0.54f, 0.55f, 0.57f)
        val falseColour = Rgb(0.28f, 0.31f, 0.82f)
        val interiorWeight = AerochromeNeutralMath.protectionWeight(
            source = source,
            classification = source,
            smoothLuma = source.luma(),
        )
        val edgeWeight = AerochromeNeutralMath.protectionWeight(
            source = source,
            classification = source,
            smoothLuma = 0.38f,
        )

        assertTrue(interiorWeight > 0.45f)
        assertTrue(edgeWeight < interiorWeight)
        assertTrue(edgeWeight < 0.20f)
        assertTrue(AerochromeNeutralMath.protectFalseColour(
            source = source,
            falseColour = falseColour,
            classification = source,
            smoothLuma = 0.38f,
        ).b > 0.60f)
    }
}

private fun Rgb.luma(): Float = 0.299f * r + 0.587f * g + 0.114f * b
package com.renardoberou.spectralcamera.core

import org.junit.Assert.assertTrue
import org.junit.Test

class GrainPolicyTest {
    @Test
    fun namedPolicyStrengthsAreMonotonic() {
        val strengths = GrainPolicy.entries.map { it.strength }
        assertTrue(strengths.zipWithNext().all { (left, right) -> left < right })
    }

    @Test
    fun persistedSliderValuesMapToTheNamedPoliciesWithoutChangingTheirValues() {
        assertTrue(GrainPolicy.entries.all { GrainPolicy.fromPersistedValue(it.strength).strength == it.strength })
    }

    @Test
    fun persistedValuesAreNormalizedToTheNearestExplicitPolicy() {
        assertTrue(GrainPolicy.fromPersistedValue(0.04f) == GrainPolicy.OFF)
        assertTrue(GrainPolicy.fromPersistedValue(0.72f) == GrainPolicy.COARSE)
        assertTrue(GrainPolicy.fromPersistedValue(1.10f) == GrainPolicy.EXTREME)
    }

    @Test
    fun captureSettingsCarryTheSelectedPolicyStrengthToTheRenderer() {
        val captureSettings = CameraSettings(
            adjustments = ManualAdjustments(grain = GrainPolicy.EXTREME.strength),
        )
        assertTrue(GrainPolicy.captureStrength(captureSettings) == GrainPolicy.EXTREME.strength)
        assertTrue(
            GrainPolicy.captureStrength(captureSettings.copy(adjustments = ManualAdjustments())) ==
                GrainPolicy.OFF.strength,
        )
    }
}
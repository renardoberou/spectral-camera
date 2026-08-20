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
}
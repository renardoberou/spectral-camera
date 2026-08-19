package com.renardoberou.spectralcamera.core.gl

import com.renardoberou.spectralcamera.core.SpectralPreset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedFilmShaderContractTest {
    @Test
    fun sharedStageIsGenericAndRunsAfterTheSpectralFrontEnd() {
        assertTrue(FRAGMENT_BODY.contains("uniform vec4 uSharedTone"))
        assertTrue(FRAGMENT_BODY.contains("uniform vec4 uSharedProtection"))
        assertTrue(FRAGMENT_BODY.contains("uniform vec4 uSharedDensity"))
        assertTrue(FRAGMENT_BODY.contains("vec3 sharedFujiStage"))
        assertTrue(FRAGMENT_BODY.contains("uniform vec4 uSharedHueA"))
        assertTrue(FRAGMENT_BODY.contains("uniform vec2 uSharedHueB"))
        assertTrue(FRAGMENT_BODY.contains("float sharedProtectionConfidence"))
        assertTrue(FRAGMENT_BODY.contains("float hueSectorWeight"))
        assertTrue(FRAGMENT_BODY.indexOf("vec3 c = presetColor") < FRAGMENT_BODY.indexOf("c = sharedFujiStage(c)"))
    }

    @Test
    fun sharedStageUsesDefinedSmoothstepEdgesForTheToe() {
        assertFalse(FRAGMENT_BODY.contains("smoothstep(0.34, 0.0, l)"))
        assertTrue(FRAGMENT_BODY.contains("float toe = uSharedTone.x * (1.0 - smoothstep(0.0, 0.34, l));"))
    }

    @Test
    fun grainShadowLiftUsesDefinedSmoothstepEdges() {
        assertFalse(FRAGMENT_BODY.contains("float shadowLift = smoothstep(0.34, 0.02, gLuma);"))
        assertTrue(FRAGMENT_BODY.contains("float shadowLift = 1.0 - smoothstep(0.02, 0.34, gLuma);"))
    }

    @Test
    fun newPresetsUseTheStandardFilmShaderRange() {
        assertEquals(16, SpectralPreset.ARCHIVE_CHROME.toShaderIndex())
        assertEquals(17, SpectralPreset.CINEMATIC_NEUTRAL.toShaderIndex())
        assertEquals(18, SpectralPreset.WARM_NEGATIVE.toShaderIndex())
    }

    @Test
    fun sharedStageDoesNotPrecedeSpectralClassification() {
        assertTrue(FRAGMENT_BODY.indexOf("c = sharedFujiStage(c)") > FRAGMENT_BODY.indexOf("vec3 presetColor"))
    }

    @Test
    fun aerochromeNeutralConfidenceIsCapturedBeforeFalseColourFinishing() {
        val neutralConfidence = FRAGMENT_BODY.indexOf("float neutralArtifactConfidence")
        val aerochromeOutput = FRAGMENT_BODY.indexOf("vec3 ir = mix(base, folCol, vegAll);")
        val diagnostic = FRAGMENT_BODY.indexOf("gClassifierDebug = vec3(")

        assertTrue("neutral confidence stage is missing", neutralConfidence >= 0)
        assertTrue("Aerochrome diagnostic does not expose the neutral stage", diagnostic >= 0)
        assertTrue("neutral confidence must precede Aerochrome output", neutralConfidence < aerochromeOutput)
    }

    @Test
    fun classifierDiagnosticRequiresADevelopmentBuildAtTheRendererBoundary() {
        assertTrue(classifierDebugEnabled(requested = true, debugBuild = true))
        assertFalse(classifierDebugEnabled(requested = true, debugBuild = false))
        assertFalse(classifierDebugEnabled(requested = false, debugBuild = true))
    }
}

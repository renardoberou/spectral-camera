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

    @Test
    fun photographicGrainUsesTheExplicitPolicyStrength() {
        assertTrue(FRAGMENT_BODY.contains("float grainAmp = uGrain *"))
        assertFalse(FRAGMENT_BODY.contains("float effGrain = uGrain + uGrainBase;"))
    }

    @Test
    fun zeroPolicyGatesTheEntireOptionalPhotographicGrainStage() {
        val gate = FRAGMENT_BODY.indexOf("if (uGrain > 0.001)")
        val end = FRAGMENT_BODY.indexOf("// channel swap")
        assertTrue("explicit grain gate is missing", gate >= 0)
        assertTrue("grain gate must contain the whole optional stage", end > gate)
        assertTrue(FRAGMENT_BODY.indexOf("float nLuma = filmGrain", gate) < end)
        assertTrue(FRAGMENT_BODY.indexOf("float chromaAmt", gate) < end)
        assertFalse(FRAGMENT_BODY.contains("uGrainBase"))
    }

    @Test
    fun ignDitherRemainsPresentOutsideThePhotographicGrainGate() {
        val gate = FRAGMENT_BODY.indexOf("if (uGrain > 0.001)")
        val ign = FRAGMENT_BODY.indexOf("float ign =")
        assertTrue(ign > gate)
        assertTrue(FRAGMENT_BODY.contains("c += ign * 0.006 * grainDitherScale"))
    }

    @Test
    fun stockClumpScaleAndMonoChromaGateRemainIntact() {
        assertTrue(FRAGMENT_BODY.contains("grainUv / max(uHaloGrain.w, 0.05)"))
        assertTrue(FRAGMENT_BODY.contains("float chromaAmt = (uPreset <= 5) ? 0.0 : (1.0 - uStdTone3.x);"))
        assertTrue(FRAGMENT_BODY.contains("if (chromaAmt > 0.001)"))
    }

    @Test
    fun extremeMonoGrainRemainsLuminanceDominantWithoutDigitalColorSpeckle() {
        assertTrue(FRAGMENT_BODY.contains("vec3 grainDelta = vec3(nLuma);"))
        assertTrue(FRAGMENT_BODY.contains("grainDelta += chromaAmt * 0.35 *"))
        assertFalse(FRAGMENT_BODY.contains("uGrain * 0.35"))
    }
}

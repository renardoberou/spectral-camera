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
        assertTrue(FRAGMENT_BODY.contains("float grainAmp = uGrain * 0.10"))
        assertFalse(FRAGMENT_BODY.contains("float effGrain = uGrain + uGrainBase;"))
    }

    @Test
    fun zeroPolicyGatesTheEntireOptionalPhotographicGrainStage() {
        val block = balancedBlockAfter("if (uGrain > 0.001)")
        assertTrue("explicit grain gate is missing", block.isNotEmpty())
        assertTrue(block.contains("float nLuma = filmGrain"))
        assertTrue(block.contains("float chromaAmt"))
        assertTrue(block.contains("float clumpMask"))
        assertTrue(block.contains("c += grainDelta * grainAmp * 2.2 * clumpMask"))
        assertFalse(FRAGMENT_BODY.contains("uGrainBase"))
    }

    @Test
    fun ignDitherRemainsPresentOutsideThePhotographicGrainGate() {
        val gateStart = FRAGMENT_BODY.indexOf("if (uGrain > 0.001)")
        val gate = balancedBlockEnd("if (uGrain > 0.001)")
        val ign = FRAGMENT_BODY.indexOf("float ign =")
        assertTrue(gateStart >= 0)
        assertTrue("IGN dither must follow the complete grain block", ign > gate)
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
        val block = balancedBlockAfter("if (uGrain > 0.001)")
        assertTrue(block.contains("vec3 grainDelta = vec3(nLuma);"))
        assertTrue(block.contains("float chromaAmt = (uPreset <= 5) ? 0.0 : (1.0 - uStdTone3.x);"))
        assertTrue(block.contains("grainDelta += chromaAmt * 0.35 * vec3(nCr, nCg, nCb);"))
        assertFalse(block.contains("uGrain * 0.35"))
    }

    private fun balancedBlockAfter(marker: String): String {
        val start = FRAGMENT_BODY.indexOf(marker)
        if (start < 0) return ""
        val open = FRAGMENT_BODY.indexOf('{', start)
        if (open < 0) return ""
        var depth = 0
        for (index in open until FRAGMENT_BODY.length) {
            when (FRAGMENT_BODY[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return FRAGMENT_BODY.substring(open, index + 1)
                }
            }
        }
        return ""
    }

    private fun balancedBlockEnd(marker: String): Int {
        val block = balancedBlockAfter(marker)
        return if (block.isEmpty()) -1 else FRAGMENT_BODY.indexOf(marker) + FRAGMENT_BODY.substring(FRAGMENT_BODY.indexOf(marker)).indexOf(block) + block.length
    }
}

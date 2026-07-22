package com.renardoberou.spectralcamera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusCapabilitiesTest {
    private fun capabilities(
        canFocus: Boolean = true,
        continuous: Boolean = false,
        tap: Boolean = false,
        macro: Boolean = false,
        manual: Boolean = false,
        infinity: Boolean = false,
    ) = CameraCapabilities(
        hasFlash = false,
        canFocus = canFocus,
        exposureRange = 0..0,
        exposureStep = 1f / 3f,
        zoomRange = 1f..1f,
        continuousFocusSupported = continuous,
        tapFocusSupported = tap,
        macroFocusSupported = macro,
        manualFocusSupported = manual,
        infinityFocusSupported = infinity,
    )

    @Test
    fun fixedLensExposesOnlyFixedFocus() {
        val fixed = capabilities(canFocus = false)

        assertTrue(fixed.supportsFocusMode(FocusMode.FIXED))
        assertFalse(fixed.supportsFocusMode(FocusMode.CONTINUOUS))
        assertEquals(FocusMode.FIXED, fixed.supportedOrFallback(FocusMode.MANUAL))
    }

    @Test
    fun requestedSupportedModeIsPreserved() {
        val manualLens = capabilities(
            continuous = true,
            tap = true,
            manual = true,
            infinity = true,
        )

        assertEquals(FocusMode.MANUAL, manualLens.supportedOrFallback(FocusMode.MANUAL))
        assertEquals(FocusMode.INFINITY, manualLens.supportedOrFallback(FocusMode.INFINITY))
    }

    @Test
    fun continuousIsPreferredFallbackOnGeneralAutofocusLens() {
        val autofocusLens = capabilities(continuous = true, tap = true)

        assertEquals(FocusMode.CONTINUOUS, autofocusLens.supportedOrFallback(FocusMode.MANUAL))
    }

    @Test
    fun tapLockIsFallbackWhenContinuousIsUnavailable() {
        val tapOnlyLens = capabilities(tap = true)

        assertEquals(FocusMode.TAP_LOCK, tapOnlyLens.supportedOrFallback(FocusMode.CONTINUOUS))
    }

    @Test
    fun macroIsNotClaimedUnlessReported() {
        val ordinaryLens = capabilities(continuous = true, tap = true, manual = true, infinity = true)

        assertFalse(ordinaryLens.supportsFocusMode(FocusMode.MACRO))
        assertEquals(FocusMode.CONTINUOUS, ordinaryLens.supportedOrFallback(FocusMode.MACRO))
    }
}

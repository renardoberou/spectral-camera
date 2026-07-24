package com.renardoberou.spectralcamera.core.camera

import com.renardoberou.spectralcamera.core.WhiteBalancePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteBalanceRequestPlannerTest {
    @Test
    fun `direct CCT is preferred when the requested Kelvin is in range`() {
        val support = WhiteBalanceSupport(
            cctRange = 2_500..7_000,
            fixedModes = setOf(FixedWhiteBalanceMode.DAYLIGHT),
        )

        assertEquals(
            WhiteBalanceRequest.Cct(5_500),
            WhiteBalanceRequestPlanner.plan(WhiteBalancePreset.SUNNY, support),
        )
    }

    @Test
    fun `named camera mode is used when CCT is unavailable`() {
        val support = WhiteBalanceSupport(
            fixedModes = setOf(
                FixedWhiteBalanceMode.DAYLIGHT,
                FixedWhiteBalanceMode.CLOUDY_DAYLIGHT,
                FixedWhiteBalanceMode.INCANDESCENT,
                FixedWhiteBalanceMode.FLUORESCENT,
            ),
        )

        assertEquals(
            WhiteBalanceRequest.Fixed(FixedWhiteBalanceMode.CLOUDY_DAYLIGHT),
            WhiteBalanceRequestPlanner.plan(WhiteBalancePreset.CLOUDY, support),
        )
        assertEquals(
            WhiteBalanceRequest.Fixed(FixedWhiteBalanceMode.FLUORESCENT),
            WhiteBalanceRequestPlanner.plan(WhiteBalancePreset.WHITE_LIGHT, support),
        )
    }

    @Test
    fun `unsupported manual preset degrades to Auto`() {
        val support = WhiteBalanceSupport(fixedModes = setOf(FixedWhiteBalanceMode.DAYLIGHT))

        assertTrue(
            WhiteBalanceRequestPlanner.plan(WhiteBalancePreset.TUNGSTEN, support) ===
                WhiteBalanceRequest.Auto,
        )
    }

    @Test
    fun `capability sets distinguish direct Kelvin from fixed fallbacks`() {
        val support = WhiteBalanceSupport(
            cctRange = 3_000..6_500,
            fixedModes = setOf(FixedWhiteBalanceMode.INCANDESCENT),
        )

        val supported = WhiteBalanceRequestPlanner.supportedPresets(support)
        val direct = WhiteBalanceRequestPlanner.directCctPresets(support)

        assertTrue(WhiteBalancePreset.AUTO in supported)
        assertTrue(WhiteBalancePreset.SUNNY in supported)
        assertTrue(WhiteBalancePreset.CLOUDY in direct)
        assertTrue(WhiteBalancePreset.TUNGSTEN in direct)
        assertTrue(WhiteBalancePreset.STREETLIGHT in supported)
        assertFalse(WhiteBalancePreset.STREETLIGHT in direct)
        assertTrue(WhiteBalancePreset.WHITE_LIGHT in supported)
    }
}

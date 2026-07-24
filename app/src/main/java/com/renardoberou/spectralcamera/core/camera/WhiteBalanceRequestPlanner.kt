package com.renardoberou.spectralcamera.core.camera

import com.renardoberou.spectralcamera.core.WhiteBalancePreset

/** Device-independent request planning. Android Camera2 constants are mapped at the controller boundary. */
internal enum class FixedWhiteBalanceMode {
    INCANDESCENT,
    FLUORESCENT,
    WARM_FLUORESCENT,
    DAYLIGHT,
    CLOUDY_DAYLIGHT,
    TWILIGHT,
    SHADE,
}

internal data class WhiteBalanceSupport(
    val cctRange: IntRange? = null,
    val fixedModes: Set<FixedWhiteBalanceMode> = emptySet(),
)

internal sealed interface WhiteBalanceRequest {
    object Auto : WhiteBalanceRequest
    data class Cct(val kelvin: Int) : WhiteBalanceRequest
    data class Fixed(val mode: FixedWhiteBalanceMode) : WhiteBalanceRequest
}

internal object WhiteBalanceRequestPlanner {
    fun plan(
        preset: WhiteBalancePreset,
        support: WhiteBalanceSupport,
    ): WhiteBalanceRequest {
        if (preset == WhiteBalancePreset.AUTO) return WhiteBalanceRequest.Auto
        val kelvin = preset.kelvin ?: return WhiteBalanceRequest.Auto
        if (support.cctRange?.let { kelvin in it } == true) {
            return WhiteBalanceRequest.Cct(kelvin)
        }
        val fixed = fallbackOrder(preset).firstOrNull { it in support.fixedModes }
        return fixed?.let { WhiteBalanceRequest.Fixed(it) } ?: WhiteBalanceRequest.Auto
    }

    fun supportedPresets(support: WhiteBalanceSupport): Set<WhiteBalancePreset> =
        WhiteBalancePreset.values().filterTo(linkedSetOf()) { preset ->
            preset == WhiteBalancePreset.AUTO || plan(preset, support) !== WhiteBalanceRequest.Auto
        }

    fun directCctPresets(support: WhiteBalanceSupport): Set<WhiteBalancePreset> =
        WhiteBalancePreset.values().filterTo(linkedSetOf()) { preset ->
            val kelvin = preset.kelvin
            kelvin != null && support.cctRange?.let { kelvin in it } == true
        }

    private fun fallbackOrder(preset: WhiteBalancePreset): List<FixedWhiteBalanceMode> = when (preset) {
        WhiteBalancePreset.AUTO -> emptyList()
        WhiteBalancePreset.SUNNY -> listOf(FixedWhiteBalanceMode.DAYLIGHT)
        WhiteBalancePreset.CLOUDY -> listOf(
            FixedWhiteBalanceMode.CLOUDY_DAYLIGHT,
            FixedWhiteBalanceMode.SHADE,
        )
        WhiteBalancePreset.TUNGSTEN -> listOf(FixedWhiteBalanceMode.INCANDESCENT)
        WhiteBalancePreset.WHITE_LIGHT -> listOf(
            FixedWhiteBalanceMode.FLUORESCENT,
            FixedWhiteBalanceMode.WARM_FLUORESCENT,
        )
        WhiteBalancePreset.STREETLIGHT -> listOf(
            FixedWhiteBalanceMode.INCANDESCENT,
            FixedWhiteBalanceMode.TWILIGHT,
            FixedWhiteBalanceMode.WARM_FLUORESCENT,
        )
    }
}

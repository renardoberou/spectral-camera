package com.renardoberou.spectralcamera.ui.screens

import com.renardoberou.spectralcamera.core.LookFamily
import com.renardoberou.spectralcamera.core.GrainPolicy
import com.renardoberou.spectralcamera.core.SpectralPreset

/** Pure layout/catalog contracts for the compact live-camera surface. */
internal val compactCameraActionInventory = listOf(
    "Exposure", "Focus", "WB", "Presets", "More",
)

internal val compactPresetFamilyOrder = listOf(
    LookFamily.STANDARD_FILM,
    LookFamily.AEROCHROME,
    LookFamily.MONOCHROME_IR,
)

internal val grainOptionLabels = listOf("Off", "Fine", "Medium", "Coarse", "Extreme")
internal val grainOptionValues = GrainPolicy.entries.map { it.strength }

internal const val presetTileMinTouchSizeDp = 48

internal fun presetGridColumnCount(widthDp: Int): Int = when {
    widthDp >= 800 -> 6
    widthDp >= 520 -> 5
    widthDp >= 360 -> 4
    else -> 3
}

internal fun presetsForLazyGrid(current: SpectralPreset): List<SpectralPreset> {
    return compactPresetFamilyOrder.flatMap { family ->
        SpectralPreset.values().filter { it.family == family }
    }
}

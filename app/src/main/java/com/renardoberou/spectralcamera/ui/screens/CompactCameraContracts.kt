package com.renardoberou.spectralcamera.ui.screens

import com.renardoberou.spectralcamera.core.LookFamily
import com.renardoberou.spectralcamera.core.SpectralPreset

/** Pure layout/catalog contracts for the compact live-camera surface. */
internal val compactCameraActionInventory = listOf(
    "Exposure", "Focus", "WB", "Capture", "Switch camera", "Torch", "Gallery", "More",
)

internal val compactPresetFamilyOrder = listOf(
    LookFamily.STANDARD_FILM,
    LookFamily.AEROCHROME,
    LookFamily.MONOCHROME_IR,
)

internal val grainOptionLabels = listOf("Off", "Fine", "Medium", "Coarse", "Extreme")
internal val grainOptionValues = listOf(0f, 0.25f, 0.5f, 0.85f, 1.25f)

internal fun presetGridColumnCount(widthDp: Int): Int = when {
    widthDp >= 800 -> 4
    widthDp >= 520 -> 3
    else -> 2
}

internal fun presetsForLazyGrid(current: SpectralPreset): List<SpectralPreset> {
    return compactPresetFamilyOrder.flatMap { family ->
        SpectralPreset.values().filter { it.family == family }
    }
}

package com.renardoberou.spectralcamera.core

import android.net.Uri

enum class SpectralPreset(
    val label: String,
    val description: String,
) {
    B_W_INFRARED(
        label = "B&W Infrared",
        description = "Classic monochrome IR with bright foliage and dark sky.",
    ),
    HIGH_CONTRAST_IR(
        label = "High Contrast IR",
        description = "Deeper blacks, brighter whites, stronger drama.",
    ),
    WHITE_FOLIAGE_DARK_SKY(
        label = "White Foliage / Dark Sky",
        description = "Leaf whitening with aggressive sky suppression.",
    ),
    AEROCHROME_FALSE_COLOR(
        label = "Aerochrome-Style False Colour",
        description = "Red foliage, cyan sky, filmic false-colour balance.",
    ),
    AEROCHROME_GOLD(
        label = "Aerochrome Gold (orange filter)",
        description = "EIR with orange filter: golden foliage, teal sky.",
    ),
    RED_720_STYLE(
        label = "Red 720nm-Style",
        description = "Warm red infrared look with strong foliage response.",
    ),
    BLUE_CYAN_SPECTRAL(
        label = "Blue/Cyan Spectral",
        description = "Cool cyan-blue spectral shift with darkened shadows.",
    ),
    FAKE_THERMAL_PALETTE(
        label = "Fake Thermal Palette",
        description = "Heat-map style palette for scientific / surreal looks.",
    ),
    NIGHT_SURVEILLANCE_IR(
        label = "Night Surveillance IR",
        description = "Utility-style monochrome with green tint and grain.",
    ),
}

enum class SensorMode(val label: String) {
    SIMULATED_IR("Simulated IR"),
    EXTERNAL_IR("External IR"),
    THERMAL("External Thermal"),
}

enum class ChannelSwapMode(val label: String) {
    NONE("None"),
    RB_SWAP("Swap R/B"),
    RG_SWAP("Swap R/G"),
    GB_SWAP("Swap G/B"),
}

data class ManualAdjustments(
    val contrast: Float = 1.0f,
    val exposureCompensation: Float = 0f,
    val blacks: Float = 0f,
    val whites: Float = 0f,
    val bloom: Float = 0f,
    val grain: Float = 0f,
    val sharpness: Float = 0f,
    val redChannelWeight: Float = 1.0f,
    val greenFoliageLift: Float = 0f,
    val blueSkySuppression: Float = 0f,
    val hueRotation: Float = 0f,
    val saturation: Float = 1.0f,
    val channelSwapMode: ChannelSwapMode = ChannelSwapMode.NONE,
)

data class CameraSettings(
    val preset: SpectralPreset = SpectralPreset.B_W_INFRARED,
    val adjustments: ManualAdjustments = ManualAdjustments(),
    val saveOriginal: Boolean = false,
    val frontFacing: Boolean = false,
    val sensorMode: SensorMode = SensorMode.SIMULATED_IR,
    /** Hardware exposure compensation index applied to the camera itself. */
    val hardwareEv: Float = 0f,
)

data class CameraCapabilities(
    val hasFlash: Boolean,
    val canFocus: Boolean,
    val exposureRange: IntRange,
    val exposureStep: Float,
    val zoomRange: ClosedFloatingPointRange<Float>,
) {
    val exposureSupported: Boolean get() = exposureRange.first != exposureRange.last
}

data class GalleryItem(
    val uri: Uri,
    val displayName: String,
    val dateTakenMillis: Long,
    val presetLabel: String,
    val sensorModeLabel: String,
    val isOriginal: Boolean,
)

data class HardwareTestState(
    val instruction: String = "Point a TV remote at the camera and press a button.",
    val statusMessage: String = "Waiting for a flashing near-IR hotspot.",
    val detected: Boolean = false,
    val confidence: Float = 0f,
    val peakLuma: Float = 0f,
    val framesAnalyzed: Int = 0,
) {
    companion object {
        fun idle() = HardwareTestState()
    }
}

data class CaptureResult(
    val processedUri: Uri,
    val originalUri: Uri?,
    val displayName: String,
)

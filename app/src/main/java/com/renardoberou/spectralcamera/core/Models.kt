package com.renardoberou.spectralcamera.core

import android.net.Uri

/** Which coherent look family a preset belongs to, for grouped UI and product messaging. */
enum class LookFamily(val label: String) {
    MONOCHROME_IR("Monochrome IR"),
    AEROCHROME("Aerochrome / False Colour"),
}

enum class SpectralPreset(
    val label: String,
    val description: String,
    val family: LookFamily,
) {
    // ---- Monochrome IR family: six differentiated stock personalities,
    // all sharing one IR-luminance/tone-curve engine parameterized by
    // FilmLookLibrary.monoLookFor(). See core/FilmLook.kt for the numbers.
    B_W_INFRARED(
        label = "Rollei Infrared 400",
        description = "Reference monochrome IR: textured glowing foliage, dense gradated skies, fine grain, restrained anti-halation glow.",
        family = LookFamily.MONOCHROME_IR,
    ),
    HIGH_CONTRAST_IR(
        label = "Kodak HIE",
        description = "Deep Wood effect, hardest contrast, near-black skies, no anti-halation backing - the famous ethereal bloom.",
        family = LookFamily.MONOCHROME_IR,
    ),
    WHITE_FOLIAGE_DARK_SKY(
        label = "Ilford SFX 200",
        description = "Milder extended-red look, finer tonality, gentler foliage glow, minimal halation.",
        family = LookFamily.MONOCHROME_IR,
    ),
    MONO_IR_MODERATE(
        label = "Moderate IR (Konica-style)",
        description = "Balanced middle ground between restrained and dramatic - a flexible, broadly usable IR grade.",
        family = LookFamily.MONOCHROME_IR,
    ),
    MONO_IR_FINE_GRAIN(
        label = "Fine-Grain Infrared",
        description = "Neutral, print-oriented IR: finest grain, mildest Wood effect, tightest halation - clean rather than moody.",
        family = LookFamily.MONOCHROME_IR,
    ),
    MONO_IR_SOFT_VINTAGE(
        label = "Soft Vintage IR",
        description = "Romantic, low-contrast print look: milky highlights, dreamy wide halation, coarser grain, lifted blacks.",
        family = LookFamily.MONOCHROME_IR,
    ),

    // ---- Aerochrome family: five coherent EIR grades sharing one
    // physically-grounded false-colour engine, parameterized by
    // FilmLookLibrary.aeroLookFor().
    AEROCHROME_FALSE_COLOR(
        label = "Aerochrome Classic",
        description = "The reference EIR grade: magenta-red foliage, deep cyan sky, filmic false-colour balance.",
        family = LookFamily.AEROCHROME,
    ),
    AEROCHROME_SOFT(
        label = "Aerochrome Soft",
        description = "Gentler contrast, pastel foliage magenta, paler sky, minimal glow - an easygoing everyday grade.",
        family = LookFamily.AEROCHROME,
    ),
    AEROCHROME_DENSE(
        label = "Aerochrome Dense",
        description = "Punchier contrast, deeper cyan sky, more saturation headroom, dramatic halation - the hero-shot grade.",
        family = LookFamily.AEROCHROME,
    ),
    AEROCHROME_GOLD(
        label = "Aerochrome Gold (orange filter)",
        description = "EIR with orange filter: golden foliage, teal sky.",
        family = LookFamily.AEROCHROME,
    ),
    AEROCHROME_FADED(
        label = "Aerochrome Faded / Vintage",
        description = "Desaturated, lifted blacks, warm cast, hazy pale sky - an aged-print character.",
        family = LookFamily.AEROCHROME,
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
    /** Hardware exposure compensation, in photographic stops. */
    val hardwareEv: Float = 0f,
    /** Full-manual exposure: AE off, ISO and shutter set directly. */
    val manualMode: Boolean = false,
    val manualIso: Int = 400,
    /** Shutter time in nanoseconds (default 1/125s). */
    val manualShutterNs: Long = 8_000_000L,
    /** Look intensity: 1.0 = full film effect, lower blends toward source. */
    val intensity: Float = 1f,
    /** Preview-only clipping zebras over near-blown highlights. */
    val zebraEnabled: Boolean = false,
)

data class CameraCapabilities(
    val hasFlash: Boolean,
    val canFocus: Boolean,
    val exposureRange: IntRange,
    val exposureStep: Float,
    val zoomRange: ClosedFloatingPointRange<Float>,
    /** Fixed lens aperture (phones have no iris), for display only. */
    val aperture: Float? = null,
    /** Sensor ISO range (also the bounds for full-manual mode). */
    val isoRange: IntRange? = null,
    /** Sensor exposure-time range in nanoseconds, for full-manual mode. */
    val exposureTimeRange: LongRange? = null,
    /** Whether the camera declares the MANUAL_SENSOR capability. */
    val manualExposureSupported: Boolean = false,
) {
    val minStops: Float get() = exposureRange.first * safeStep
    val maxStops: Float get() = exposureRange.last * safeStep
    private val safeStep: Float get() = if (exposureStep > 0f) exposureStep else 1f / 3f

    /** Converts photographic stops to the camera's exposure compensation index. */
    fun stopsToIndex(stops: Float): Int =
        Math.round(stops / safeStep).coerceIn(exposureRange.first, exposureRange.last)

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

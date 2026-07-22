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

/** Processed-file size, source-resolution, and capture-speed policy. */
enum class OutputMode(
    val label: String,
    val description: String,
) {
    FULL_RESOLUTION(
        label = "Full Resolution",
        description = "Highest practical still source and the largest processed export the GPU supports.",
    ),
    HQ_1080(
        label = "HQ 1080",
        description = "High-resolution 16:9 render, then high-quality downsample to exact 1920×1080 (or 1080×1920).",
    ),
    FAST_1080(
        label = "Fast 1080",
        description = "Lower-latency 16:9 capture and exact Full HD processing for faster turnaround.",
    ),
}

/**
 * Capture-domain dynamic-range strategy.
 *
 * THREE_FRAME brackets the scene around the current exposure, aligns the JPEG
 * frames, estimates linear-light radiance, deghosts disagreement toward the
 * reference exposure, and normalizes/tone-maps before the spectral film model.
 */
enum class HdrCaptureMode(
    val label: String,
    val description: String,
) {
    OFF(
        label = "Standard",
        description = "One shutter frame. Fastest and best for motion.",
    ),
    THREE_FRAME(
        label = "Computational HDR",
        description = "Three bracketed frames merged before synthetic NIR and film rendering.",
    ),
}

/** SDR base rendition used after scene-linear HDR merge and before film rendering. */
enum class HdrToneMap(
    val label: String,
    val description: String,
) {
    NATURAL(
        label = "Natural",
        description = "Balanced highlight recovery with restrained shadow lift.",
    ),
    FILMIC(
        label = "Filmic",
        description = "Deeper toe and a longer highlight shoulder before the selected film stock.",
    ),
    LOW_CONTRAST(
        label = "Low Contrast",
        description = "Maximum range compression for difficult backlight and later manual grading.",
    ),
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
    val outputMode: OutputMode = OutputMode.FULL_RESOLUTION,
    /** Optional multi-frame exposure fusion before the film renderer. */
    val hdrCaptureMode: HdrCaptureMode = HdrCaptureMode.OFF,
    /** Tone map used to normalize merged radiance into the film engine's SDR working range. */
    val hdrToneMap: HdrToneMap = HdrToneMap.NATURAL,
    /** Attach a newly generated gain map to the processed JPEG on Android 14+. */
    val ultraHdrExport: Boolean = false,
    /** Save a true DNG sidecar when the active camera supports RAW+JPEG capture. */
    val saveRawSidecar: Boolean = false,
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
    val aperture: Float? = null,
    val isoRange: IntRange? = null,
    val exposureTimeRange: LongRange? = null,
    val manualExposureSupported: Boolean = false,
    val rawJpegCaptureSupported: Boolean = false,
    /** Whether the current AE compensation range can provide at least three distinct bracket values. */
    val hdrBracketSupported: Boolean = false,
) {
    val minStops: Float get() = exposureRange.first * safeStep
    val maxStops: Float get() = exposureRange.last * safeStep
    private val safeStep: Float get() = if (exposureStep > 0f) exposureStep else 1f / 3f

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
    val isUltraHdr: Boolean = false,
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
    val rawUri: Uri? = null,
    val displayName: String,
    val ultraHdr: Boolean = false,
)
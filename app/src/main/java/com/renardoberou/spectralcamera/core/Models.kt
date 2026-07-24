package com.renardoberou.spectralcamera.core

import android.graphics.Bitmap
import android.net.Uri

enum class LookFamily(val label: String) {
    MONOCHROME_IR("Monochrome IR"),
    AEROCHROME("Aerochrome / False Colour"),
    STANDARD_FILM("Classic Film"),
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
    EKTAR_100(
        label = "Kodak Ektar 100",
        description = "World's finest-grain colour negative: vivid but faithful saturation with the famous red/blue pop, punchy clean contrast, nearly invisible grain.",
        family = LookFamily.STANDARD_FILM,
    ),
    CINESTILL_800T(
        label = "CineStill 800T",
        description = "Tungsten-balanced cine stock without remjet: signature red halation around lights, cool teal daylight cast, lifted cinematic blacks.",
        family = LookFamily.STANDARD_FILM,
    ),
    TRI_X_400(
        label = "Kodak Tri-X 400",
        description = "The photojournalism classic: punchy panchromatic B&W, rich textured blacks, forgiving highlights, honest gritty grain.",
        family = LookFamily.STANDARD_FILM,
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

enum class OutputMode(
    val label: String,
    val description: String,
) {
    FULL_RESOLUTION(
        label = "Full Resolution",
        description = "Highest practical still source and the largest processed export the GPU can complete safely.",
    ),
    HQ_1080(
        label = "HQ 1080",
        description = "High-resolution 16:9 render, then high-quality downsample to exact 1920×1080 or 1080×1920.",
    ),
    FAST_1080(
        label = "Fast 1080",
        description = "Lower-latency 16:9 capture and exact Full HD processing.",
    ),
}

enum class HdrCaptureMode(
    val label: String,
    val description: String,
) {
    OFF(
        label = "Standard",
        description = "One JPEG-derived frame. Fastest for action and exact single-frame timing.",
    ),
    THREE_FRAME(
        label = "Computational HDR",
        description = "Bracketed JPEG frames with movement-safe fusion before synthetic NIR and film rendering.",
    ),
    RAW_THREE_FRAME(
        label = "True RAW HDR",
        description = "RAW_SENSOR Bayer frames with movement-safe fusion before demosaic, colour conversion, synthetic NIR, and film rendering.",
    ),
}

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

/** Standard-capture creative sequence, deliberately separate from HDR. */
enum class DoubleExposureMode(
    val label: String,
    val description: String,
) {
    OFF(
        label = "Single exposure",
        description = "One Standard source frame.",
    ),
    FILM_BALANCED(
        label = "Double exposure",
        description = "Capture two separate compositions, combine them as balanced half-exposures in linear light, then apply the selected film look once.",
    ),
}

/** Photographer-facing focus behavior. Unsupported modes are disabled per active lens. */
enum class FocusMode(
    val label: String,
    val description: String,
) {
    CONTINUOUS(
        label = "Continuous AF",
        description = "The camera continuously follows focus. A tap briefly prioritizes one subject, then continuous focus resumes.",
    ),
    TAP_LOCK(
        label = "Tap & Lock",
        description = "Tap a subject once and hold that focus distance until you unlock it or choose another mode.",
    ),
    MACRO(
        label = "Macro AF",
        description = "Tap to run a close-range autofocus scan and hold it. Available only on lenses that report a macro autofocus mode.",
    ),
    MANUAL(
        label = "Manual Focus",
        description = "Move the focus-position control directly from infinity toward the lens's nearest supported distance.",
    ),
    INFINITY(
        label = "Infinity",
        description = "Hold the lens at its farthest focus position for distant landscapes, skies, and architecture.",
    ),
    FIXED(
        label = "Fixed Focus",
        description = "This camera reports a non-moving lens; focus distance cannot be changed.",
    ),
}

enum class FocusDistanceCalibration(val label: String) {
    UNCALIBRATED("Uncalibrated"),
    APPROXIMATE("Approximate"),
    CALIBRATED("Calibrated"),
}

enum class FocusTapResult {
    FOCUSED,
    LOCKED,
    FAILED,
    METERED,
    IGNORED,
    UNSUPPORTED,
}

enum class WhiteBalancePreset(
    val label: String,
    val kelvin: Int?,
    val description: String,
) {
    AUTO(
        label = "Auto",
        kelvin = null,
        description = "Adaptive camera white balance. The camera may change its estimate as the scene changes.",
    ),
    SUNNY(
        label = "Sunny",
        kelvin = 5_500,
        description = "Daylight reference for direct sun and daylight-balanced colour film.",
    ),
    CLOUDY(
        label = "Cloudy",
        kelvin = 6_500,
        description = "Warmer compensation for overcast daylight.",
    ),
    TUNGSTEN(
        label = "Tungsten",
        kelvin = 3_200,
        description = "Tungsten-balanced reference, including CineStill 800T / VISION3 500T intent.",
    ),
    WHITE_LIGHT(
        label = "White light",
        kelvin = 4_100,
        description = "Neutral starting point for cool-white LED and fluorescent lighting.",
    ),
    STREETLIGHT(
        label = "Streetlight",
        kelvin = 2_600,
        description = "Very warm reference for sodium-like street lighting; deliberately not named Night mode.",
    );

    val temperatureLabel: String
        get() = kelvin?.let { "$it K" } ?: "Adaptive"
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
    val hdrCaptureMode: HdrCaptureMode = HdrCaptureMode.OFF,
    val hdrToneMap: HdrToneMap = HdrToneMap.NATURAL,
    val doubleExposureMode: DoubleExposureMode = DoubleExposureMode.OFF,
    val ultraHdrExport: Boolean = false,
    /** Standard capture saves one DNG; True RAW HDR saves each bracket DNG when enabled. */
    val saveRawSidecar: Boolean = false,
    val hardwareEv: Float = 0f,
    val manualMode: Boolean = false,
    val manualIso: Int = 400,
    val manualShutterNs: Long = 8_000_000L,
    val whiteBalancePreset: WhiteBalancePreset = WhiteBalancePreset.AUTO,
    val focusMode: FocusMode = FocusMode.CONTINUOUS,
    /** Normalized lens position: 0 = infinity, 1 = nearest supported focus. */
    val manualFocusPosition: Float = 0.15f,
    val intensity: Float = 1f,
    val zebraEnabled: Boolean = false,
) {
    val requestedCaptureLabel: String
        get() = when {
            doubleExposureMode == DoubleExposureMode.FILM_BALANCED -> "Double Exposure"
            else -> hdrCaptureMode.label
        }
}

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
    val hdrBracketSupported: Boolean = false,
    /** RAW_SENSOR + MANUAL_SENSOR + Bayer metadata sufficient for an in-app RAW merge. */
    val trueRawHdrSupported: Boolean = false,
    val supportedWhiteBalancePresets: Set<WhiteBalancePreset> = setOf(WhiteBalancePreset.AUTO),
    val directKelvinWhiteBalancePresets: Set<WhiteBalancePreset> = emptySet(),
    val continuousFocusSupported: Boolean = false,
    val tapFocusSupported: Boolean = false,
    val macroFocusSupported: Boolean = false,
    val manualFocusSupported: Boolean = false,
    val infinityFocusSupported: Boolean = false,
    val minimumFocusDistanceDiopters: Float = 0f,
    val focusDistanceCalibration: FocusDistanceCalibration = FocusDistanceCalibration.UNCALIBRATED,
) {
    val minStops: Float get() = exposureRange.first * safeStep
    val maxStops: Float get() = exposureRange.last * safeStep
    private val safeStep: Float get() = if (exposureStep > 0f) exposureStep else 1f / 3f

    fun stopsToIndex(stops: Float): Int =
        Math.round(stops / safeStep).coerceIn(exposureRange.first, exposureRange.last)

    val exposureSupported: Boolean get() = exposureRange.first != exposureRange.last

    fun supportsWhiteBalancePreset(preset: WhiteBalancePreset): Boolean =
        preset in supportedWhiteBalancePresets

    fun usesDirectKelvinWhiteBalance(preset: WhiteBalancePreset): Boolean =
        preset in directKelvinWhiteBalancePresets

    fun supportsFocusMode(mode: FocusMode): Boolean = when (mode) {
        FocusMode.CONTINUOUS -> continuousFocusSupported
        FocusMode.TAP_LOCK -> tapFocusSupported
        FocusMode.MACRO -> macroFocusSupported
        FocusMode.MANUAL -> manualFocusSupported
        FocusMode.INFINITY -> infinityFocusSupported
        FocusMode.FIXED -> !canFocus
    }

    fun supportedOrFallback(requested: FocusMode): FocusMode {
        if (supportsFocusMode(requested)) return requested
        return listOf(
            FocusMode.CONTINUOUS,
            FocusMode.TAP_LOCK,
            FocusMode.MACRO,
            FocusMode.MANUAL,
            FocusMode.INFINITY,
            FocusMode.FIXED,
        ).firstOrNull(::supportsFocusMode) ?: FocusMode.FIXED
    }
}

data class GalleryItem(
    val uri: Uri,
    val displayName: String,
    val dateTakenMillis: Long,
    val presetLabel: String,
    val sensorModeLabel: String,
    val isOriginal: Boolean,
    val isUltraHdr: Boolean = false,
    val captureModeLabel: String = "Standard",
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

/**
 * Local, per-photo state for the Import Preview screen: the user picks a
 * photo, dials in settings just for it, and previews the result before
 * saving - Save uses THESE settings, not necessarily the live camera's
 * current settings.
 */
data class ImportPreviewState(
    val uri: Uri,
    val original: Bitmap,
    val settings: CameraSettings,
    val preview: Bitmap?,
    val isRendering: Boolean = false,
    val isSaving: Boolean = false,
)

data class CaptureResult(
    val processedUri: Uri,
    val originalUri: Uri?,
    val originalUris: List<Uri> = originalUri?.let(::listOf) ?: emptyList(),
    val rawUri: Uri? = null,
    val rawUris: List<Uri> = emptyList(),
    val displayName: String,
    val ultraHdr: Boolean = false,
    val captureModeLabel: String = "Standard",
    val captureDetail: String = "1 frame",
    val frameCount: Int = 1,
    val motionProtected: Boolean = false,
) {
    val summary: String
        get() = buildString {
            append(captureModeLabel)
            if (motionProtected && !captureModeLabel.contains("motion protected", ignoreCase = true)) {
                append(" • motion protected")
            }
            append(" • ")
            append(captureDetail)
        }
}

sealed interface CaptureActionResult {
    data class Saved(val result: CaptureResult) : CaptureActionResult
    data class AwaitingSecondExposure(val message: String) : CaptureActionResult
}

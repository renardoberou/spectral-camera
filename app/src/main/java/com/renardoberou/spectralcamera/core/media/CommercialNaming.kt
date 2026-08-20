package com.renardoberou.spectralcamera.core.media

import com.renardoberou.spectralcamera.core.LookFamily
import com.renardoberou.spectralcamera.core.OutputMode
import com.renardoberou.spectralcamera.core.SensorMode
import com.renardoberou.spectralcamera.core.SpectralPreset

/**
 * Photographer-facing names for shipped UI and export metadata.
 *
 * Enum names remain stable storage/parser identifiers; this is the only layer
 * that should turn a preset into customer-visible copy outside research docs.
 */
object CommercialNaming {
    private val metadataIds = mapOf(
        SpectralPreset.B_W_INFRARED to "infrared_mono",
        SpectralPreset.HIGH_CONTRAST_IR to "deep_infrared",
        SpectralPreset.WHITE_FOLIAGE_DARK_SKY to "soft_infrared",
        SpectralPreset.MONO_IR_MODERATE to "balanced_infrared",
        SpectralPreset.MONO_IR_FINE_GRAIN to "fine_infrared",
        SpectralPreset.MONO_IR_SOFT_VINTAGE to "vintage_infrared",
        SpectralPreset.AEROCHROME_FALSE_COLOR to "false_colour_classic",
        SpectralPreset.AEROCHROME_SOFT to "false_colour_soft",
        SpectralPreset.AEROCHROME_DENSE to "false_colour_dense",
        SpectralPreset.AEROCHROME_GOLD to "false_colour_gold",
        SpectralPreset.AEROCHROME_FADED to "false_colour_faded",
        SpectralPreset.AEROCHROME_VIVID to "false_colour_vivid",
        SpectralPreset.EKTAR_100 to "vivid_colour",
        SpectralPreset.CINESTILL_800T to "tungsten_halation",
        SpectralPreset.TRI_X_400 to "documentary_mono",
        SpectralPreset.PORTRA_400 to "natural_portrait",
        SpectralPreset.ARCHIVE_CHROME to "archive_colour",
        SpectralPreset.CINEMATIC_NEUTRAL to "cinematic_neutral",
        SpectralPreset.WARM_NEGATIVE to "warm_negative",
    )

    fun presetLabel(preset: SpectralPreset): String = preset.label

    fun familyLabel(family: LookFamily): String = when (family) {
        LookFamily.MONOCHROME_IR -> "Infrared"
        LookFamily.AEROCHROME -> "False Colour"
        LookFamily.STANDARD_FILM -> "Classic Film"
    }

    fun metadataProfile(
        preset: SpectralPreset,
        sensorLabel: String,
        outputLabel: String,
    ): String = "profile_id=${metadataIds.getValue(preset)} • $sensorLabel • ${presetLabel(preset)} • $outputLabel"

    fun exportLabel(preset: SpectralPreset): String = presetLabel(preset)

    fun exportLabel(sensorMode: SensorMode, preset: SpectralPreset, outputMode: OutputMode): String =
        "${presetLabel(preset)} • ${sensorMode.label} • ${outputMode.label}"
}

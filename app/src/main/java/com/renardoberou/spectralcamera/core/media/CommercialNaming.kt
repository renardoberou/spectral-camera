package com.renardoberou.spectralcamera.core.media

import com.renardoberou.spectralcamera.core.LookFamily
import com.renardoberou.spectralcamera.core.OutputMode
import com.renardoberou.spectralcamera.core.SensorMode
import com.renardoberou.spectralcamera.core.SpectralPreset
import java.util.Locale

/**
 * Photographer-facing names for shipped UI and export metadata.
 *
 * Enum names remain stable storage/parser identifiers; this is the only layer
 * that should turn a preset into customer-visible copy outside research docs.
 */
object CommercialNaming {
    private val labels = mapOf(
        SpectralPreset.B_W_INFRARED to "Infrared Mono",
        SpectralPreset.HIGH_CONTRAST_IR to "Deep Infrared",
        SpectralPreset.WHITE_FOLIAGE_DARK_SKY to "Soft Infrared",
        SpectralPreset.MONO_IR_MODERATE to "Balanced Infrared",
        SpectralPreset.MONO_IR_FINE_GRAIN to "Fine Infrared",
        SpectralPreset.MONO_IR_SOFT_VINTAGE to "Vintage Infrared",
        SpectralPreset.AEROCHROME_FALSE_COLOR to "False Colour Classic",
        SpectralPreset.AEROCHROME_SOFT to "False Colour Soft",
        SpectralPreset.AEROCHROME_DENSE to "False Colour Dense",
        SpectralPreset.AEROCHROME_GOLD to "False Colour Gold",
        SpectralPreset.AEROCHROME_FADED to "False Colour Faded",
        SpectralPreset.AEROCHROME_VIVID to "False Colour Vivid",
        SpectralPreset.EKTAR_100 to "Vivid Colour",
        SpectralPreset.CINESTILL_800T to "Tungsten Halation",
        SpectralPreset.TRI_X_400 to "Documentary Mono",
        SpectralPreset.PORTRA_400 to "Natural Portrait",
        SpectralPreset.ARCHIVE_CHROME to "Archive Colour",
        SpectralPreset.CINEMATIC_NEUTRAL to "Cinematic Neutral",
        SpectralPreset.WARM_NEGATIVE to "Warm Negative",
    )
    private val metadataIds = labels.mapValues { (_, label) ->
        label.lowercase(Locale.US).replace("[^a-z0-9]+".toRegex(), "_").trim('_')
    }

    fun presetLabel(preset: SpectralPreset): String = labels.getValue(preset)

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

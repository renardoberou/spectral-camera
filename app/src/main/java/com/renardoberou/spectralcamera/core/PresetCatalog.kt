package com.renardoberou.spectralcamera.core

data class PresetMetadata(
    val label: String,
    val description: String,
)

/** Stable UI-facing families, ordering, and copy for the preset picker. */
object PresetCatalog {
    val families: List<LookFamily> = listOf(
        LookFamily.STANDARD_FILM,
        LookFamily.AEROCHROME,
        LookFamily.MONOCHROME_IR,
    )

    private val orderedPresets: Map<LookFamily, List<SpectralPreset>> = mapOf(
        LookFamily.STANDARD_FILM to listOf(
            SpectralPreset.EKTAR_100,
            SpectralPreset.CINESTILL_800T,
            SpectralPreset.TRI_X_400,
            SpectralPreset.PORTRA_400,
            SpectralPreset.ARCHIVE_CHROME,
            SpectralPreset.CINEMATIC_NEUTRAL,
            SpectralPreset.WARM_NEGATIVE,
        ),
        LookFamily.AEROCHROME to listOf(
            SpectralPreset.AEROCHROME_FALSE_COLOR,
            SpectralPreset.AEROCHROME_SOFT,
            SpectralPreset.AEROCHROME_DENSE,
            SpectralPreset.AEROCHROME_GOLD,
            SpectralPreset.AEROCHROME_FADED,
            SpectralPreset.AEROCHROME_VIVID,
        ),
        LookFamily.MONOCHROME_IR to listOf(
            SpectralPreset.B_W_INFRARED,
            SpectralPreset.HIGH_CONTRAST_IR,
            SpectralPreset.WHITE_FOLIAGE_DARK_SKY,
            SpectralPreset.MONO_IR_MODERATE,
            SpectralPreset.MONO_IR_FINE_GRAIN,
            SpectralPreset.MONO_IR_SOFT_VINTAGE,
        ),
    )

    private val metadata: Map<SpectralPreset, PresetMetadata> =
        SpectralPreset.entries.associateWith { PresetMetadata(it.label, it.description) }

    fun presetsFor(family: LookFamily): List<SpectralPreset> = orderedPresets.getValue(family)

    fun metadataFor(preset: SpectralPreset): PresetMetadata = metadata.getValue(preset)
}

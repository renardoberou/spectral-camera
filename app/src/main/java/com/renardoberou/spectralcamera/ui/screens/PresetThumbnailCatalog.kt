package com.renardoberou.spectralcamera.ui.screens

import com.renardoberou.spectralcamera.R
import com.renardoberou.spectralcamera.core.SpectralPreset

data class PresetThumbnail(
    val resourceId: Int,
    val resourceName: String,
)

/** Resource-backed, bounded thumbnails for the preset picker. */
internal object PresetThumbnailCatalog {
    const val thumbnailPixelSize = 256
    const val fallbackResourceName = "preset_thumbnail_fallback"
    val fallbackResourceId: Int = R.drawable.preset_thumbnail_fallback

    private val thumbnailsByPresetId: Map<String, PresetThumbnail> = mapOf(
        "B_W_INFRARED" to PresetThumbnail(R.drawable.b_w_infrared_thumbnail, "b_w_infrared_thumbnail"),
        "HIGH_CONTRAST_IR" to PresetThumbnail(R.drawable.high_contrast_ir_thumbnail, "high_contrast_ir_thumbnail"),
        "WHITE_FOLIAGE_DARK_SKY" to PresetThumbnail(R.drawable.white_foliage_dark_sky_thumbnail, "white_foliage_dark_sky_thumbnail"),
        "MONO_IR_MODERATE" to PresetThumbnail(R.drawable.mono_ir_moderate_thumbnail, "mono_ir_moderate_thumbnail"),
        "MONO_IR_FINE_GRAIN" to PresetThumbnail(R.drawable.mono_ir_fine_grain_thumbnail, "mono_ir_fine_grain_thumbnail"),
        "MONO_IR_SOFT_VINTAGE" to PresetThumbnail(R.drawable.mono_ir_soft_vintage_thumbnail, "mono_ir_soft_vintage_thumbnail"),
        "AEROCHROME_FALSE_COLOR" to PresetThumbnail(R.drawable.aerochrome_false_color_thumbnail, "aerochrome_false_color_thumbnail"),
        "AEROCHROME_SOFT" to PresetThumbnail(R.drawable.aerochrome_soft_thumbnail, "aerochrome_soft_thumbnail"),
        "AEROCHROME_DENSE" to PresetThumbnail(R.drawable.aerochrome_dense_thumbnail, "aerochrome_dense_thumbnail"),
        "AEROCHROME_GOLD" to PresetThumbnail(R.drawable.aerochrome_gold_thumbnail, "aerochrome_gold_thumbnail"),
        "AEROCHROME_FADED" to PresetThumbnail(R.drawable.aerochrome_faded_thumbnail, "aerochrome_faded_thumbnail"),
        "AEROCHROME_VIVID" to PresetThumbnail(R.drawable.aerochrome_vivid_thumbnail, "aerochrome_vivid_thumbnail"),
        "EKTAR_100" to PresetThumbnail(R.drawable.ektar_100_thumbnail, "ektar_100_thumbnail"),
        "CINESTILL_800T" to PresetThumbnail(R.drawable.cinestill_800t_thumbnail, "cinestill_800t_thumbnail"),
        "TRI_X_400" to PresetThumbnail(R.drawable.tri_x_400_thumbnail, "tri_x_400_thumbnail"),
        "PORTRA_400" to PresetThumbnail(R.drawable.portra_400_thumbnail, "portra_400_thumbnail"),
        "ARCHIVE_CHROME" to PresetThumbnail(R.drawable.archive_chrome_thumbnail, "archive_chrome_thumbnail"),
        "CINEMATIC_NEUTRAL" to PresetThumbnail(R.drawable.cinematic_neutral_thumbnail, "cinematic_neutral_thumbnail"),
        "WARM_NEGATIVE" to PresetThumbnail(R.drawable.warm_negative_thumbnail, "warm_negative_thumbnail"),
    )

    val allResourceNames: List<String> = thumbnailsByPresetId.values.map { it.resourceName }

    fun thumbnailFor(preset: SpectralPreset): PresetThumbnail =
        thumbnailsByPresetId[preset.name] ?: PresetThumbnail(fallbackResourceId, fallbackResourceName)
}

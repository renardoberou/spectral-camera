package com.renardoberou.spectralcamera.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.renardoberou.spectralcamera.core.CameraSettings
import com.renardoberou.spectralcamera.core.ChannelSwapMode
import com.renardoberou.spectralcamera.core.DoubleExposureMode
import com.renardoberou.spectralcamera.core.FocusMode
import com.renardoberou.spectralcamera.core.HdrCaptureMode
import com.renardoberou.spectralcamera.core.HdrToneMap
import com.renardoberou.spectralcamera.core.ManualAdjustments
import com.renardoberou.spectralcamera.core.OutputMode
import com.renardoberou.spectralcamera.core.SensorMode
import com.renardoberou.spectralcamera.core.SpectralPreset
import com.renardoberou.spectralcamera.core.WhiteBalancePreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.spectralDataStore by preferencesDataStore(name = "spectral_camera_settings")

class CameraSettingsRepository(context: Context) {
    private val dataStore = context.spectralDataStore

    val settings: Flow<CameraSettings> = dataStore.data.map { prefs ->
        CameraSettings(
            preset = prefs[PRESET]?.let { name -> runCatching { SpectralPreset.valueOf(name) }.getOrNull() }
                ?: SpectralPreset.B_W_INFRARED,
            adjustments = ManualAdjustments(
                contrast = prefs[CONTRAST] ?: 1.0f,
                exposureCompensation = prefs[EXPOSURE] ?: 0f,
                blacks = prefs[BLACKS] ?: 0f,
                whites = prefs[WHITES] ?: 0f,
                bloom = prefs[BLOOM] ?: 0f,
                grain = prefs[GRAIN] ?: 0f,
                sharpness = prefs[SHARPNESS] ?: 0f,
                redChannelWeight = prefs[RED_WEIGHT] ?: 1.0f,
                greenFoliageLift = prefs[GREEN_LIFT] ?: 0f,
                blueSkySuppression = prefs[BLUE_SUPPRESS] ?: 0f,
                hueRotation = prefs[HUE_ROTATION] ?: 0f,
                saturation = prefs[SATURATION] ?: 1.0f,
                channelSwapMode = runCatching {
                    ChannelSwapMode.valueOf(prefs[SWAP_MODE] ?: ChannelSwapMode.NONE.name)
                }.getOrDefault(ChannelSwapMode.NONE),
            ),
            saveOriginal = prefs[SAVE_ORIGINAL] ?: false,
            frontFacing = prefs[FRONT_FACING] ?: false,
            sensorMode = runCatching {
                SensorMode.valueOf(prefs[SENSOR_MODE] ?: SensorMode.SIMULATED_IR.name)
            }.getOrDefault(SensorMode.SIMULATED_IR),
            outputMode = prefs[OUTPUT_MODE]?.let { name ->
                runCatching { OutputMode.valueOf(name) }.getOrNull()
            } ?: OutputMode.FULL_RESOLUTION,
            hdrCaptureMode = prefs[HDR_CAPTURE_MODE]?.let { name ->
                runCatching { HdrCaptureMode.valueOf(name) }.getOrNull()
            } ?: HdrCaptureMode.OFF,
            hdrToneMap = prefs[HDR_TONE_MAP]?.let { name ->
                runCatching { HdrToneMap.valueOf(name) }.getOrNull()
            } ?: HdrToneMap.NATURAL,
            doubleExposureMode = prefs[DOUBLE_EXPOSURE_MODE]?.let { name ->
                runCatching { DoubleExposureMode.valueOf(name) }.getOrNull()
            } ?: DoubleExposureMode.OFF,
            ultraHdrExport = prefs[ULTRA_HDR_EXPORT] ?: false,
            saveRawSidecar = prefs[SAVE_RAW_SIDECAR] ?: false,
            hardwareEv = (prefs[HARDWARE_EV] ?: 0f).let { stored ->
                if (stored > 2.01f || stored < -2.01f) 0f else stored.coerceIn(-2f, 2f)
            },
            manualMode = false,
            manualIso = prefs[MANUAL_ISO] ?: 400,
            manualShutterNs = prefs[MANUAL_SHUTTER_NS] ?: 8_000_000L,
            whiteBalancePreset = prefs[WHITE_BALANCE_PRESET]?.let { name ->
                runCatching { WhiteBalancePreset.valueOf(name) }.getOrNull()
            } ?: WhiteBalancePreset.AUTO,
            focusMode = prefs[FOCUS_MODE]?.let { name ->
                runCatching { FocusMode.valueOf(name) }.getOrNull()
            } ?: FocusMode.CONTINUOUS,
            manualFocusPosition = (prefs[MANUAL_FOCUS_POSITION] ?: 0.15f).coerceIn(0f, 1f),
            intensity = (prefs[INTENSITY] ?: 1f).coerceIn(0.25f, 1f),
            zebraEnabled = prefs[ZEBRA] ?: false,
        )
    }

    suspend fun save(settings: CameraSettings) {
        dataStore.edit { prefs ->
            prefs[PRESET] = settings.preset.name
            prefs[CONTRAST] = settings.adjustments.contrast
            prefs[EXPOSURE] = settings.adjustments.exposureCompensation
            prefs[BLACKS] = settings.adjustments.blacks
            prefs[WHITES] = settings.adjustments.whites
            prefs[BLOOM] = settings.adjustments.bloom
            prefs[GRAIN] = settings.adjustments.grain
            prefs[SHARPNESS] = settings.adjustments.sharpness
            prefs[RED_WEIGHT] = settings.adjustments.redChannelWeight
            prefs[GREEN_LIFT] = settings.adjustments.greenFoliageLift
            prefs[BLUE_SUPPRESS] = settings.adjustments.blueSkySuppression
            prefs[HUE_ROTATION] = settings.adjustments.hueRotation
            prefs[SATURATION] = settings.adjustments.saturation
            prefs[SWAP_MODE] = settings.adjustments.channelSwapMode.name
            prefs[SAVE_ORIGINAL] = settings.saveOriginal
            prefs[FRONT_FACING] = settings.frontFacing
            prefs[SENSOR_MODE] = settings.sensorMode.name
            prefs[OUTPUT_MODE] = settings.outputMode.name
            prefs[HDR_CAPTURE_MODE] = settings.hdrCaptureMode.name
            prefs[HDR_TONE_MAP] = settings.hdrToneMap.name
            prefs[DOUBLE_EXPOSURE_MODE] = settings.doubleExposureMode.name
            prefs[ULTRA_HDR_EXPORT] = settings.ultraHdrExport
            prefs[SAVE_RAW_SIDECAR] = settings.saveRawSidecar
            prefs[HARDWARE_EV] = settings.hardwareEv
            prefs[MANUAL_ISO] = settings.manualIso
            prefs[MANUAL_SHUTTER_NS] = settings.manualShutterNs
            prefs[WHITE_BALANCE_PRESET] = settings.whiteBalancePreset.name
            prefs[FOCUS_MODE] = settings.focusMode.name
            prefs[MANUAL_FOCUS_POSITION] = settings.manualFocusPosition.coerceIn(0f, 1f)
            prefs[INTENSITY] = settings.intensity
            prefs[ZEBRA] = settings.zebraEnabled
        }
    }

    private companion object {
        val PRESET = stringPreferencesKey("preset")
        val CONTRAST = floatPreferencesKey("contrast")
        val EXPOSURE = floatPreferencesKey("exposure")
        val BLACKS = floatPreferencesKey("blacks")
        val WHITES = floatPreferencesKey("whites")
        val BLOOM = floatPreferencesKey("bloom")
        val GRAIN = floatPreferencesKey("grain")
        val SHARPNESS = floatPreferencesKey("sharpness")
        val RED_WEIGHT = floatPreferencesKey("red_weight")
        val GREEN_LIFT = floatPreferencesKey("green_lift")
        val BLUE_SUPPRESS = floatPreferencesKey("blue_suppress")
        val HUE_ROTATION = floatPreferencesKey("hue_rotation")
        val SATURATION = floatPreferencesKey("saturation")
        val SWAP_MODE = stringPreferencesKey("swap_mode")
        val SAVE_ORIGINAL = booleanPreferencesKey("save_original")
        val FRONT_FACING = booleanPreferencesKey("front_facing")
        val SENSOR_MODE = stringPreferencesKey("sensor_mode")
        val OUTPUT_MODE = stringPreferencesKey("output_mode")
        val HDR_CAPTURE_MODE = stringPreferencesKey("hdr_capture_mode")
        val HDR_TONE_MAP = stringPreferencesKey("hdr_tone_map")
        val DOUBLE_EXPOSURE_MODE = stringPreferencesKey("double_exposure_mode")
        val ULTRA_HDR_EXPORT = booleanPreferencesKey("ultra_hdr_export")
        val SAVE_RAW_SIDECAR = booleanPreferencesKey("save_raw_sidecar")
        val HARDWARE_EV = floatPreferencesKey("hardware_ev")
        val MANUAL_ISO = intPreferencesKey("manual_iso")
        val MANUAL_SHUTTER_NS = longPreferencesKey("manual_shutter_ns")
        val WHITE_BALANCE_PRESET = stringPreferencesKey("white_balance_preset")
        val FOCUS_MODE = stringPreferencesKey("focus_mode")
        val MANUAL_FOCUS_POSITION = floatPreferencesKey("manual_focus_position")
        val INTENSITY = floatPreferencesKey("look_intensity")
        val ZEBRA = booleanPreferencesKey("zebra_enabled")
    }
}

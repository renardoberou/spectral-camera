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
import com.renardoberou.spectralcamera.core.ManualAdjustments
import com.renardoberou.spectralcamera.core.SensorMode
import com.renardoberou.spectralcamera.core.SpectralPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.spectralDataStore by preferencesDataStore(name = "spectral_camera_settings")

class CameraSettingsRepository(context: Context) {
    private val dataStore = context.spectralDataStore

    val settings: Flow<CameraSettings> = dataStore.data.map { prefs ->
        CameraSettings(
            preset = SpectralPreset.valueOf(prefs[PRESET] ?: SpectralPreset.B_W_INFRARED.name),
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
                channelSwapMode = ChannelSwapMode.valueOf(prefs[SWAP_MODE] ?: ChannelSwapMode.NONE.name),
            ),
            saveOriginal = prefs[SAVE_ORIGINAL] ?: false,
            frontFacing = prefs[FRONT_FACING] ?: false,
            sensorMode = SensorMode.valueOf(prefs[SENSOR_MODE] ?: SensorMode.SIMULATED_IR.name),
            // Migration guard: hardwareEv was stored as a raw camera index before
            // v1.6.0 and is stops now. A magnitude beyond 2 is clearly a legacy
            // index and would pin the camera at max EV (chronic overexposure) -
            // reset it; otherwise clamp to the photographic range.
            hardwareEv = (prefs[HARDWARE_EV] ?: 0f).let { stored ->
                if (stored > 2.01f || stored < -2.01f) 0f else stored.coerceIn(-2f, 2f)
            },
            manualMode = false,
            manualIso = prefs[MANUAL_ISO] ?: 400,
            manualShutterNs = prefs[MANUAL_SHUTTER_NS] ?: 8_000_000L,
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
            prefs[HARDWARE_EV] = settings.hardwareEv
            prefs[MANUAL_ISO] = settings.manualIso
            prefs[MANUAL_SHUTTER_NS] = settings.manualShutterNs
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
        val HARDWARE_EV = floatPreferencesKey("hardware_ev")
        val MANUAL_MODE = booleanPreferencesKey("manual_mode")
        val MANUAL_ISO = intPreferencesKey("manual_iso")
        val MANUAL_SHUTTER_NS = longPreferencesKey("manual_shutter_ns")
        val INTENSITY = floatPreferencesKey("look_intensity")
        val ZEBRA = booleanPreferencesKey("zebra_enabled")
    }
}

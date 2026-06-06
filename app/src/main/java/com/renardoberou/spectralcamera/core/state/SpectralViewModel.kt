package com.renardoberou.spectralcamera.core.state

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.renardoberou.spectralcamera.core.CameraSettings
import com.renardoberou.spectralcamera.core.CaptureResult
import com.renardoberou.spectralcamera.core.GalleryItem
import com.renardoberou.spectralcamera.core.HardwareTestState
import com.renardoberou.spectralcamera.core.SpectralPreset
import com.renardoberou.spectralcamera.core.data.CameraSettingsRepository
import com.renardoberou.spectralcamera.core.filters.SpectralFilterEngine
import com.renardoberou.spectralcamera.core.hardware.HardwareTestAnalyzer
import com.renardoberou.spectralcamera.core.media.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SpectralViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepository = CameraSettingsRepository(application)
    private val mediaRepository = MediaRepository(application)
    private val filterEngine = SpectralFilterEngine()
    private val hardwareAnalyzer = HardwareTestAnalyzer()

    private var lastHardwareFrameAt = 0L

    val settings: StateFlow<CameraSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = CameraSettings(),
    )

    private val _galleryItems = MutableStateFlow<List<GalleryItem>>(emptyList())
    val galleryItems = _galleryItems.asStateFlow()

    private val _hardwareState = MutableStateFlow(HardwareTestState.idle())
    val hardwareState = _hardwareState.asStateFlow()

    private val _livePreviewFrame = MutableStateFlow<Bitmap?>(null)
    val livePreviewFrame = _livePreviewFrame.asStateFlow()

    init {
        refreshGallery()
    }

    fun onPreviewFrame(bitmap: Bitmap) {
        _livePreviewFrame.value = bitmap
        val now = SystemClock.elapsedRealtime()
        if (now - lastHardwareFrameAt < 120L) return
        lastHardwareFrameAt = now
        _hardwareState.value = hardwareAnalyzer.analyze(bitmap)
    }

    fun resetHardwareTest() {
        _hardwareState.value = hardwareAnalyzer.reset()
        lastHardwareFrameAt = 0L
    }

    fun updateSettings(transform: (CameraSettings) -> CameraSettings) {
        val updated = transform(settings.value)
        viewModelScope.launch { settingsRepository.save(updated) }
    }

    fun setPreset(preset: SpectralPreset) = updateSettings { it.copy(preset = preset) }

    fun setSaveOriginal(enabled: Boolean) = updateSettings { it.copy(saveOriginal = enabled) }

    fun setFrontFacing(enabled: Boolean) = updateSettings { it.copy(frontFacing = enabled) }

    fun setSensorMode(mode: com.renardoberou.spectralcamera.core.SensorMode) = updateSettings { it.copy(sensorMode = mode) }

    fun updateAdjustments(transform: (com.renardoberou.spectralcamera.core.ManualAdjustments) -> com.renardoberou.spectralcamera.core.ManualAdjustments) {
        updateSettings { settings -> settings.copy(adjustments = transform(settings.adjustments)) }
    }

    suspend fun captureAndSave(cameraController: com.renardoberou.spectralcamera.core.camera.CameraController): CaptureResult {
        val raw = cameraController.capture()
        val currentSettings = settings.value
        val processed = withContext(Dispatchers.Default) {
            filterEngine.processCapture(raw, currentSettings)
        }
        val result = withContext(Dispatchers.IO) {
            mediaRepository.saveCapture(processed, raw, currentSettings)
        }
        refreshGallery()
        return result
    }

    fun refreshGallery() {
        viewModelScope.launch(Dispatchers.IO) {
            _galleryItems.value = mediaRepository.loadGallery()
        }
    }

    fun processHardwareFrame(bitmap: Bitmap) {
        _hardwareState.value = hardwareAnalyzer.analyze(bitmap)
    }
}

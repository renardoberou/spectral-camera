package com.renardoberou.spectralcamera.core.state

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.renardoberou.spectralcamera.core.CameraSettings
import com.renardoberou.spectralcamera.core.CaptureResult
import com.renardoberou.spectralcamera.core.GalleryItem
import com.renardoberou.spectralcamera.core.HardwareTestState
import com.renardoberou.spectralcamera.core.SpectralPreset
import com.renardoberou.spectralcamera.core.camera.CameraController
import com.renardoberou.spectralcamera.core.data.CameraSettingsRepository
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

data class GalleryUiState(
    val items: List<GalleryItem> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

class SpectralViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepository = CameraSettingsRepository(application)
    private val mediaRepository = MediaRepository(application)
    private val hardwareAnalyzer = HardwareTestAnalyzer()

    val settings: StateFlow<CameraSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = CameraSettings(),
    )

    private val _galleryState = MutableStateFlow(GalleryUiState())
    val galleryState = _galleryState.asStateFlow()

    private val _hardwareState = MutableStateFlow(HardwareTestState.idle())
    val hardwareState = _hardwareState.asStateFlow()

    init {
        refreshGallery()
    }

    /** Receives small RGBA frames from the analysis stream (hardware-test screen only). */
    fun onAnalysisFrame(bitmap: Bitmap) {
        _hardwareState.value = hardwareAnalyzer.analyze(bitmap)
    }

    fun resetHardwareTest() {
        _hardwareState.value = hardwareAnalyzer.reset()
    }

    fun updateSettings(transform: (CameraSettings) -> CameraSettings) {
        val updated = transform(settings.value)
        viewModelScope.launch { settingsRepository.save(updated) }
    }

    fun setPreset(preset: SpectralPreset) = updateSettings { it.copy(preset = preset) }
    fun setSaveOriginal(enabled: Boolean) = updateSettings { it.copy(saveOriginal = enabled) }
    fun setFrontFacing(enabled: Boolean) = updateSettings { it.copy(frontFacing = enabled) }
    fun setHardwareEv(value: Float) = updateSettings { it.copy(hardwareEv = value) }
    fun setManualMode(enabled: Boolean) = updateSettings { it.copy(manualMode = enabled) }
    fun setManualIso(iso: Int) = updateSettings { it.copy(manualIso = iso) }
    fun setManualShutter(nanos: Long) = updateSettings { it.copy(manualShutterNs = nanos) }
    fun setSensorMode(mode: com.renardoberou.spectralcamera.core.SensorMode) = updateSettings { it.copy(sensorMode = mode) }

    fun updateAdjustments(transform: (com.renardoberou.spectralcamera.core.ManualAdjustments) -> com.renardoberou.spectralcamera.core.ManualAdjustments) {
        updateSettings { settings -> settings.copy(adjustments = transform(settings.adjustments)) }
    }

    /**
     * Captures a still, runs it through the GPU filter chain supplied by the UI layer
     * (so the saved photo matches the live preview exactly), and writes it to MediaStore.
     */
    suspend fun captureAndSave(
        cameraController: CameraController,
        process: suspend (Bitmap, CameraSettings) -> Bitmap,
    ): CaptureResult {
        val currentSettings = settings.value
        val raw = cameraController.capture()
        val processed = process(raw, currentSettings)
        val result = withContext(Dispatchers.IO) {
            mediaRepository.saveCapture(processed, raw, currentSettings)
        }
        refreshGallery()
        return result
    }

    /**
     * Import an existing photo (Android photo picker Uri) through the SAME
     * process lambda the live capture uses, and save it exactly like a
     * capture (including the optional untouched original). Lets a
     * photographer run their whole back catalog through the film pipelines.
     */
    suspend fun importAndSave(
        uri: Uri,
        process: suspend (Bitmap, CameraSettings) -> Bitmap,
    ): CaptureResult {
        val currentSettings = settings.value
        val app = getApplication<android.app.Application>()
        val decoded = withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(app.contentResolver, uri)
                // Software allocation: the GL upload path needs CPU-accessible
                // pixels; ImageDecoder also applies EXIF orientation for us.
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(app.contentResolver, uri)
            }
        }
        val raw = if (decoded.config != Bitmap.Config.ARGB_8888) {
            decoded.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            decoded
        }
        val processed = process(raw, currentSettings)
        val result = withContext(Dispatchers.IO) {
            mediaRepository.saveCapture(processed, raw, currentSettings)
        }
        refreshGallery()
        return result
    }

    fun refreshGallery() {
        val currentItems = _galleryState.value.items
        _galleryState.value = GalleryUiState(items = currentItems, isLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            _galleryState.value = runCatching { mediaRepository.loadGallery() }
                .fold(
                    onSuccess = { GalleryUiState(items = it, isLoading = false) },
                    onFailure = {
                        GalleryUiState(
                            items = currentItems,
                            isLoading = false,
                            errorMessage = "Unable to read the photo library. Check photo access and try again.",
                        )
                    },
                )
        }
    }
}

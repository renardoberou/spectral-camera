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
import com.renardoberou.spectralcamera.core.OutputMode
import com.renardoberou.spectralcamera.core.SpectralPreset
import com.renardoberou.spectralcamera.core.camera.CameraController
import com.renardoberou.spectralcamera.core.data.CameraSettingsRepository
import com.renardoberou.spectralcamera.core.export.OutputPipeline
import com.renardoberou.spectralcamera.core.hardware.HardwareTestAnalyzer
import com.renardoberou.spectralcamera.core.media.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

    // Manual exposure is SESSION state: never persisted, and layered over the
    // repository flow here (forcing it false at read time fought every
    // DataStore re-emission, snapping the Manual switch off instantly).
    private val manualModeSession = MutableStateFlow(false)

    val settings: StateFlow<CameraSettings> = settingsRepository.settings
        .combine(manualModeSession) { persisted, manual -> persisted.copy(manualMode = manual) }.stateIn(
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
    fun setOutputMode(mode: OutputMode) = updateSettings { it.copy(outputMode = mode) }
    fun setSaveRawSidecar(enabled: Boolean) = updateSettings { it.copy(saveRawSidecar = enabled) }
    fun setHardwareEv(value: Float) = updateSettings { it.copy(hardwareEv = value) }
    fun setManualMode(enabled: Boolean) { manualModeSession.value = enabled }
    fun setManualIso(iso: Int) = updateSettings { it.copy(manualIso = iso) }
    fun setManualShutter(nanos: Long) = updateSettings { it.copy(manualShutterNs = nanos) }

    fun setIntensity(value: Float) = updateSettings { it.copy(intensity = value) }

    fun setZebra(enabled: Boolean) = updateSettings { it.copy(zebraEnabled = enabled) }
    fun setSensorMode(mode: com.renardoberou.spectralcamera.core.SensorMode) = updateSettings { it.copy(sensorMode = mode) }

    fun updateAdjustments(transform: (com.renardoberou.spectralcamera.core.ManualAdjustments) -> com.renardoberou.spectralcamera.core.ManualAdjustments) {
        updateSettings { settings -> settings.copy(adjustments = transform(settings.adjustments)) }
    }

    /**
     * Captures one shutter result and applies the selected output policy around
     * the shared GPU film renderer. HQ 1080 renders the high-resolution crop
     * before downsampling; Fast 1080 downscales before rendering. A temporary
     * DNG sidecar, when present, is copied by MediaRepository and then deleted.
     */
    suspend fun captureAndSave(
        cameraController: CameraController,
        process: suspend (Bitmap, CameraSettings) -> Bitmap,
    ): CaptureResult {
        val currentSettings = settings.value
        val frame = cameraController.capture()
        var renderInput: Bitmap? = null
        var rendered: Bitmap? = null
        var processed: Bitmap? = null
        try {
            renderInput = withContext(Dispatchers.Default) {
                OutputPipeline.prepareForRender(frame.bitmap, currentSettings.outputMode)
            }
            rendered = process(renderInput, currentSettings)
            processed = withContext(Dispatchers.Default) {
                OutputPipeline.finalizeExport(rendered, currentSettings.outputMode)
            }
            val result = withContext(Dispatchers.IO) {
                mediaRepository.saveCapture(
                    processed = processed,
                    original = frame.bitmap,
                    rawSidecar = frame.rawSidecarFile,
                    settings = currentSettings,
                )
            }
            refreshGallery()
            return result
        } finally {
            frame.rawSidecarFile?.delete()
            recycleDistinct(frame.bitmap, renderInput, rendered, processed)
        }
    }

    /**
     * Imports an existing photo through the same film and output pipeline used
     * by capture. Imported DNG development is not implemented in this cycle;
     * ImageDecoder still supplies a display-referred Bitmap.
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
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(app.contentResolver, uri)
            }
        }
        val original = if (decoded.config != Bitmap.Config.ARGB_8888) {
            decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
        } else {
            decoded
        }

        var renderInput: Bitmap? = null
        var rendered: Bitmap? = null
        var processed: Bitmap? = null
        try {
            renderInput = withContext(Dispatchers.Default) {
                OutputPipeline.prepareForRender(original, currentSettings.outputMode)
            }
            rendered = process(renderInput, currentSettings)
            processed = withContext(Dispatchers.Default) {
                OutputPipeline.finalizeExport(rendered, currentSettings.outputMode)
            }
            val result = withContext(Dispatchers.IO) {
                mediaRepository.saveCapture(
                    processed = processed,
                    original = original,
                    rawSidecar = null,
                    settings = currentSettings,
                )
            }
            refreshGallery()
            return result
        } finally {
            recycleDistinct(original, renderInput, rendered, processed)
        }
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

    private fun recycleDistinct(vararg bitmaps: Bitmap?) {
        val seen = mutableListOf<Bitmap>()
        bitmaps.forEach { bitmap ->
            if (bitmap != null && seen.none { it === bitmap }) {
                seen += bitmap
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }
}

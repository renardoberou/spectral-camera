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
import com.renardoberou.spectralcamera.core.HdrCaptureMode
import com.renardoberou.spectralcamera.core.HdrToneMap
import com.renardoberou.spectralcamera.core.OutputMode
import com.renardoberou.spectralcamera.core.SpectralPreset
import com.renardoberou.spectralcamera.core.camera.CameraController
import com.renardoberou.spectralcamera.core.camera.CapturedExposure
import com.renardoberou.spectralcamera.core.data.CameraSettingsRepository
import com.renardoberou.spectralcamera.core.export.OutputPipeline
import com.renardoberou.spectralcamera.core.hardware.HardwareTestAnalyzer
import com.renardoberou.spectralcamera.core.hdr.HdrMergeResult
import com.renardoberou.spectralcamera.core.hdr.HdrPipeline
import com.renardoberou.spectralcamera.core.hdr.UltraHdrExporter
import com.renardoberou.spectralcamera.core.hdr.UltraHdrImage
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

    fun setHdrCaptureMode(mode: HdrCaptureMode) = updateSettings { current ->
        when (mode) {
            HdrCaptureMode.OFF -> current.copy(hdrCaptureMode = mode, ultraHdrExport = false)
            HdrCaptureMode.THREE_FRAME -> current.copy(
                hdrCaptureMode = mode,
                // This cycle implements a JPEG exposure bracket. RAW remains a
                // single-shutter sidecar workflow rather than silently making
                // three large DNG files.
                saveRawSidecar = false,
            )
        }
    }

    fun setHdrToneMap(mode: HdrToneMap) = updateSettings { it.copy(hdrToneMap = mode) }

    fun setUltraHdrExport(enabled: Boolean) = updateSettings { current ->
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        current.copy(
            ultraHdrExport = enabled && supported && current.hdrCaptureMode == HdrCaptureMode.THREE_FRAME,
        )
    }

    fun setSaveRawSidecar(enabled: Boolean) = updateSettings { current ->
        if (enabled) {
            current.copy(
                saveRawSidecar = true,
                hdrCaptureMode = HdrCaptureMode.OFF,
                ultraHdrExport = false,
            )
        } else {
            current.copy(saveRawSidecar = false)
        }
    }

    fun setHardwareEv(value: Float) = updateSettings { it.copy(hardwareEv = value) }
    fun setManualMode(enabled: Boolean) { manualModeSession.value = enabled }
    fun setManualIso(iso: Int) = updateSettings { it.copy(manualIso = iso) }
    fun setManualShutter(nanos: Long) = updateSettings { it.copy(manualShutterNs = nanos) }
    fun setIntensity(value: Float) = updateSettings { it.copy(intensity = value) }
    fun setZebra(enabled: Boolean) = updateSettings { it.copy(zebraEnabled = enabled) }
    fun setSensorMode(mode: com.renardoberou.spectralcamera.core.SensorMode) =
        updateSettings { it.copy(sensorMode = mode) }

    fun updateAdjustments(transform: (com.renardoberou.spectralcamera.core.ManualAdjustments) -> com.renardoberou.spectralcamera.core.ManualAdjustments) {
        updateSettings { current -> current.copy(adjustments = transform(current.adjustments)) }
    }

    /**
     * Full still pipeline:
     *
     * camera bracket -> output-mode geometry -> scene-linear HDR merge -> global
     * normalization/tone map -> shared synthetic-NIR/film shader -> output-size
     * finishing -> optional processed-image Ultra HDR gain map -> MediaStore.
     */
    suspend fun captureAndSave(
        cameraController: CameraController,
        process: suspend (Bitmap, CameraSettings) -> Bitmap,
    ): CaptureResult {
        val requestedSettings = settings.value
        val frame = cameraController.capture(requestedSettings)
        val effectiveSettings = if (frame.isHdrBracket) {
            requestedSettings
        } else {
            // A camera with insufficient bracket range can fall back to one
            // frame; keep output metadata and auto-level behavior truthful.
            requestedSettings.copy(hdrCaptureMode = HdrCaptureMode.OFF, ultraHdrExport = false)
        }

        val prepared = mutableListOf<CapturedExposure>()
        var hdrMerge: HdrMergeResult? = null
        var filmInput: Bitmap? = null
        var rendered: Bitmap? = null
        var finalOutput: Bitmap? = null
        var ultraHdr: UltraHdrImage? = null
        try {
            frame.exposures.forEach { exposure ->
                val bitmap = withContext(Dispatchers.Default) {
                    OutputPipeline.prepareForRender(exposure.bitmap, effectiveSettings.outputMode)
                }
                prepared += CapturedExposure(bitmap, exposure.evOffset)
            }

            val working = if (frame.isHdrBracket) {
                withContext(Dispatchers.Default) {
                    HdrPipeline.merge(
                        frames = prepared,
                        referenceIndex = frame.referenceIndex,
                        toneMap = effectiveSettings.hdrToneMap,
                    )
                }.also { hdrMerge = it }.workingBitmap
            } else {
                prepared[frame.referenceIndex].bitmap
            }
            filmInput = working

            val filmRender = process(working, effectiveSettings)
            rendered = filmRender
            val finished = withContext(Dispatchers.Default) {
                OutputPipeline.finalizeExport(filmRender, effectiveSettings.outputMode)
            }
            finalOutput = finished

            if (effectiveSettings.ultraHdrExport && hdrMerge != null) {
                ultraHdr = withContext(Dispatchers.Default) {
                    UltraHdrExporter.attachIfSupported(finished, requireNotNull(hdrMerge).gainField)
                }
            }
            val saveBitmap = ultraHdr?.bitmap ?: finished
            val result = withContext(Dispatchers.IO) {
                mediaRepository.saveCapture(
                    processed = saveBitmap,
                    original = frame.referenceBitmap,
                    rawSidecar = frame.rawSidecarFile,
                    settings = effectiveSettings,
                    ultraHdr = ultraHdr != null,
                )
            }
            refreshGallery()
            return result
        } finally {
            frame.rawSidecarFile?.delete()
            ultraHdr?.recycle()
            val allBitmaps = buildList {
                addAll(frame.exposures.map { it.bitmap })
                addAll(prepared.map { it.bitmap })
                add(hdrMerge?.workingBitmap)
                add(filmInput)
                add(rendered)
                add(finalOutput)
            }
            recycleDistinct(*allBitmaps.toTypedArray())
        }
    }

    /**
     * Gallery import remains a single display-referred source. Computational
     * bracketing and Ultra HDR generation are capture-only until a DNG/multi-
     * exposure import workflow exists.
     */
    suspend fun importAndSave(
        uri: Uri,
        process: suspend (Bitmap, CameraSettings) -> Bitmap,
    ): CaptureResult {
        val effectiveSettings = settings.value.copy(
            hdrCaptureMode = HdrCaptureMode.OFF,
            ultraHdrExport = false,
        )
        val app = getApplication<Application>()
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
            val prepared = withContext(Dispatchers.Default) {
                OutputPipeline.prepareForRender(original, effectiveSettings.outputMode)
            }
            renderInput = prepared
            val filmRender = process(prepared, effectiveSettings)
            rendered = filmRender
            val finalOutput = withContext(Dispatchers.Default) {
                OutputPipeline.finalizeExport(filmRender, effectiveSettings.outputMode)
            }
            processed = finalOutput
            val result = withContext(Dispatchers.IO) {
                mediaRepository.saveCapture(
                    processed = finalOutput,
                    original = original,
                    rawSidecar = null,
                    settings = effectiveSettings,
                    ultraHdr = false,
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

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
import com.renardoberou.spectralcamera.core.hdr.HdrGainField
import com.renardoberou.spectralcamera.core.hdr.HdrMergeResult
import com.renardoberou.spectralcamera.core.hdr.HdrPipeline
import com.renardoberou.spectralcamera.core.hdr.RawHdrMergeResult
import com.renardoberou.spectralcamera.core.hdr.RawHdrPipeline
import com.renardoberou.spectralcamera.core.hdr.UltraHdrExporter
import com.renardoberou.spectralcamera.core.hdr.UltraHdrImage
import com.renardoberou.spectralcamera.core.media.MediaRepository
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
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
                saveRawSidecar = false,
            )
            HdrCaptureMode.RAW_THREE_FRAME -> current.copy(
                hdrCaptureMode = mode,
                saveOriginal = false,
                saveRawSidecar = true,
            )
        }
    }

    fun setHdrToneMap(mode: HdrToneMap) = updateSettings { it.copy(hdrToneMap = mode) }

    fun setUltraHdrExport(enabled: Boolean) = updateSettings { current ->
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        current.copy(
            ultraHdrExport = enabled && supported && current.hdrCaptureMode != HdrCaptureMode.OFF,
        )
    }

    fun setSaveRawSidecar(enabled: Boolean) = updateSettings { current ->
        when {
            current.hdrCaptureMode == HdrCaptureMode.RAW_THREE_FRAME ->
                current.copy(saveRawSidecar = enabled)
            enabled -> current.copy(
                saveRawSidecar = true,
                hdrCaptureMode = HdrCaptureMode.OFF,
                ultraHdrExport = false,
            )
            else -> current.copy(saveRawSidecar = false)
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
     * End-to-end still path. True RAW HDR branches before any Bitmap/JPEG source
     * preparation; its Bayer merge and demosaic produce the first RGB bitmap.
     */
    suspend fun captureAndSave(
        cameraController: CameraController,
        process: suspend (Bitmap, CameraSettings) -> Bitmap,
    ): CaptureResult {
        val requestedSettings = settings.value
        val frame = cameraController.capture(requestedSettings)
        var effectiveSettings = when {
            frame.isRawHdrBracket -> requestedSettings.copy(hdrCaptureMode = HdrCaptureMode.RAW_THREE_FRAME)
            frame.isHdrBracket -> requestedSettings.copy(hdrCaptureMode = HdrCaptureMode.THREE_FRAME)
            else -> requestedSettings.copy(hdrCaptureMode = HdrCaptureMode.OFF, ultraHdrExport = false)
        }

        val referenceOriginal = frame.referenceBitmap
        val preparedJpegs = mutableListOf<CapturedExposure>()
        var jpegMerge: HdrMergeResult? = null
        var rawMerge: RawHdrMergeResult? = null
        var gainField: HdrGainField? = null
        var filmInput: Bitmap? = null
        var rendered: Bitmap? = null
        var finalOutput: Bitmap? = null
        var ultraHdr: UltraHdrImage? = null
        val rawFiles = frame.rawFiles
        try {
            val working: Bitmap = when {
                frame.isRawHdrBracket -> {
                    val merge = withContext(Dispatchers.Default) {
                        RawHdrPipeline.merge(
                            frames = frame.rawExposures,
                            referenceIndex = frame.referenceIndex,
                            toneMap = effectiveSettings.hdrToneMap,
                            frontFacing = effectiveSettings.frontFacing,
                        )
                    }
                    rawMerge = merge
                    gainField = merge.gainField
                    val prepared = withContext(Dispatchers.Default) {
                        OutputPipeline.prepareForRender(merge.workingBitmap, effectiveSettings.outputMode)
                    }
                    if (prepared !== merge.workingBitmap && !merge.workingBitmap.isRecycled) {
                        merge.workingBitmap.recycle()
                    }
                    prepared
                }
                else -> {
                    frame.exposures.forEachIndexed { index, exposure ->
                        val bitmap = withContext(Dispatchers.Default) {
                            OutputPipeline.prepareForRender(exposure.bitmap, effectiveSettings.outputMode)
                        }
                        preparedJpegs += CapturedExposure(bitmap, exposure.evOffset)
                        val keepOriginal = effectiveSettings.saveOriginal && index == frame.referenceIndex
                        if (bitmap !== exposure.bitmap && !keepOriginal && !exposure.bitmap.isRecycled) {
                            exposure.bitmap.recycle()
                        }
                    }
                    if (frame.isHdrBracket) {
                        val merge = try {
                            withContext(Dispatchers.Default) {
                                HdrPipeline.merge(
                                    frames = preparedJpegs,
                                    referenceIndex = frame.referenceIndex,
                                    toneMap = effectiveSettings.hdrToneMap,
                                )
                            }
                        } catch (_: Exception) {
                            null
                        }
                        if (merge != null) {
                            jpegMerge = merge
                            gainField = merge.gainField
                            recycleDistinctExcept(
                                keep = if (effectiveSettings.saveOriginal) referenceOriginal else null,
                                bitmaps = buildList {
                                    addAll(frame.exposures.map { it.bitmap })
                                    addAll(preparedJpegs.map { it.bitmap })
                                },
                            )
                            merge.workingBitmap
                        } else {
                            effectiveSettings = effectiveSettings.copy(
                                hdrCaptureMode = HdrCaptureMode.OFF,
                                ultraHdrExport = false,
                            )
                            preparedJpegs[frame.referenceIndex].bitmap
                        }
                    } else {
                        preparedJpegs[frame.referenceIndex].bitmap
                    }
                }
            }
            filmInput = working

            val filmRender = renderWithAdaptiveGpuFallback(
                input = working,
                settings = effectiveSettings,
                process = process,
            )
            rendered = filmRender
            val finished = withContext(Dispatchers.Default) {
                OutputPipeline.finalizeExport(filmRender, effectiveSettings.outputMode)
            }
            finalOutput = finished

            if (effectiveSettings.ultraHdrExport && gainField != null) {
                ultraHdr = try {
                    withContext(Dispatchers.Default) {
                        UltraHdrExporter.attachIfSupported(finished, requireNotNull(gainField))
                    }
                } catch (_: Exception) {
                    null
                }
            }

            val saveBitmap = ultraHdr?.bitmap ?: finished
            val hdrFrameCount = when (effectiveSettings.hdrCaptureMode) {
                HdrCaptureMode.THREE_FRAME -> frame.exposures.size
                HdrCaptureMode.RAW_THREE_FRAME -> frame.rawExposures.size
                HdrCaptureMode.OFF -> 1
            }
            val result = withContext(Dispatchers.IO) {
                mediaRepository.saveCapture(
                    processed = saveBitmap,
                    original = if (effectiveSettings.saveOriginal) referenceOriginal else null,
                    rawSidecars = rawFiles,
                    settings = effectiveSettings,
                    ultraHdr = ultraHdr != null,
                    hdrFrameCount = hdrFrameCount,
                )
            }
            refreshGallery()
            return result
        } finally {
            rawFiles.forEach(File::delete)
            ultraHdr?.recycle()
            recycleDistinct(
                *buildList {
                    addAll(frame.exposures.map { it.bitmap })
                    addAll(preparedJpegs.map { it.bitmap })
                    add(jpegMerge?.workingBitmap)
                    add(rawMerge?.workingBitmap)
                    add(filmInput)
                    add(rendered)
                    add(finalOutput)
                }.toTypedArray(),
            )
        }
    }

    /**
     * A large offscreen RGBA target can exceed mobile GPU memory even when its
     * dimensions are below GL_MAX_TEXTURE_SIZE. Full/HQ capture now retries the
     * film render at descending, aspect-preserving sizes instead of discarding
     * the completed bracket with `Capture framebuffer incomplete: 0`.
     */
    private suspend fun renderWithAdaptiveGpuFallback(
        input: Bitmap,
        settings: CameraSettings,
        process: suspend (Bitmap, CameraSettings) -> Bitmap,
    ): Bitmap {
        val longEdge = maxOf(input.width, input.height)
        val candidates = when (settings.outputMode) {
            OutputMode.FULL_RESOLUTION -> listOf(4096, 3456, 3072, 2560, 2304, 2048, 1920)
            OutputMode.HQ_1080 -> listOf(3072, 2560, 2304, 2048, 1920)
            OutputMode.FAST_1080 -> listOf(1920, 1600, 1280)
        }.filter { it < longEdge }

        var current = input
        var ownsCurrent = false
        var nextIndex = 0
        while (true) {
            try {
                val result = process(current, settings)
                if (ownsCurrent && !current.isRecycled) current.recycle()
                return result
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (!isRetryableGpuAllocationFailure(error) || nextIndex >= candidates.size) {
                    if (ownsCurrent && !current.isRecycled) current.recycle()
                    throw error
                }
                if (ownsCurrent && !current.isRecycled) current.recycle()
                val targetLongEdge = candidates[nextIndex++]
                val scale = targetLongEdge / longEdge.toFloat()
                current = Bitmap.createScaledBitmap(
                    input,
                    (input.width * scale).roundToInt().coerceAtLeast(1),
                    (input.height * scale).roundToInt().coerceAtLeast(1),
                    true,
                )
                ownsCurrent = true
            }
        }
    }

    private fun isRetryableGpuAllocationFailure(error: Throwable): Boolean {
        if (error is OutOfMemoryError) return true
        val text = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return "framebuffer incomplete" in text ||
            "out of memory" in text ||
            "gl_out_of_memory" in text ||
            "texture allocation" in text
    }

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
            val filmRender = renderWithAdaptiveGpuFallback(prepared, effectiveSettings, process)
            rendered = filmRender
            val final = withContext(Dispatchers.Default) {
                OutputPipeline.finalizeExport(filmRender, effectiveSettings.outputMode)
            }
            processed = final
            val result = withContext(Dispatchers.IO) {
                mediaRepository.saveCapture(
                    processed = final,
                    original = if (effectiveSettings.saveOriginal) original else null,
                    rawSidecars = emptyList(),
                    settings = effectiveSettings,
                    ultraHdr = false,
                    hdrFrameCount = 1,
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

    private fun recycleDistinctExcept(keep: Bitmap?, bitmaps: List<Bitmap?>) {
        val seen = mutableListOf<Bitmap>()
        bitmaps.forEach { bitmap ->
            if (bitmap != null && bitmap !== keep && seen.none { it === bitmap }) {
                seen += bitmap
                if (!bitmap.isRecycled) bitmap.recycle()
            }
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

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
import com.renardoberou.spectralcamera.core.CaptureActionResult
import com.renardoberou.spectralcamera.core.CaptureResult
import com.renardoberou.spectralcamera.core.DoubleExposureMode
import com.renardoberou.spectralcamera.core.FocusMode
import com.renardoberou.spectralcamera.core.GalleryItem
import com.renardoberou.spectralcamera.core.HardwareTestState
import com.renardoberou.spectralcamera.core.HdrCaptureMode
import com.renardoberou.spectralcamera.core.HdrToneMap
import com.renardoberou.spectralcamera.core.OutputMode
import com.renardoberou.spectralcamera.core.SpectralPreset
import com.renardoberou.spectralcamera.core.camera.CameraController
import com.renardoberou.spectralcamera.core.camera.CapturedExposure
import com.renardoberou.spectralcamera.core.capture.DoubleExposurePipeline
import com.renardoberou.spectralcamera.core.data.CameraSettingsRepository
import com.renardoberou.spectralcamera.core.export.OutputPipeline
import com.renardoberou.spectralcamera.core.hardware.HardwareTestAnalyzer
import com.renardoberou.spectralcamera.core.hdr.HdrGainField
import com.renardoberou.spectralcamera.core.hdr.HdrMergeResult
import com.renardoberou.spectralcamera.core.hdr.HdrPipeline
import com.renardoberou.spectralcamera.core.hdr.RawHdrMergeResult
import com.renardoberou.spectralcamera.core.hdr.RawHdrSafetyPipeline
import com.renardoberou.spectralcamera.core.hdr.UltraHdrExporter
import com.renardoberou.spectralcamera.core.hdr.UltraHdrImage
import com.renardoberou.spectralcamera.core.hdr.orientLikeBitmap
import com.renardoberou.spectralcamera.core.hdr.prepareForOutput
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

data class DoubleExposureUiState(
    val waitingForSecond: Boolean = false,
    val overlayBitmap: Bitmap? = null,
)

private data class DoubleExposureSession(
    val firstFrame: Bitmap,
    val outputMode: OutputMode,
    val frontFacing: Boolean,
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

    private val _doubleExposureState = MutableStateFlow(DoubleExposureUiState())
    val doubleExposureState = _doubleExposureState.asStateFlow()
    private var doubleExposureSession: DoubleExposureSession? = null

    init {
        refreshGallery()
    }

    fun onAnalysisFrame(bitmap: Bitmap) {
        _hardwareState.value = hardwareAnalyzer.analyze(bitmap)
    }

    fun resetHardwareTest() {
        _hardwareState.value = HardwareTestState.idle()
    }

    fun updateSettings(transform: (CameraSettings) -> CameraSettings) {
        val updated = transform(settings.value)
        viewModelScope.launch { settingsRepository.save(updated) }
    }

    fun setPreset(preset: SpectralPreset) = updateSettings { it.copy(preset = preset) }
    fun setSaveOriginal(enabled: Boolean) = updateSettings { it.copy(saveOriginal = enabled) }

    fun setFrontFacing(enabled: Boolean) {
        if (enabled != settings.value.frontFacing) cancelDoubleExposure()
        updateSettings { it.copy(frontFacing = enabled) }
    }

    fun setOutputMode(mode: OutputMode) {
        if (mode != settings.value.outputMode) cancelDoubleExposure()
        updateSettings { it.copy(outputMode = mode) }
    }

    fun setHdrCaptureMode(mode: HdrCaptureMode) {
        if (mode != HdrCaptureMode.OFF) cancelDoubleExposure()
        updateSettings { current ->
            when (mode) {
                HdrCaptureMode.OFF -> current.copy(hdrCaptureMode = mode, ultraHdrExport = false)
                HdrCaptureMode.THREE_FRAME -> current.copy(
                    hdrCaptureMode = mode,
                    doubleExposureMode = DoubleExposureMode.OFF,
                    saveRawSidecar = false,
                )
                HdrCaptureMode.RAW_THREE_FRAME -> current.copy(
                    hdrCaptureMode = mode,
                    doubleExposureMode = DoubleExposureMode.OFF,
                    saveOriginal = false,
                    saveRawSidecar = true,
                )
            }
        }
    }

    fun setDoubleExposureMode(mode: DoubleExposureMode) {
        cancelDoubleExposure()
        updateSettings { current ->
            when (mode) {
                DoubleExposureMode.OFF -> current.copy(doubleExposureMode = mode)
                DoubleExposureMode.FILM_BALANCED -> current.copy(
                    doubleExposureMode = mode,
                    hdrCaptureMode = HdrCaptureMode.OFF,
                    ultraHdrExport = false,
                    saveRawSidecar = false,
                )
            }
        }
    }

    fun cancelDoubleExposure() {
        val session = doubleExposureSession
        doubleExposureSession = null
        _doubleExposureState.value = DoubleExposureUiState()
        session?.firstFrame?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    fun setHdrToneMap(mode: HdrToneMap) = updateSettings { it.copy(hdrToneMap = mode) }

    fun setUltraHdrExport(enabled: Boolean) = updateSettings { current ->
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        current.copy(
            ultraHdrExport = enabled && supported && current.hdrCaptureMode != HdrCaptureMode.OFF,
        )
    }

    fun setSaveRawSidecar(enabled: Boolean) {
        if (enabled) cancelDoubleExposure()
        updateSettings { current ->
            when {
                current.hdrCaptureMode == HdrCaptureMode.RAW_THREE_FRAME ->
                    current.copy(saveRawSidecar = enabled)
                enabled -> current.copy(
                    saveRawSidecar = true,
                    hdrCaptureMode = HdrCaptureMode.OFF,
                    doubleExposureMode = DoubleExposureMode.OFF,
                    ultraHdrExport = false,
                )
                else -> current.copy(saveRawSidecar = false)
            }
        }
    }

    fun setHardwareEv(value: Float) = updateSettings { it.copy(hardwareEv = value) }
    fun setManualMode(enabled: Boolean) { manualModeSession.value = enabled }
    fun setManualIso(iso: Int) = updateSettings { it.copy(manualIso = iso) }
    fun setManualShutter(nanos: Long) = updateSettings { it.copy(manualShutterNs = nanos) }
    fun setFocusMode(mode: FocusMode) = updateSettings { it.copy(focusMode = mode) }
    fun setManualFocusPosition(position: Float) =
        updateSettings { it.copy(manualFocusPosition = position.coerceIn(0f, 1f)) }
    fun setIntensity(value: Float) = updateSettings { it.copy(intensity = value) }
    fun setZebra(enabled: Boolean) = updateSettings { it.copy(zebraEnabled = enabled) }

    fun setSensorMode(mode: com.renardoberou.spectralcamera.core.SensorMode) {
        if (mode != settings.value.sensorMode) cancelDoubleExposure()
        updateSettings { it.copy(sensorMode = mode) }
    }

    fun updateAdjustments(transform: (com.renardoberou.spectralcamera.core.ManualAdjustments) -> com.renardoberou.spectralcamera.core.ManualAdjustments) {
        updateSettings { current -> current.copy(adjustments = transform(current.adjustments)) }
    }

    suspend fun captureAndSave(
        cameraController: CameraController,
        process: suspend (Bitmap, CameraSettings) -> Bitmap,
    ): CaptureActionResult {
        val requestedSettings = settings.value
        return if (requestedSettings.doubleExposureMode == DoubleExposureMode.FILM_BALANCED) {
            captureDoubleExposure(cameraController, requestedSettings, process)
        } else {
            CaptureActionResult.Saved(
                captureSingleOrHdr(cameraController, requestedSettings, process),
            )
        }
    }

    private suspend fun captureDoubleExposure(
        cameraController: CameraController,
        requestedSettings: CameraSettings,
        process: suspend (Bitmap, CameraSettings) -> Bitmap,
    ): CaptureActionResult {
        val existing = doubleExposureSession
        if (existing != null &&
            (existing.outputMode != requestedSettings.outputMode ||
                existing.frontFacing != requestedSettings.frontFacing)
        ) {
            cancelDoubleExposure()
        }
        val captureSettings = requestedSettings.copy(
            hdrCaptureMode = HdrCaptureMode.OFF,
            ultraHdrExport = false,
            saveRawSidecar = false,
        )

        if (doubleExposureSession == null) {
            val frame = cameraController.capture(captureSettings)
            val source = requireNotNull(frame.referenceBitmap) {
                "Double exposure requires a Standard JPEG source"
            }
            var prepared: Bitmap? = null
            try {
                prepared = withContext(Dispatchers.Default) {
                    DoubleExposurePipeline.prepareFrame(source, captureSettings.outputMode)
                }
                frame.exposures.map { it.bitmap }.forEach { bitmap ->
                    if (bitmap !== prepared && !bitmap.isRecycled) bitmap.recycle()
                }
                frame.rawFiles.forEach(File::delete)
                val overlay = withContext(Dispatchers.Default) {
                    DoubleExposurePipeline.makeOverlay(prepared)
                }
                doubleExposureSession = DoubleExposureSession(
                    firstFrame = prepared,
                    outputMode = captureSettings.outputMode,
                    frontFacing = captureSettings.frontFacing,
                )
                _doubleExposureState.value = DoubleExposureUiState(
                    waitingForSecond = true,
                    overlayBitmap = overlay,
                )
                return CaptureActionResult.AwaitingSecondExposure(
                    "Double Exposure • frame 1 stored • recompose and capture frame 2",
                )
            } catch (error: Throwable) {
                prepared?.let { if (!it.isRecycled) it.recycle() }
                frame.exposures.map { it.bitmap }.forEach { bitmap ->
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
                frame.rawFiles.forEach(File::delete)
                throw error
            }
        }

        val session = requireNotNull(doubleExposureSession)
        val secondFrame = cameraController.capture(captureSettings)
        val secondSource = requireNotNull(secondFrame.referenceBitmap) {
            "Double exposure requires a Standard JPEG source"
        }
        var secondPrepared: Bitmap? = null
        var combined: Bitmap? = null
        var rendered: Bitmap? = null
        var finalOutput: Bitmap? = null
        var saved = false
        try {
            secondPrepared = withContext(Dispatchers.Default) {
                DoubleExposurePipeline.prepareFrame(secondSource, captureSettings.outputMode)
            }
            secondFrame.exposures.map { it.bitmap }.forEach { bitmap ->
                if (bitmap !== secondPrepared && !bitmap.isRecycled) bitmap.recycle()
            }
            secondFrame.rawFiles.forEach(File::delete)

            combined = withContext(Dispatchers.Default) {
                DoubleExposurePipeline.combine(session.firstFrame, secondPrepared)
            }
            val filmRender = renderWithAdaptiveGpuFallback(combined, captureSettings, process)
            rendered = filmRender
            val finished = withContext(Dispatchers.Default) {
                OutputPipeline.finalizeExport(filmRender, captureSettings.outputMode)
            }
            finalOutput = finished
            val result = withContext(Dispatchers.IO) {
                mediaRepository.saveCapture(
                    processed = finished,
                    originals = if (captureSettings.saveOriginal) {
                        listOf(session.firstFrame, secondPrepared)
                    } else {
                        emptyList()
                    },
                    rawSidecars = emptyList(),
                    settings = captureSettings,
                    ultraHdr = false,
                    frameCount = 2,
                    motionProtected = false,
                )
            }
            saved = true
            refreshGallery()
            return CaptureActionResult.Saved(result)
        } finally {
            secondFrame.rawFiles.forEach(File::delete)
            recycleDistinct(secondPrepared, combined, rendered, finalOutput)
            if (saved) cancelDoubleExposure()
        }
    }

    private suspend fun captureSingleOrHdr(
        cameraController: CameraController,
        requestedSettings: CameraSettings,
        process: suspend (Bitmap, CameraSettings) -> Bitmap,
    ): CaptureResult {
        val frame = cameraController.capture(requestedSettings)
        val effectiveSettings = when {
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
                    val referenceRaw = frame.rawExposures[frame.referenceIndex]
                    val merge = withContext(Dispatchers.Default) {
                        RawHdrSafetyPipeline.merge(
                            frames = frame.rawExposures,
                            referenceIndex = frame.referenceIndex,
                            toneMap = effectiveSettings.hdrToneMap,
                            frontFacing = effectiveSettings.frontFacing,
                        )
                    }
                    rawMerge = merge
                    val uprightWidth = merge.workingBitmap.width
                    val uprightHeight = merge.workingBitmap.height
                    gainField = merge.gainField
                        .orientLikeBitmap(
                            rotationDegrees = referenceRaw.rotationDegrees,
                            mirrorHorizontal = effectiveSettings.frontFacing,
                        )
                        .prepareForOutput(
                            sourceWidth = uprightWidth,
                            sourceHeight = uprightHeight,
                            mode = effectiveSettings.outputMode,
                        )
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
                        val merge = withContext(Dispatchers.Default) {
                            HdrPipeline.merge(
                                frames = preparedJpegs,
                                referenceIndex = frame.referenceIndex,
                                toneMap = effectiveSettings.hdrToneMap,
                            )
                        }
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
            val frameCount = when (effectiveSettings.hdrCaptureMode) {
                HdrCaptureMode.THREE_FRAME -> frame.exposures.size
                HdrCaptureMode.RAW_THREE_FRAME -> frame.rawExposures.size
                HdrCaptureMode.OFF -> 1
            }
            val motionProtected = jpegMerge?.motionProtected == true || rawMerge?.motionProtected == true
            val result = withContext(Dispatchers.IO) {
                mediaRepository.saveCapture(
                    processed = saveBitmap,
                    originals = if (effectiveSettings.saveOriginal && referenceOriginal != null) {
                        listOf(referenceOriginal)
                    } else {
                        emptyList()
                    },
                    rawSidecars = rawFiles,
                    settings = effectiveSettings,
                    ultraHdr = ultraHdr != null,
                    frameCount = frameCount,
                    motionProtected = motionProtected,
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
            doubleExposureMode = DoubleExposureMode.OFF,
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
                    originals = if (effectiveSettings.saveOriginal) listOf(original) else emptyList(),
                    rawSidecars = emptyList(),
                    settings = effectiveSettings,
                    ultraHdr = false,
                    frameCount = 1,
                    motionProtected = false,
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

    override fun onCleared() {
        cancelDoubleExposure()
        super.onCleared()
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

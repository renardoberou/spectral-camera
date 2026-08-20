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
import com.renardoberou.spectralcamera.core.ImportPreviewState
import com.renardoberou.spectralcamera.core.ManualAdjustments
import com.renardoberou.spectralcamera.core.OutputMode
import com.renardoberou.spectralcamera.core.SpectralPreset
import com.renardoberou.spectralcamera.core.WhiteBalancePreset
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
import com.renardoberou.spectralcamera.core.gl.SpectralGrainTrace
import com.renardoberou.spectralcamera.core.media.MediaRepository
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableStateFlow as VmMutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

/**
 * Serializes optimistic settings changes so delayed UI/effect setters always
 * merge against the latest owned snapshot instead of an older repository flow
 * emission. Persistence remains ordered, while [current] updates immediately.
 */
internal class SettingsUpdateCoordinator(
    initial: CameraSettings,
    private val persist: suspend (CameraSettings) -> Unit = {},
) {
    private val mutex = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    private val _current = MutableStateFlow(initial)
    private val _sequence = MutableStateFlow(0L)
    val current: StateFlow<CameraSettings> = _current.asStateFlow()
    val sequence: StateFlow<Long> = _sequence.asStateFlow()

    suspend fun initialize(persisted: CameraSettings) {
        mutex.withLock {
            if (!initialized.isCompleted) {
                _current.value = persisted
                initialized.complete(Unit)
            }
        }
    }

    suspend fun update(transform: (CameraSettings) -> CameraSettings): CameraSettings {
        initialized.await()
        return mutex.withLock {
            val updated = transform(_current.value)
            _current.value = updated
            _sequence.value += 1L
            persist(updated)
            updated
        }
    }
}

internal object CameraSettingsFieldIntents {
    fun grain(current: CameraSettings, value: Float): CameraSettings = current.copy(
        adjustments = current.adjustments.copy(grain = value),
    )

    fun contrast(current: CameraSettings, value: Float): CameraSettings = current.copy(
        adjustments = current.adjustments.copy(contrast = value),
    )

    fun saturation(current: CameraSettings, value: Float): CameraSettings = current.copy(
        adjustments = current.adjustments.copy(saturation = value),
    )
}

class SpectralViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepository = CameraSettingsRepository(application)
    private val mediaRepository = MediaRepository(application)
    private val hardwareAnalyzer = HardwareTestAnalyzer()
    private val manualModeSession = MutableStateFlow(false)
    private val settingsCoordinator = SettingsUpdateCoordinator(CameraSettings()) {
        settingsRepository.save(it)
    }
    val settingsSequence: StateFlow<Long> = settingsCoordinator.sequence

    val settings: StateFlow<CameraSettings> = settingsCoordinator.current
        .combine(manualModeSession) { owned, manual -> owned.copy(manualMode = manual) }.stateIn(
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
        viewModelScope.launch {
            settingsCoordinator.initialize(settingsRepository.settings.first())
        }
        refreshGallery()
    }

    fun onAnalysisFrame(bitmap: Bitmap) {
        _hardwareState.value = hardwareAnalyzer.analyze(bitmap)
    }

    fun resetHardwareTest() {
        _hardwareState.value = HardwareTestState.idle()
    }

    fun updateSettings(transform: (CameraSettings) -> CameraSettings) {
        viewModelScope.launch {
            val updated = settingsCoordinator.update(transform)
            SpectralGrainTrace.viewModelOwned(settingsCoordinator.sequence.value, updated)
        }
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
    fun setWhiteBalancePreset(preset: WhiteBalancePreset) =
        updateSettings { it.copy(whiteBalancePreset = preset) }
    fun setFocusMode(mode: FocusMode) = updateSettings { it.copy(focusMode = mode) }
    fun setManualFocusPosition(position: Float) =
        updateSettings { it.copy(manualFocusPosition = position.coerceIn(0f, 1f)) }
    fun setIntensity(value: Float) = updateSettings { it.copy(intensity = value) }
    fun setZebra(enabled: Boolean) = updateSettings { it.copy(zebraEnabled = enabled) }
    fun setClassifierDebugView(enabled: Boolean) = updateSettings { it.copy(classifierDebugView = enabled) }

    fun setSensorMode(mode: com.renardoberou.spectralcamera.core.SensorMode) {
        if (mode != settings.value.sensorMode) cancelDoubleExposure()
        updateSettings { it.copy(sensorMode = mode) }
    }

    fun updateAdjustments(transform: (com.renardoberou.spectralcamera.core.ManualAdjustments) -> com.renardoberou.spectralcamera.core.ManualAdjustments) {
        updateSettings { current -> current.copy(adjustments = transform(current.adjustments)) }
    }

    fun resetAdjustments() = updateSettings { it.copy(adjustments = ManualAdjustments()) }

    fun setGrain(value: Float) = updateSettings { current ->
        CameraSettingsFieldIntents.grain(current, value)
    }

    fun setContrast(value: Float) = updateSettings { current ->
        CameraSettingsFieldIntents.contrast(current, value)
    }

    fun setSaturation(value: Float) = updateSettings { current ->
        CameraSettingsFieldIntents.saturation(current, value)
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

    private suspend fun decodeUri(uri: Uri): Bitmap {
        val app = getApplication<Application>()
        val decoded = withContext(Dispatchers.IO) {
            val viaImageDecoder = runCatching {
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
            // Fallback for providers ImageDecoder can reject or stall on -
            // cloud-backed "Collections"/shared-album items resolve through
            // a different content path than local MediaStore photos and can
            // need a plain streamed read instead of ImageDecoder's source
            // API. Retry via BitmapFactory before giving up.
            viaImageDecoder.getOrElse {
                app.contentResolver.openInputStream(uri)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream)
                } ?: throw IllegalStateException("Unable to open the selected photo (no data at this URI).")
            }
        }
        return if (decoded.config != Bitmap.Config.ARGB_8888) {
            decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
        } else {
            decoded
        }
    }

    /** Full-resolution render + save with an EXPLICIT settings snapshot (the
     * settings the user chose for THIS photo, not necessarily the live
     * camera's current settings). Shared by the one-tap import path and the
     * Import Preview screen's Save action. */
    private suspend fun renderAndSave(
        original: Bitmap,
        settings: CameraSettings,
        process: suspend (Bitmap, CameraSettings) -> Bitmap,
    ): CaptureResult {
        val effectiveSettings = settings.copy(
            hdrCaptureMode = HdrCaptureMode.OFF,
            doubleExposureMode = DoubleExposureMode.OFF,
            ultraHdrExport = false,
        )
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

    suspend fun importAndSave(
        uri: Uri,
        process: suspend (Bitmap, CameraSettings) -> Bitmap,
    ): CaptureResult {
        val original = decodeUri(uri)
        return renderAndSave(original, settings.value, process)
    }

    // ---- Import Preview: choose/preview settings for a specific photo -----

    private var previewLoadJob: Job? = null
    private var previewRenderJob: Job? = null
    private var previewRenderToken: Int = 0

    private val _importPreview = VmMutableStateFlow<ImportPreviewState?>(null)
    val importPreview: StateFlow<ImportPreviewState?> = _importPreview

    // Decode failure (or a pending decode the screen should keep waiting on)
    // is distinct from "there is nothing to show yet": the screen must NOT
    // treat importPreview == null as "cancelled" while a decode is still in
    // flight, or slower content providers (cloud-backed "Collections"/shared
    // albums, which resolve through a different path than local MediaStore
    // photos and can take noticeably longer to decode) would have the
    // preview screen pop itself closed before the photo ever loads.
    private val _importLoading = VmMutableStateFlow(false)
    val importLoading: StateFlow<Boolean> = _importLoading

    private val _importError = VmMutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError

    fun beginImportPreview(
        uri: Uri,
        process: suspend (Bitmap, CameraSettings) -> Bitmap,
    ) {
        _importError.value = null
        _importLoading.value = true
        previewLoadJob = viewModelScope.launch {
            val original = runCatching { decodeUri(uri) }
                .onFailure {
                    _importError.value = "Couldn't open that photo: ${it.message ?: it.javaClass.simpleName}"
                    _importLoading.value = false
                }
                .getOrNull() ?: return@launch
            val initialSettings = settings.value.copy(
                hdrCaptureMode = HdrCaptureMode.OFF,
                doubleExposureMode = DoubleExposureMode.OFF,
                ultraHdrExport = false,
            )
            _importLoading.value = false
            _importPreview.value = ImportPreviewState(
                uri = uri,
                original = original,
                settings = initialSettings,
                preview = null,
                isRendering = true,
            )
            renderPreview(original, initialSettings, process)
        }
    }

    fun updatePreviewSettings(
        transform: (CameraSettings) -> CameraSettings,
        process: suspend (Bitmap, CameraSettings) -> Bitmap,
    ) {
        val state = _importPreview.value ?: return
        val updated = transform(state.settings)
        _importPreview.value = state.copy(settings = updated, isRendering = true)
        renderPreview(state.original, updated, process)
    }

    /** Latest-wins preview render: a monotonic token discards any in-flight
     * render that a newer control change has already superseded, so quick
     * successive taps never let a stale preview frame arrive late. */
    private fun renderPreview(
        original: Bitmap,
        settings: CameraSettings,
        process: suspend (Bitmap, CameraSettings) -> Bitmap,
    ) {
        val token = ++previewRenderToken
        previewRenderJob?.cancel()
        previewRenderJob = viewModelScope.launch {
            val longEdge = maxOf(original.width, original.height)
            val previewSource = if (longEdge > 1024) {
                val scale = 1024f / longEdge
                withContext(Dispatchers.Default) {
                    Bitmap.createScaledBitmap(
                        original,
                        (original.width * scale).roundToInt().coerceAtLeast(1),
                        (original.height * scale).roundToInt().coerceAtLeast(1),
                        true,
                    )
                }
            } else {
                original
            }
            val rendered = runCatching { process(previewSource, settings) }.getOrNull()
            if (previewSource !== original && !previewSource.isRecycled) previewSource.recycle()
            if (token != previewRenderToken) {
                rendered?.let { if (!it.isRecycled) it.recycle() }
                return@launch
            }
            val current = _importPreview.value ?: return@launch
            current.preview?.let { if (!it.isRecycled && it !== rendered) it.recycle() }
            _importPreview.value = current.copy(preview = rendered, isRendering = false)
        }
    }

    suspend fun confirmImportPreview(
        process: suspend (Bitmap, CameraSettings) -> Bitmap,
    ): CaptureResult? {
        val state = _importPreview.value ?: return null
        _importPreview.value = state.copy(isSaving = true)
        val result = runCatching { renderAndSave(state.original, state.settings, process) }
        state.preview?.let { if (!it.isRecycled) it.recycle() }
        _importPreview.value = null
        return result.getOrThrow()
    }

    fun cancelImportPreview() {
        previewLoadJob?.cancel()
        previewRenderJob?.cancel()
        _importLoading.value = false
        _importError.value = null
        val state = _importPreview.value
        if (state != null) {
            if (!state.original.isRecycled) state.original.recycle()
            state.preview?.let { if (!it.isRecycled) it.recycle() }
        }
        _importPreview.value = null
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

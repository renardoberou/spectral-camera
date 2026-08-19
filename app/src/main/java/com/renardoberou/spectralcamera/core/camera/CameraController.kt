package com.renardoberou.spectralcamera.core.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DisplayOrientedMeteringPointFactory
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import com.renardoberou.spectralcamera.core.CameraCapabilities
import com.renardoberou.spectralcamera.core.CameraSettings
import com.renardoberou.spectralcamera.core.FocusDistanceCalibration
import com.renardoberou.spectralcamera.core.FocusMode
import com.renardoberou.spectralcamera.core.FocusTapResult
import com.renardoberou.spectralcamera.core.HdrCaptureMode
import com.renardoberou.spectralcamera.core.OutputMode
import com.renardoberou.spectralcamera.core.WhiteBalancePreset
import com.renardoberou.spectralcamera.core.gl.SpectralGlView
import com.renardoberou.spectralcamera.core.focus.FocusMath
import com.renardoberou.spectralcamera.core.hdr.BayerArrangement
import com.renardoberou.spectralcamera.core.hdr.BayerChannel
import com.renardoberou.spectralcamera.core.hdr.HdrBracketPlanner
import com.renardoberou.spectralcamera.core.hdr.RawHdrMath
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.log2
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns CameraX and all capture-domain source acquisition.
 *
 * Standard and JPEG HDR use decoded JPEG frames. True RAW HDR configures
 * ImageCapture for in-memory RAW_SENSOR output, fixes ISO, brackets shutter,
 * copies the native Bayer plane, and optionally writes each still-open RAW
 * image to DNG using its exact TotalCaptureResult.
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class, ExperimentalGetImage::class)
class CameraController(context: Context) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val captureMutex = Mutex()

    private var glView: SpectralGlView? = null
    private var onCapabilities: ((CameraCapabilities) -> Unit)? = null
    private var onAnalysisFrame: ((Bitmap) -> Unit)? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var outputMode: OutputMode = OutputMode.FULL_RESOLUTION
    private var hdrCaptureMode: HdrCaptureMode = HdrCaptureMode.OFF
    private var rawSidecarRequested: Boolean = false

    @Volatile private var rawJpegActive: Boolean = false
    @Volatile private var rawJpegUsable: Boolean = false
    @Volatile private var rawCaptureUsable: Boolean = false
    @Volatile private var rawHdrActive: Boolean = false
    @Volatile private var analysisEnabled: Boolean = false
    @Volatile private var lastAnalysisFrameAt = 0L

    private var activeCharacteristics: CameraCharacteristics? = null
    private var activeExposureRange: IntRange = 0..0
    private var activeExposureStep: Float = 1f / 3f
    private var activeIsoRange: IntRange? = null
    private var activeExposureTimeRange: LongRange? = null
    private var activeManualExposureSupported: Boolean = false
    private var activeAwbLockSupported: Boolean = false
    private var activeWhiteBalanceSupport: WhiteBalanceSupport = WhiteBalanceSupport()

    private var focusCapabilitiesKnown: Boolean = false
    private var activeFocusMode: FocusMode = FocusMode.CONTINUOUS
    private var activeManualFocusPosition: Float = 0.15f
    private var activeMinimumFocusDistance: Float = 0f
    private var activeContinuousFocusSupported: Boolean = false
    private var activeTapFocusSupported: Boolean = false
    private var activeMacroFocusSupported: Boolean = false
    private var activeManualFocusSupported: Boolean = false
    private var activeInfinityFocusSupported: Boolean = false
    private var activeAfModes: Set<Int> = emptySet()
    private var activeContinuousAfMode: Int = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE

    private val latestCaptureResult = AtomicReference<TotalCaptureResult?>(null)
    private val captureResultsByTimestamp = ConcurrentHashMap<Long, TotalCaptureResult>()

    private val metadataCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            latestCaptureResult.set(result)
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
            if (timestamp != null) {
                captureResultsByTimestamp[timestamp] = result
                if (captureResultsByTimestamp.size > 64) {
                    captureResultsByTimestamp.keys.sorted().take(16).forEach(captureResultsByTimestamp::remove)
                }
            }
        }
    }

    // Preview surface plumbing. Main-thread only.
    private var surfaceTexture: SurfaceTexture? = null
    private var pendingRequest: SurfaceRequest? = null
    private var sourceRotation = 0
    private var sourceResolution: Size? = null

    private val surfaceProvider = Preview.SurfaceProvider { request ->
        pendingRequest?.willNotProvideSurface()
        pendingRequest = request
        tryFulfillRequest()
    }

    fun onSurfaceTextureAvailable(texture: SurfaceTexture) {
        surfaceTexture = texture
        if (pendingRequest != null) {
            tryFulfillRequest()
        } else {
            preview?.setSurfaceProvider(null)
            preview?.setSurfaceProvider(surfaceProvider)
        }
    }

    private fun tryFulfillRequest() {
        val request = pendingRequest ?: return
        val texture = surfaceTexture ?: return
        val resolution = request.resolution
        sourceResolution = resolution
        texture.setDefaultBufferSize(resolution.width, resolution.height)
        updateSourceGeometry(currentRelativeRotation())
        request.setTransformationInfoListener(mainExecutor) { info ->
            updateSourceGeometry(info.rotationDegrees)
        }
        val surface = Surface(texture)
        request.provideSurface(surface, mainExecutor) { surface.release() }
        pendingRequest = null
    }

    private fun updateSourceGeometry(rotationDegrees: Int) {
        sourceRotation = rotationDegrees
        sourceResolution?.let { resolution ->
            glView?.configureSource(resolution.width, resolution.height, sourceRotation)
        }
    }

    private fun currentRelativeRotation(): Int {
        val info = camera?.cameraInfo ?: return sourceRotation
        val displayRotation = glView?.display?.rotation ?: Surface.ROTATION_0
        return try {
            info.getSensorRotationDegrees(displayRotation)
        } catch (_: Exception) {
            sourceRotation
        }
    }

    fun bind(
        lifecycleOwner: LifecycleOwner,
        glView: SpectralGlView,
        lensFacing: Int,
        outputMode: OutputMode,
        hdrCaptureMode: HdrCaptureMode,
        rawSidecarRequested: Boolean,
        onCapabilities: (CameraCapabilities) -> Unit,
        onAnalysisFrame: (Bitmap) -> Unit,
    ) {
        this.glView = glView
        this.lensFacing = lensFacing
        this.outputMode = outputMode
        this.hdrCaptureMode = hdrCaptureMode
        this.rawSidecarRequested = rawSidecarRequested
        this.onCapabilities = onCapabilities
        this.onAnalysisFrame = onAnalysisFrame

        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener({
            bindUseCases(providerFuture.get(), lifecycleOwner)
        }, mainExecutor)
    }

    fun setTorch(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
    }

    fun setExposureCompensation(index: Int) {
        camera?.cameraControl?.setExposureCompensationIndex(index)
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun applyUserControls(settings: CameraSettings) {
        val activeCamera = camera ?: return
        val previousMode = activeFocusMode
        activeFocusMode = effectiveFocusMode(settings.focusMode)
        activeManualFocusPosition = settings.manualFocusPosition.coerceIn(0f, 1f)
        if (previousMode != activeFocusMode) {
            activeCamera.cameraControl.cancelFocusAndMetering()
        }
        Camera2CameraControl.from(activeCamera.cameraControl)
            .setCaptureRequestOptions(userCaptureRequestOptions(settings, lockAwb = false))
    }

    fun setAnalysisEnabled(enabled: Boolean) {
        analysisEnabled = enabled
    }

    fun unlockFocus() {
        camera?.cameraControl?.cancelFocusAndMetering()
    }

    fun focusAt(
        x: Float,
        y: Float,
        viewWidth: Float,
        viewHeight: Float,
        manualExposure: Boolean,
        onResult: (FocusTapResult) -> Unit = {},
    ) {
        val activeCamera = camera ?: run {
            onResult(FocusTapResult.UNSUPPORTED)
            return
        }
        val display = glView?.display ?: run {
            onResult(FocusTapResult.UNSUPPORTED)
            return
        }
        if (viewWidth <= 0f || viewHeight <= 0f) {
            onResult(FocusTapResult.UNSUPPORTED)
            return
        }
        val resolution = sourceResolution
        val rotated = sourceRotation == 90 || sourceRotation == 270
        val contentW = (if (rotated) resolution?.height else resolution?.width)?.toFloat() ?: viewWidth
        val contentH = (if (rotated) resolution?.width else resolution?.height)?.toFloat() ?: viewHeight
        if (contentW <= 0f || contentH <= 0f) {
            onResult(FocusTapResult.UNSUPPORTED)
            return
        }
        val scale = maxOf(viewWidth / contentW, viewHeight / contentH)
        val fullW = contentW * scale
        val fullH = contentH * scale
        val factory = DisplayOrientedMeteringPointFactory(display, activeCamera.cameraInfo, fullW, fullH)
        val point = factory.createPoint(x + (fullW - viewWidth) / 2f, y + (fullH - viewHeight) / 2f)

        if (
            activeFocusMode == FocusMode.MANUAL ||
            activeFocusMode == FocusMode.INFINITY ||
            activeFocusMode == FocusMode.FIXED
        ) {
            if (manualExposure) {
                onResult(FocusTapResult.IGNORED)
                return
            }
            val metering = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
            val future = activeCamera.cameraControl.startFocusAndMetering(metering)
            future.addListener({
                val metered = runCatching { future.get() }.isSuccess
                onResult(if (metered) FocusTapResult.METERED else FocusTapResult.FAILED)
            }, mainExecutor)
            return
        }

        val autofocusSupported = when (activeFocusMode) {
            FocusMode.MACRO -> activeMacroFocusSupported
            FocusMode.CONTINUOUS -> activeContinuousFocusSupported || activeTapFocusSupported
            FocusMode.TAP_LOCK -> activeTapFocusSupported
            FocusMode.FIXED,
            FocusMode.MANUAL,
            FocusMode.INFINITY,
            -> false
        }
        if (!autofocusSupported) {
            onResult(FocusTapResult.UNSUPPORTED)
            return
        }

        val meteringFlags = if (activeFocusMode == FocusMode.CONTINUOUS) {
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        } else {
            // Tap Lock and Macro lock focus only; exposure remains governed by
            // the user's Auto/Manual exposure setting rather than being held at
            // the focus point for the whole locked interval.
            FocusMeteringAction.FLAG_AF
        }
        val builder = FocusMeteringAction.Builder(point, meteringFlags)
        if (activeFocusMode == FocusMode.CONTINUOUS) {
            builder.setAutoCancelDuration(3, TimeUnit.SECONDS)
        } else {
            builder.disableAutoCancel()
        }
        val future = activeCamera.cameraControl.startFocusAndMetering(builder.build())
        future.addListener({
            val successful = runCatching { future.get().isFocusSuccessful }.getOrDefault(false)
            onResult(
                when {
                    !successful -> FocusTapResult.FAILED
                    activeFocusMode == FocusMode.CONTINUOUS -> FocusTapResult.FOCUSED
                    else -> FocusTapResult.LOCKED
                },
            )
        }, mainExecutor)
    }

    suspend fun capture(settings: CameraSettings): CapturedFrame = captureMutex.withLock {
        activeFocusMode = effectiveFocusMode(settings.focusMode)
        activeManualFocusPosition = settings.manualFocusPosition.coerceIn(0f, 1f)
        val capture = imageCapture ?: throw IllegalStateException("Camera not ready")
        when {
            settings.hdrCaptureMode == HdrCaptureMode.RAW_THREE_FRAME && rawHdrActive ->
                captureRawHdrBracket(capture, settings)
            settings.hdrCaptureMode == HdrCaptureMode.RAW_THREE_FRAME ->
                CapturedFrame(exposures = listOf(CapturedExposure(captureJpegBitmap(capture), 0f)))
            settings.hdrCaptureMode == HdrCaptureMode.THREE_FRAME -> captureJpegHdrBracket(capture, settings)
            rawJpegActive -> captureRawJpeg(capture)
            else -> CapturedFrame(exposures = listOf(CapturedExposure(captureJpegBitmap(capture), 0f)))
        }
    }

    private suspend fun captureJpegHdrBracket(
        capture: ImageCapture,
        settings: CameraSettings,
    ): CapturedFrame {
        val activeCamera = camera ?: return CapturedFrame(
            exposures = listOf(CapturedExposure(captureJpegBitmap(capture), 0f)),
        )
        val captured = mutableListOf<CapturedExposure>()
        val heldFocusDistance = focusDistanceForBracket()
        return try {
            if (settings.manualMode && activeManualExposureSupported && activeExposureTimeRange != null) {
                val iso = settings.manualIso.coerceIn(
                    activeIsoRange?.first ?: settings.manualIso,
                    activeIsoRange?.last ?: settings.manualIso,
                )
                val plan = HdrBracketPlanner.planManual(
                    baseShutterNs = settings.manualShutterNs,
                    supportedRange = requireNotNull(activeExposureTimeRange),
                )
                if (plan.size < 2) {
                    return CapturedFrame(exposures = listOf(CapturedExposure(captureJpegBitmap(capture), 0f)))
                }
                plan.forEach { (shutter, evOffset) ->
                    applyManualExposureAndAwait(
                        iso = iso,
                        shutterNs = shutter,
                        whiteBalancePreset = settings.whiteBalancePreset,
                        lockAwb = true,
                        heldFocusDistance = heldFocusDistance,
                    )
                    captured += CapturedExposure(captureJpegBitmap(capture), evOffset)
                }
            } else {
                if (heldFocusDistance != null) {
                    applyAutoExposureFocusHoldAndAwait(
                        focusDistance = heldFocusDistance,
                        whiteBalancePreset = settings.whiteBalancePreset,
                        lockAwb = true,
                    )
                }
                val baseIndex = activeCamera.cameraInfo.exposureState.exposureCompensationIndex
                    .coerceIn(activeExposureRange.first, activeExposureRange.last)
                val plan = HdrBracketPlanner.planAuto(baseIndex, activeExposureRange, activeExposureStep)
                if (plan.size < 2) {
                    return CapturedFrame(exposures = listOf(CapturedExposure(captureJpegBitmap(capture), 0f)))
                }
                plan.forEach { step ->
                    activeCamera.cameraControl.setExposureCompensationIndex(step.compensationIndex).await()
                    captured += CapturedExposure(captureJpegBitmap(capture), step.evOffset)
                }
            }
            val reference = captured.indices.minByOrNull { abs(captured[it].evOffset) } ?: 0
            CapturedFrame(exposures = captured.toList(), referenceIndex = reference)
        } catch (error: Throwable) {
            captured.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
            throw error
        } finally {
            restoreExposure(settings)
        }
    }

    /** Captures three minimally processed RAW_SENSOR mosaics at fixed ISO. */
    private suspend fun captureRawHdrBracket(
        capture: ImageCapture,
        settings: CameraSettings,
    ): CapturedFrame {
        val baseResult = awaitRecentCaptureResult()
        val baseShutter = if (settings.manualMode) {
            settings.manualShutterNs
        } else {
            baseResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: settings.manualShutterNs
        }
        val baseIso = if (settings.manualMode) {
            settings.manualIso
        } else {
            baseResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: settings.manualIso
        }
        val range = activeExposureTimeRange
            ?: throw IllegalStateException("True RAW HDR requires a reported sensor exposure-time range")
        val safeIso = activeIsoRange?.let { baseIso.coerceIn(it.first, it.last) } ?: baseIso
        val plan = HdrBracketPlanner.planManual(baseShutter, range)
        if (plan.size < 2) {
            throw IllegalStateException("The active camera cannot form a distinct RAW exposure bracket")
        }

        val heldFocusDistance = focusDistanceForBracket()
        val frames = mutableListOf<RawSensorFrame>()
        return try {
            plan.forEachIndexed { index, (shutter, plannedEv) ->
                applyManualExposureAndAwait(
                    iso = safeIso,
                    shutterNs = shutter,
                    whiteBalancePreset = settings.whiteBalancePreset,
                    lockAwb = true,
                    heldFocusDistance = heldFocusDistance,
                )
                frames += captureRawSensor(
                    capture = capture,
                    plannedEvOffset = plannedEv,
                    saveDng = settings.saveRawSidecar,
                    bracketIndex = index,
                )
            }
            val reference = frames.indices.minByOrNull { abs(frames[it].evOffset) } ?: 0
            CapturedFrame(rawExposures = frames.toList(), referenceIndex = reference)
        } catch (error: Throwable) {
            frames.mapNotNull { it.dngFile }.forEach(File::delete)
            throw error
        } finally {
            restoreExposure(settings)
        }
    }

    private suspend fun awaitRecentCaptureResult(): TotalCaptureResult? {
        repeat(50) {
            latestCaptureResult.get()?.let { return it }
            delay(10)
        }
        return latestCaptureResult.get()
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private suspend fun restoreExposure(settings: CameraSettings) {
        val activeCamera = camera ?: return
        try {
            activeFocusMode = effectiveFocusMode(settings.focusMode)
            activeManualFocusPosition = settings.manualFocusPosition.coerceIn(0f, 1f)
            Camera2CameraControl.from(activeCamera.cameraControl)
                .setCaptureRequestOptions(userCaptureRequestOptions(settings, lockAwb = false))
                .await()
            if (!settings.manualMode) {
                val target = Math.round(settings.hardwareEv / activeExposureStep.coerceAtLeast(1f / 6f))
                    .coerceIn(activeExposureRange.first, activeExposureRange.last)
                activeCamera.cameraControl.setExposureCompensationIndex(target).await()
            }
        } catch (_: Throwable) {
            Unit
        }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private suspend fun applyManualExposureAndAwait(
        iso: Int,
        shutterNs: Long,
        whiteBalancePreset: WhiteBalancePreset,
        lockAwb: Boolean,
        heldFocusDistance: Float? = null,
    ) {
        val activeCamera = camera ?: throw IllegalStateException("Camera closed during exposure bracket")
        val safeIso = activeIsoRange?.let { iso.coerceIn(it.first, it.last) } ?: iso
        val safeShutter = activeExposureTimeRange?.let { shutterNs.coerceIn(it.first, it.last) } ?: shutterNs
        Camera2CameraControl.from(activeCamera.cameraControl)
            .setCaptureRequestOptions(
                manualExposureOptions(
                    iso = safeIso,
                    shutterNs = safeShutter,
                    whiteBalancePreset = whiteBalancePreset,
                    lockAwb = lockAwb,
                    heldFocusDistance = heldFocusDistance,
                ),
            )
            .await()
    }

    private fun manualExposureOptions(
        iso: Int,
        shutterNs: Long,
        whiteBalancePreset: WhiteBalancePreset,
        lockAwb: Boolean,
        heldFocusDistance: Float? = null,
    ): CaptureRequestOptions {
        val builder = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs)
        if (heldFocusDistance != null && activeManualFocusSupported) {
            builder
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, heldFocusDistance)
        } else {
            appendFocusOptions(builder, activeFocusMode, activeManualFocusPosition)
        }
        appendWhiteBalanceOptions(builder, whiteBalancePreset, lockAutoAwb = lockAwb)
        return builder.build()
    }

    private fun userCaptureRequestOptions(
        settings: CameraSettings,
        lockAwb: Boolean,
    ): CaptureRequestOptions {
        val builder = CaptureRequestOptions.Builder()
        if (settings.manualMode && activeManualExposureSupported) {
            val safeIso = activeIsoRange?.let {
                settings.manualIso.coerceIn(it.first, it.last)
            } ?: settings.manualIso
            val safeShutter = activeExposureTimeRange?.let {
                settings.manualShutterNs.coerceIn(it.first, it.last)
            } ?: settings.manualShutterNs
            builder
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, safeIso)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, safeShutter)
        }
        appendFocusOptions(builder, effectiveFocusMode(settings.focusMode), settings.manualFocusPosition)
        appendWhiteBalanceOptions(builder, settings.whiteBalancePreset, lockAutoAwb = lockAwb)
        return builder.build()
    }

    private fun appendWhiteBalanceOptions(
        builder: CaptureRequestOptions.Builder,
        preset: WhiteBalancePreset,
        lockAutoAwb: Boolean,
    ) {
        when (val request = WhiteBalanceRequestPlanner.plan(preset, activeWhiteBalanceSupport)) {
            WhiteBalanceRequest.Auto -> appendAutoWhiteBalanceOptions(builder, lockAutoAwb)
            is WhiteBalanceRequest.Fixed -> {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    cameraAwbMode(request.mode),
                )
                if (activeAwbLockSupported) {
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
                }
            }
            is WhiteBalanceRequest.Cct -> {
                if (Build.VERSION.SDK_INT >= 36) {
                    appendCctWhiteBalanceOptions(builder, request.kelvin)
                } else {
                    appendAutoWhiteBalanceOptions(builder, lockAutoAwb)
                }
            }
        }
    }

    private fun appendAutoWhiteBalanceOptions(
        builder: CaptureRequestOptions.Builder,
        lockAutoAwb: Boolean,
    ) {
        builder.setCaptureRequestOption(
            CaptureRequest.CONTROL_AWB_MODE,
            CameraMetadata.CONTROL_AWB_MODE_AUTO,
        )
        if (activeAwbLockSupported) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, lockAutoAwb)
        }
    }

    @androidx.annotation.RequiresApi(36)
    private fun appendCctWhiteBalanceOptions(
        builder: CaptureRequestOptions.Builder,
        kelvin: Int,
    ) {
        builder
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CameraMetadata.CONTROL_AWB_MODE_OFF,
            )
            .setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CameraMetadata.COLOR_CORRECTION_MODE_CCT,
            )
            .setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE, kelvin)
        if (activeAwbLockSupported) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
        }
    }

    private fun cameraAwbMode(mode: FixedWhiteBalanceMode): Int = when (mode) {
        FixedWhiteBalanceMode.INCANDESCENT -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
        FixedWhiteBalanceMode.FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
        FixedWhiteBalanceMode.WARM_FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT
        FixedWhiteBalanceMode.DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
        FixedWhiteBalanceMode.CLOUDY_DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        FixedWhiteBalanceMode.TWILIGHT -> CameraMetadata.CONTROL_AWB_MODE_TWILIGHT
        FixedWhiteBalanceMode.SHADE -> CameraMetadata.CONTROL_AWB_MODE_SHADE
    }

    private fun appendFocusOptions(
        builder: CaptureRequestOptions.Builder,
        mode: FocusMode,
        manualPosition: Float,
    ) {
        when (mode) {
            FocusMode.CONTINUOUS -> if (activeContinuousFocusSupported) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, activeContinuousAfMode)
            }
            FocusMode.TAP_LOCK -> if (activeTapFocusSupported) {
                val afMode = when {
                    CameraMetadata.CONTROL_AF_MODE_AUTO in activeAfModes -> CameraMetadata.CONTROL_AF_MODE_AUTO
                    activeContinuousFocusSupported -> activeContinuousAfMode
                    else -> return
                }
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, afMode)
            }
            FocusMode.MACRO -> if (activeMacroFocusSupported) {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_MACRO,
                )
            }
            FocusMode.MANUAL -> if (activeManualFocusSupported) {
                builder
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    .setCaptureRequestOption(
                        CaptureRequest.LENS_FOCUS_DISTANCE,
                        FocusMath.normalizedToDiopters(manualPosition, activeMinimumFocusDistance),
                    )
            }
            FocusMode.INFINITY -> if (activeInfinityFocusSupported) {
                builder
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
            }
            FocusMode.FIXED -> Unit
        }
    }

    private fun effectiveFocusMode(requested: FocusMode): FocusMode {
        if (!focusCapabilitiesKnown) return requested
        val supported = when (requested) {
            FocusMode.CONTINUOUS -> activeContinuousFocusSupported
            FocusMode.TAP_LOCK -> activeTapFocusSupported
            FocusMode.MACRO -> activeMacroFocusSupported
            FocusMode.MANUAL -> activeManualFocusSupported
            FocusMode.INFINITY -> activeInfinityFocusSupported
            FocusMode.FIXED -> !activeTapFocusSupported && !activeManualFocusSupported
        }
        if (supported) return requested
        return when {
            activeContinuousFocusSupported -> FocusMode.CONTINUOUS
            activeTapFocusSupported -> FocusMode.TAP_LOCK
            activeMacroFocusSupported -> FocusMode.MACRO
            activeManualFocusSupported -> FocusMode.MANUAL
            activeInfinityFocusSupported -> FocusMode.INFINITY
            else -> FocusMode.FIXED
        }
    }

    private fun focusDistanceForBracket(): Float? {
        if (!activeManualFocusSupported) return null
        return when (activeFocusMode) {
            FocusMode.MANUAL -> FocusMath.normalizedToDiopters(
                activeManualFocusPosition,
                activeMinimumFocusDistance,
            )
            FocusMode.INFINITY -> 0f
            FocusMode.FIXED -> null
            FocusMode.CONTINUOUS,
            FocusMode.TAP_LOCK,
            FocusMode.MACRO,
            -> latestCaptureResult.get()
                ?.get(CaptureResult.LENS_FOCUS_DISTANCE)
                ?.coerceIn(0f, activeMinimumFocusDistance)
        }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private suspend fun applyAutoExposureFocusHoldAndAwait(
        focusDistance: Float,
        whiteBalancePreset: WhiteBalancePreset,
        lockAwb: Boolean,
    ) {
        val activeCamera = camera ?: throw IllegalStateException("Camera closed during HDR focus hold")
        val builder = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            .setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                focusDistance.coerceIn(0f, activeMinimumFocusDistance),
            )
        appendWhiteBalanceOptions(builder, whiteBalancePreset, lockAutoAwb = lockAwb)
        Camera2CameraControl.from(activeCamera.cameraControl)
            .setCaptureRequestOptions(builder.build())
            .await()
    }

    private suspend fun captureJpegBitmap(capture: ImageCapture): Bitmap =
        suspendCancellableCoroutine { continuation ->
            capture.takePicture(
                captureExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            if (!continuation.isActive) return
                            continuation.resume(capturedImageToBitmap(image))
                        } catch (t: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(t)
                        } finally {
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (continuation.isActive) continuation.resumeWithException(exception)
                    }
                },
            )
        }

    private suspend fun captureRawSensor(
        capture: ImageCapture,
        plannedEvOffset: Float,
        saveDng: Boolean,
        bracketIndex: Int,
    ): RawSensorFrame = suspendCancellableCoroutine { continuation ->
        capture.takePicture(
            captureExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    var dngFile: File? = null
                    try {
                        if (!continuation.isActive) return
                        if (image.format != ImageFormat.RAW_SENSOR) {
                            throw IllegalStateException("Expected RAW_SENSOR, received format ${image.format}")
                        }
                        val result = waitForCaptureResult(image.imageInfo.timestamp)
                            ?: throw IllegalStateException("RAW capture metadata did not arrive")
                        val characteristics = activeCharacteristics
                            ?: throw IllegalStateException("RAW camera characteristics unavailable")
                        if (saveDng) {
                            dngFile = writeTemporaryDng(image, characteristics, result, bracketIndex)
                        }
                        continuation.resume(
                            copyRawSensorFrame(
                                image = image,
                                result = result,
                                plannedEvOffset = plannedEvOffset,
                                dngFile = dngFile,
                            ),
                        )
                    } catch (t: Throwable) {
                        dngFile?.delete()
                        if (continuation.isActive) continuation.resumeWithException(t)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    if (continuation.isActive) continuation.resumeWithException(exception)
                }
            },
        )
    }

    private fun waitForCaptureResult(timestamp: Long): TotalCaptureResult? {
        captureResultsByTimestamp.remove(timestamp)?.let { return it }
        repeat(40) {
            Thread.sleep(5)
            captureResultsByTimestamp.remove(timestamp)?.let { return it }
        }
        return latestCaptureResult.get()
    }

    private fun writeTemporaryDng(
        proxy: ImageProxy,
        characteristics: CameraCharacteristics,
        result: TotalCaptureResult,
        bracketIndex: Int,
    ): File? = runCatching {
        val mediaImage = proxy.image ?: return@runCatching null
        val directory = File(appContext.cacheDir, "spectral_raw_hdr").apply {
            if (!exists() && !mkdirs()) error("Unable to create RAW HDR cache")
        }
        val file = File(directory, "raw_hdr_${System.currentTimeMillis()}_${bracketIndex}.dng")
        file.outputStream().use { output ->
            DngCreator(characteristics, result).use { creator -> creator.writeImage(output, mediaImage) }
        }
        file
    }.getOrNull()

    private fun copyRawSensorFrame(
        image: ImageProxy,
        result: TotalCaptureResult,
        plannedEvOffset: Float,
        dngFile: File?,
    ): RawSensorFrame {
        val characteristics = activeCharacteristics
            ?: throw IllegalStateException("RAW camera characteristics unavailable")
        val cfaValue = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: throw IllegalStateException("RAW CFA arrangement unavailable")
        val arrangement = BayerArrangement.fromCameraValue(cfaValue)
            ?: throw IllegalStateException("Only four-channel Bayer RAW is supported")
        val staticBlack = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            ?: throw IllegalStateException("RAW black level unavailable")
        val staticBlackValues = IntArray(4).also { staticBlack.copyTo(it, 0) }
        val dynamicBlack = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        val blackByParity = FloatArray(4) { parity ->
            val x = parity and 1
            val y = parity ushr 1
            val channel = RawHdrMath.channelAt(arrangement, x, y)
            if (dynamicBlack != null && dynamicBlack.size >= 4) {
                dynamicBlack[channelResultIndex(channel)]
            } else {
                staticBlackValues[parity].toFloat()
            }
        }
        val whiteLevel = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.toFloat()
            ?: throw IllegalStateException("RAW white level unavailable")
        val gainsVector = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            ?: latestCaptureResult.get()?.get(CaptureResult.COLOR_CORRECTION_GAINS)
            ?: throw IllegalStateException("RAW white-balance gains unavailable")
        val gains = FloatArray(4).also { gainsVector.copyTo(it, 0) }
        val transformValue = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
            ?: latestCaptureResult.get()?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
            ?: throw IllegalStateException("RAW sensor-to-linear-sRGB transform unavailable")
        val transform = FloatArray(9) { index ->
            val row = index / 3
            val column = index % 3
            transformValue.getElement(column, row).toFloat()
        }
        val exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            ?: throw IllegalStateException("RAW exposure time unavailable")
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            ?: throw IllegalStateException("RAW sensitivity unavailable")
        val crop = image.cropRect
        val plane = image.planes.firstOrNull()
            ?: throw IllegalStateException("RAW image has no plane")
        val source = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
        val pixels = ShortArray(image.width * image.height)
        for (y in 0 until image.height) {
            val rowStart = y * plane.rowStride
            for (x in 0 until image.width) {
                val offset = rowStart + x * plane.pixelStride
                if (offset + 1 >= source.capacity()) {
                    throw IllegalStateException("RAW plane stride exceeds buffer")
                }
                pixels[y * image.width + x] = source.getShort(offset)
            }
        }
        val actualEv = latestCaptureResult.get()?.let { latest ->
            val latestExposure = latest.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val latestIso = latest.get(CaptureResult.SENSOR_SENSITIVITY)
            if (latestExposure != null && latestIso != null) {
                log2(
                    RawHdrMath.exposureProduct(exposureTime, iso) /
                        RawHdrMath.exposureProduct(latestExposure, latestIso),
                ).toFloat()
            } else {
                plannedEvOffset
            }
        } ?: plannedEvOffset
        return RawSensorFrame(
            width = image.width,
            height = image.height,
            pixels = pixels,
            cropLeft = crop.left.coerceIn(0, image.width - 1),
            cropTop = crop.top.coerceIn(0, image.height - 1),
            cropWidth = crop.width().coerceIn(1, image.width - crop.left.coerceAtLeast(0)),
            cropHeight = crop.height().coerceIn(1, image.height - crop.top.coerceAtLeast(0)),
            cfaArrangement = cfaValue,
            blackLevels = blackByParity,
            whiteLevel = whiteLevel,
            exposureTimeNs = exposureTime,
            sensitivityIso = iso,
            whiteBalanceGains = gains,
            colorTransform = transform,
            rotationDegrees = image.imageInfo.rotationDegrees,
            timestampNs = image.imageInfo.timestamp,
            evOffset = actualEv,
            dngFile = dngFile,
        )
    }

    private fun channelResultIndex(channel: BayerChannel): Int = when (channel) {
        BayerChannel.RED -> 0
        BayerChannel.GREEN_EVEN -> 1
        BayerChannel.GREEN_ODD -> 2
        BayerChannel.BLUE -> 3
    }

    private suspend fun captureRawJpeg(capture: ImageCapture): CapturedFrame =
        suspendCancellableCoroutine { continuation ->
            val directory = File(appContext.cacheDir, "spectral_raw_capture").apply {
                if (!exists() && !mkdirs()) {
                    continuation.resumeWithException(IllegalStateException("Unable to create RAW cache"))
                    return@suspendCancellableCoroutine
                }
            }
            val token = "${System.currentTimeMillis()}_${System.nanoTime()}"
            val rawFile = File(directory, "capture_$token.dng")
            val jpegFile = File(directory, "capture_$token.jpg")
            val rawOptions = ImageCapture.OutputFileOptions.Builder(rawFile).build()
            val jpegOptions = ImageCapture.OutputFileOptions.Builder(jpegFile).build()
            val remaining = AtomicInteger(2)
            val completed = AtomicBoolean(false)

            fun cleanAll() {
                rawFile.delete()
                jpegFile.delete()
            }

            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) cleanAll()
            }

            capture.takePicture(
                rawOptions,
                jpegOptions,
                captureExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        if (remaining.decrementAndGet() != 0 || !completed.compareAndSet(false, true)) return
                        try {
                            val bytes = jpegFile.readBytes()
                            val decoded = decodeCompressedImage(bytes)
                            val bitmap = rotateBitmap(decoded, jpegRotationDegrees(bytes, 0))
                            jpegFile.delete()
                            if (continuation.isActive) {
                                continuation.resume(
                                    CapturedFrame(
                                        exposures = listOf(CapturedExposure(bitmap, 0f)),
                                        rawSidecarFile = rawFile,
                                    ),
                                )
                            } else {
                                bitmap.recycle()
                                rawFile.delete()
                            }
                        } catch (t: Throwable) {
                            cleanAll()
                            if (continuation.isActive) continuation.resumeWithException(t)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (!completed.compareAndSet(false, true)) return
                        cleanAll()
                        if (continuation.isActive) continuation.resumeWithException(exception)
                    }
                },
            )
        }

    fun release() {
        pendingRequest?.willNotProvideSurface()
        pendingRequest = null
        analyzerExecutor.shutdownNow()
        captureExecutor.shutdownNow()
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun bindUseCases(provider: ProcessCameraProvider, lifecycleOwner: LifecycleOwner) {
        provider.unbindAll()
        captureResultsByTimestamp.clear()
        latestCaptureResult.set(null)
        focusCapabilitiesKnown = false
        val targetRotation = glView?.display?.rotation ?: Surface.ROTATION_0
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val cameraInfo = provider.getCameraInfo(selector)
        val camera2Info = Camera2CameraInfo.from(cameraInfo)
        activeCharacteristics = runCatching {
            cameraManager.getCameraCharacteristics(camera2Info.cameraId)
        }.getOrNull()
        val advertisedFormats = runCatching {
            ImageCapture.getImageCaptureCapabilities(cameraInfo).supportedOutputFormats
        }.getOrDefault(setOf(ImageCapture.OUTPUT_FORMAT_JPEG))
        rawJpegUsable = advertisedFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
        rawCaptureUsable = advertisedFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW) || rawJpegUsable

        val previewUseCase = buildPreview(targetRotation)
        preview = previewUseCase
        val staticRawSupport = rawStaticMetadataSupported(activeCharacteristics)
        val manualSupport = characteristicsManualSensorSupported(activeCharacteristics)
        var enableRawHdr = hdrCaptureMode == HdrCaptureMode.RAW_THREE_FRAME &&
            rawCaptureUsable && staticRawSupport && manualSupport
        var enableRawSidecar = rawSidecarRequested && rawJpegUsable && hdrCaptureMode == HdrCaptureMode.OFF
        var captureUseCase = buildImageCapture(
            targetRotation,
            outputMode,
            hdrCaptureMode,
            enableRawSidecar,
            enableRawHdr,
        )

        camera = try {
            bindPreferred(provider, lifecycleOwner, selector, previewUseCase, captureUseCase)
        } catch (rawConfigurationError: Exception) {
            if (!enableRawSidecar && !enableRawHdr) throw rawConfigurationError
            provider.unbindAll()
            enableRawSidecar = false
            enableRawHdr = false
            if (hdrCaptureMode == HdrCaptureMode.RAW_THREE_FRAME) rawCaptureUsable = false
            else rawJpegUsable = false
            captureUseCase = buildImageCapture(
                targetRotation,
                outputMode,
                HdrCaptureMode.OFF,
                enableRaw = false,
                enableRawHdr = false,
            )
            bindPreferred(provider, lifecycleOwner, selector, previewUseCase, captureUseCase)
        }

        rawJpegActive = enableRawSidecar
        rawHdrActive = enableRawHdr
        imageCapture = captureUseCase
        sourceResolution?.let { resolution ->
            sourceRotation = currentRelativeRotation()
            glView?.configureSource(resolution.width, resolution.height, sourceRotation)
        }
        updateCapabilities()
    }

    private fun buildPreview(targetRotation: Int): Preview = Preview.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                )
                .build(),
        )
        .setTargetRotation(targetRotation)
        .build()
        .also { it.setSurfaceProvider(surfaceProvider) }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun buildImageCapture(
        targetRotation: Int,
        mode: OutputMode,
        hdrMode: HdrCaptureMode,
        enableRaw: Boolean,
        enableRawHdr: Boolean,
    ): ImageCapture {
        val useFastSource = mode == OutputMode.FAST_1080 && !enableRaw && !enableRawHdr
        val resolutionSelector = when {
            useFastSource -> ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                )
                .build()
            hdrMode != HdrCaptureMode.OFF -> ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(4032, 3024),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                )
                .build()
            else -> ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(8160, 6144),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                )
                .build()
        }

        val builder = ImageCapture.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(targetRotation)
            .setCaptureMode(
                if (useFastSource) ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                else ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY,
            )
            .setJpegQuality(if (useFastSource) 95 else 100)
        Camera2Interop.Extender(builder).setSessionCaptureCallback(metadataCallback)
        when {
            enableRawHdr -> builder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW)
            enableRaw -> builder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
        }
        return builder.build()
    }

    private fun bindPreferred(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        selector: CameraSelector,
        previewUseCase: Preview,
        captureUseCase: ImageCapture,
    ): Camera = try {
        provider.bindToLifecycle(
            lifecycleOwner,
            selector,
            previewUseCase,
            captureUseCase,
            buildAnalysis(previewUseCase.targetRotation),
        )
    } catch (_: Exception) {
        provider.unbindAll()
        previewUseCase.setSurfaceProvider(surfaceProvider)
        provider.bindToLifecycle(lifecycleOwner, selector, previewUseCase, captureUseCase)
    }

    private fun buildAnalysis(targetRotation: Int): ImageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(320, 240),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                )
                .build(),
        )
        .setTargetRotation(targetRotation)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .build()
        .also { useCase ->
            useCase.setAnalyzer(analyzerExecutor) { proxy ->
                try {
                    if (analysisEnabled) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastAnalysisFrameAt >= 150L) {
                            lastAnalysisFrameAt = now
                            onAnalysisFrame?.invoke(rgbaProxyToBitmap(proxy))
                        }
                    }
                } finally {
                    proxy.close()
                }
            }
        }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun updateCapabilities() {
        val activeCamera = camera ?: return
        val info = activeCamera.cameraInfo
        val exposureRange = info.exposureState.exposureCompensationRange
        val stepRational = info.exposureState.exposureCompensationStep
        val exposureStep = if (stepRational.denominator != 0) {
            stepRational.numerator.toFloat() / stepRational.denominator.toFloat()
        } else {
            1f / 3f
        }
        val zoomState = info.zoomState.value
        var aperture: Float? = null
        var isoRange: IntRange? = null
        var exposureTimeRange: LongRange? = null
        var manualExposureSupported = false
        var minimumFocusDistance = 0f
        var availableAfModes: Set<Int> = emptySet()
        var whiteBalanceSupport = WhiteBalanceSupport()
        var focusCalibration = FocusDistanceCalibration.UNCALIBRATED
        activeAwbLockSupported = false
        try {
            val camera2Info = Camera2CameraInfo.from(info)
            aperture = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES,
            )?.firstOrNull()
            val iso = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE,
            )
            if (iso != null) isoRange = iso.lower..iso.upper
            val expTime = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE,
            )
            if (expTime != null) exposureTimeRange = expTime.lower..expTime.upper
            val caps = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
            )
            manualExposureSupported = caps?.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
            ) == true
            activeAwbLockSupported = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE,
            ) == true
            val availableAwbModes = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES,
            )?.toSet().orEmpty()
            val fixedWhiteBalanceModes = availableAwbModes.mapNotNull(::fixedWhiteBalanceMode).toSet()
            val cctSupport = if (
                Build.VERSION.SDK_INT >= 36 &&
                CameraMetadata.CONTROL_AWB_MODE_OFF in availableAwbModes
            ) {
                readCctSupport(camera2Info)
            } else {
                CctSupport()
            }
            whiteBalanceSupport = WhiteBalanceSupport(
                cctRange = cctSupport.range,
                fixedModes = fixedWhiteBalanceModes,
            )
            minimumFocusDistance = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
            ) ?: 0f
            availableAfModes = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
            )?.toSet().orEmpty()
            focusCalibration = when (
                camera2Info.getCameraCharacteristic(
                    CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION,
                )
            ) {
                CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED ->
                    FocusDistanceCalibration.CALIBRATED
                CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE ->
                    FocusDistanceCalibration.APPROXIMATE
                else -> FocusDistanceCalibration.UNCALIBRATED
            }
        } catch (_: Exception) {
            Unit
        }

        activeExposureRange = exposureRange.lower..exposureRange.upper
        activeExposureStep = exposureStep
        activeIsoRange = isoRange
        activeExposureTimeRange = exposureTimeRange
        activeManualExposureSupported = manualExposureSupported
        activeWhiteBalanceSupport = whiteBalanceSupport
        activeMinimumFocusDistance = minimumFocusDistance.coerceAtLeast(0f)
        activeAfModes = availableAfModes
        activeContinuousFocusSupported =
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in availableAfModes ||
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in availableAfModes
        activeContinuousAfMode = if (
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in availableAfModes
        ) {
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        } else {
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        }
        activeMacroFocusSupported =
            minimumFocusDistance > 0f && CameraMetadata.CONTROL_AF_MODE_MACRO in availableAfModes
        activeTapFocusSupported = minimumFocusDistance > 0f && availableAfModes.any { mode ->
            mode == CameraMetadata.CONTROL_AF_MODE_AUTO ||
                mode == CameraMetadata.CONTROL_AF_MODE_MACRO ||
                mode == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE ||
                mode == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        }
        activeManualFocusSupported = minimumFocusDistance > 0f &&
            CameraMetadata.CONTROL_AF_MODE_OFF in availableAfModes
        activeInfinityFocusSupported = activeManualFocusSupported
        focusCapabilitiesKnown = true
        activeFocusMode = effectiveFocusMode(activeFocusMode)
        val autoBracketSupported = exposureRange.upper - exposureRange.lower >= 2
        val manualBracketSupported = manualExposureSupported && exposureTimeRange != null &&
            exposureTimeRange.upper > exposureTimeRange.lower
        val trueRawSupported = rawCaptureUsable && manualExposureSupported &&
            rawStaticMetadataSupported(activeCharacteristics)

        onCapabilities?.invoke(
            CameraCapabilities(
                hasFlash = info.hasFlashUnit(),
                canFocus = activeContinuousFocusSupported || activeTapFocusSupported ||
                    activeMacroFocusSupported || activeManualFocusSupported,
                exposureRange = activeExposureRange,
                exposureStep = exposureStep,
                zoomRange = 1f..(zoomState?.maxZoomRatio ?: 1f),
                aperture = aperture,
                isoRange = isoRange,
                exposureTimeRange = exposureTimeRange,
                manualExposureSupported = manualExposureSupported,
                rawJpegCaptureSupported = rawJpegUsable,
                hdrBracketSupported = autoBracketSupported || manualBracketSupported,
                trueRawHdrSupported = trueRawSupported,
                supportedWhiteBalancePresets = WhiteBalanceRequestPlanner.supportedPresets(
                    activeWhiteBalanceSupport,
                ),
                directKelvinWhiteBalancePresets = WhiteBalanceRequestPlanner.directCctPresets(
                    activeWhiteBalanceSupport,
                ),
                continuousFocusSupported = activeContinuousFocusSupported,
                tapFocusSupported = activeTapFocusSupported,
                macroFocusSupported = activeMacroFocusSupported,
                manualFocusSupported = activeManualFocusSupported,
                infinityFocusSupported = activeInfinityFocusSupported,
                minimumFocusDistanceDiopters = activeMinimumFocusDistance,
                focusDistanceCalibration = focusCalibration,
            ),
        )
    }

    private data class CctSupport(
        val range: IntRange? = null,
    )

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    @androidx.annotation.RequiresApi(36)
    private fun readCctSupport(camera2Info: Camera2CameraInfo): CctSupport {
        val modes = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES,
        )?.toSet().orEmpty()
        val temperatureRange = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE,
        )
        val cctSupported = CameraMetadata.COLOR_CORRECTION_MODE_CCT in modes &&
            temperatureRange != null
        return CctSupport(
            range = if (cctSupported) temperatureRange!!.lower..temperatureRange.upper else null,
        )
    }

    private fun fixedWhiteBalanceMode(cameraMode: Int): FixedWhiteBalanceMode? = when (cameraMode) {
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> FixedWhiteBalanceMode.INCANDESCENT
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> FixedWhiteBalanceMode.FLUORESCENT
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> FixedWhiteBalanceMode.WARM_FLUORESCENT
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> FixedWhiteBalanceMode.DAYLIGHT
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> FixedWhiteBalanceMode.CLOUDY_DAYLIGHT
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> FixedWhiteBalanceMode.TWILIGHT
        CameraMetadata.CONTROL_AWB_MODE_SHADE -> FixedWhiteBalanceMode.SHADE
        else -> null
    }

    private fun characteristicsManualSensorSupported(characteristics: CameraCharacteristics?): Boolean =
        characteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
        ) == true

    private fun rawStaticMetadataSupported(characteristics: CameraCharacteristics?): Boolean {
        val cfa = characteristics?.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        return cfa != null && BayerArrangement.fromCameraValue(cfa) != null &&
            characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN) != null &&
            characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) != null
    }

    private fun capturedImageToBitmap(image: ImageProxy): Bitmap {
        val bytes = decodeCompressedImageBytes(image)
        val decoded = decodeCompressedImage(bytes)
        return rotateBitmap(decoded, jpegRotationDegrees(bytes, image.imageInfo.rotationDegrees))
    }

    private fun decodeCompressedImageBytes(image: ImageProxy): ByteArray {
        val buffer = image.planes.firstOrNull()?.buffer
            ?: throw IllegalStateException("Captured image has no data plane")
        buffer.rewind()
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun decodeCompressedImage(bytes: ByteArray): Bitmap {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalStateException("Failed to decode captured image")
    }

    private fun jpegRotationDegrees(bytes: ByteArray, fallbackDegrees: Int): Int =
        runCatching { ExifInterface(ByteArrayInputStream(bytes)).rotationDegreesOrNull() }
            .getOrNull() ?: fallbackDegrees

    private fun ExifInterface.rotationDegreesOrNull(): Int =
        when (getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    private fun rgbaProxyToBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        buffer.rewind()
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowBytes = width * pixelStride
        if (rowStride == rowBytes) {
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                it.copyPixelsFromBuffer(buffer)
            }
        }
        val packed = ByteBuffer.allocateDirect(rowBytes * height)
        val row = ByteArray(rowBytes)
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            val toRead = minOf(rowBytes, buffer.remaining())
            buffer.get(row, 0, toRead)
            packed.put(row, 0, rowBytes)
        }
        packed.rewind()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.copyPixelsFromBuffer(packed)
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val isFront = lensFacing == CameraSelector.LENS_FACING_FRONT
        if (rotationDegrees == 0 && !isFront) return bitmap
        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
            if (isFront) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }
}

package com.renardoberou.spectralcamera.core.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DisplayOrientedMeteringPointFactory
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
import com.renardoberou.spectralcamera.core.OutputMode
import com.renardoberou.spectralcamera.core.gl.SpectralGlView
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the CameraX session. The live preview is delivered straight into the GPU
 * pipeline ([SpectralGlView]) through a SurfaceTexture, so no per-frame work
 * happens on the CPU. A tiny RGBA analysis stream is kept only for the
 * hardware-test screen and is gated by [setAnalysisEnabled].
 *
 * JPEG-only captures use the in-memory path. When the selected camera reports
 * CameraX RAW+JPEG support and the user requests a sidecar, capture switches to
 * the dual-file API: the companion JPEG still feeds the existing film renderer,
 * while the untouched DNG is returned for durable MediaStore storage.
 */
class CameraController(context: Context) {
    private val appContext = context.applicationContext
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
    private var rawSidecarRequested: Boolean = false

    @Volatile
    private var rawJpegActive: Boolean = false

    @Volatile
    private var rawJpegUsable: Boolean = false

    @Volatile
    private var analysisEnabled: Boolean = false

    @Volatile
    private var lastAnalysisFrameAt = 0L

    // Preview surface plumbing. All of these are touched on the main thread only.
    private var surfaceTexture: SurfaceTexture? = null
    private var pendingRequest: SurfaceRequest? = null
    private var sourceRotation = 0
    private var sourceResolution: Size? = null

    private val surfaceProvider = Preview.SurfaceProvider { request ->
        // Supersede any unanswered request from a previous configuration.
        pendingRequest?.willNotProvideSurface()
        pendingRequest = request
        tryFulfillRequest()
    }

    /** Called whenever the GL surface has a fresh SurfaceTexture for CameraX. */
    fun onSurfaceTextureAvailable(texture: SurfaceTexture) {
        surfaceTexture = texture
        if (pendingRequest != null) {
            tryFulfillRequest()
        } else {
            // The camera may be streaming into a surface that died with the old GL
            // context. Force CameraX to issue a new SurfaceRequest.
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
        } catch (t: Exception) {
            sourceRotation
        }
    }

    fun bind(
        lifecycleOwner: LifecycleOwner,
        glView: SpectralGlView,
        lensFacing: Int,
        outputMode: OutputMode,
        rawSidecarRequested: Boolean,
        onCapabilities: (CameraCapabilities) -> Unit,
        onAnalysisFrame: (Bitmap) -> Unit,
    ) {
        this.glView = glView
        this.lensFacing = lensFacing
        this.outputMode = outputMode
        this.rawSidecarRequested = rawSidecarRequested
        this.onCapabilities = onCapabilities
        this.onAnalysisFrame = onAnalysisFrame

        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener({
            val provider = providerFuture.get()
            bindUseCases(provider, lifecycleOwner)
        }, mainExecutor)
    }

    fun setTorch(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
    }

    fun setExposureCompensation(index: Int) {
        camera?.cameraControl?.setExposureCompensationIndex(index)
    }

    /** Full-manual exposure via Camera2 interop. */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun setManualExposure(enabled: Boolean, iso: Int, shutterNs: Long) {
        val control = camera?.cameraControl ?: return
        val camera2 = Camera2CameraControl.from(control)
        if (!enabled) {
            camera2.clearCaptureRequestOptions()
            return
        }
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CameraMetadata.CONTROL_AE_MODE_OFF,
            )
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs)
            .build()
        camera2.setCaptureRequestOptions(options)
    }

    fun setAnalysisEnabled(enabled: Boolean) {
        analysisEnabled = enabled
    }

    /** Tap-to-focus in fill-cropped preview coordinates. */
    fun focusAt(x: Float, y: Float, viewWidth: Float, viewHeight: Float) {
        val camera = camera ?: return
        val display = glView?.display ?: return
        if (viewWidth <= 0f || viewHeight <= 0f) return

        val resolution = sourceResolution
        val rotated = sourceRotation == 90 || sourceRotation == 270
        val contentW = (if (rotated) resolution?.height else resolution?.width)?.toFloat() ?: viewWidth
        val contentH = (if (rotated) resolution?.width else resolution?.height)?.toFloat() ?: viewHeight
        if (contentW <= 0f || contentH <= 0f) return
        val scale = maxOf(viewWidth / contentW, viewHeight / contentH)
        val fullW = contentW * scale
        val fullH = contentH * scale

        val factory = DisplayOrientedMeteringPointFactory(display, camera.cameraInfo, fullW, fullH)
        val point = factory.createPoint(x + (fullW - viewWidth) / 2f, y + (fullH - viewHeight) / 2f)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .build()
        camera.cameraControl.startFocusAndMetering(action)
    }

    /** Captures the JPEG render source plus an optional untouched DNG sidecar. */
    suspend fun capture(): CapturedFrame = captureMutex.withLock {
        val capture = imageCapture ?: throw IllegalStateException("Camera not ready")
        if (rawJpegActive) captureRawJpeg(capture) else captureJpeg(capture)
    }

    private suspend fun captureJpeg(capture: ImageCapture): CapturedFrame =
        suspendCancellableCoroutine { continuation ->
            capture.takePicture(
                captureExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            if (!continuation.isActive) return
                            continuation.resume(CapturedFrame(capturedImageToBitmap(image)))
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

    private suspend fun captureRawJpeg(capture: ImageCapture): CapturedFrame =
        suspendCancellableCoroutine { continuation ->
            val directory = File(appContext.cacheDir, "spectral_raw_capture").apply {
                if (!exists() && !mkdirs()) {
                    continuation.resumeWithException(IllegalStateException("Unable to create RAW capture cache"))
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
                                continuation.resume(CapturedFrame(bitmap, rawFile))
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

    private fun bindUseCases(provider: ProcessCameraProvider, lifecycleOwner: LifecycleOwner) {
        provider.unbindAll()

        val targetRotation = glView?.display?.rotation ?: Surface.ROTATION_0
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val cameraInfo = provider.getCameraInfo(selector)
        val advertisedFormats = runCatching {
            ImageCapture.getImageCaptureCapabilities(cameraInfo).supportedOutputFormats
        }.getOrDefault(setOf(ImageCapture.OUTPUT_FORMAT_JPEG))
        rawJpegUsable = advertisedFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)

        val previewUseCase = buildPreview(targetRotation)
        preview = previewUseCase

        var enableRaw = rawSidecarRequested && rawJpegUsable
        var captureUseCase = buildImageCapture(targetRotation, outputMode, enableRaw)

        camera = try {
            bindPreferred(provider, lifecycleOwner, selector, previewUseCase, captureUseCase)
        } catch (rawConfigurationError: Exception) {
            if (!enableRaw) throw rawConfigurationError
            // Some cameras advertise RAW+JPEG but cannot sustain it beside this
            // preview stream. Fall back to the normal JPEG session rather than
            // leaving the camera unusable; capability UI is updated accordingly.
            provider.unbindAll()
            enableRaw = false
            rawJpegUsable = false
            captureUseCase = buildImageCapture(targetRotation, outputMode, enableRaw = false)
            bindPreferred(provider, lifecycleOwner, selector, previewUseCase, captureUseCase)
        }

        rawJpegActive = enableRaw
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

    private fun buildImageCapture(
        targetRotation: Int,
        mode: OutputMode,
        enableRaw: Boolean,
    ): ImageCapture {
        val useFastSource = mode == OutputMode.FAST_1080 && !enableRaw
        val resolutionSelector = if (useFastSource) {
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                )
                .build()
        } else {
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    // Ask for a full-resolution sensor stream. The HAL can fall
                    // back to its highest binned size where unbinned output is absent.
                    ResolutionStrategy(
                        Size(8160, 6144),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                )
                .build()
        }

        return ImageCapture.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(targetRotation)
            .setCaptureMode(
                if (useFastSource) ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                else ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY,
            )
            .setJpegQuality(if (useFastSource) 95 else 100)
            .apply {
                if (enableRaw) setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
            }
            .build()
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
    } catch (threeUseCaseError: Exception) {
        // Some LEGACY-level devices refuse three concurrent streams. Analysis
        // only powers the hardware-test screen, so capture quality wins.
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
        val camera = camera ?: return
        val info = camera.cameraInfo
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
        try {
            val camera2Info = Camera2CameraInfo.from(info)
            aperture = camera2Info
                .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.firstOrNull()
            val iso = camera2Info
                .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            if (iso != null) isoRange = iso.lower..iso.upper
            val expTime = camera2Info
                .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            if (expTime != null) exposureTimeRange = expTime.lower..expTime.upper
            val caps = camera2Info
                .getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            manualExposureSupported = caps?.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
            ) == true
        } catch (t: Exception) {
            // Interop is best-effort; display-only data.
        }
        onCapabilities?.invoke(
            CameraCapabilities(
                hasFlash = info.hasFlashUnit(),
                canFocus = true,
                exposureRange = exposureRange.lower..exposureRange.upper,
                exposureStep = exposureStep,
                zoomRange = 1f..(zoomState?.maxZoomRatio ?: 1f),
                aperture = aperture,
                isoRange = isoRange,
                exposureTimeRange = exposureTimeRange,
                manualExposureSupported = manualExposureSupported,
                rawJpegCaptureSupported = rawJpegUsable,
            ),
        )
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
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalStateException("Failed to decode captured image")
    }

    private fun jpegRotationDegrees(bytes: ByteArray, fallbackDegrees: Int): Int =
        runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).rotationDegreesOrNull()
        }.getOrNull() ?: fallbackDegrees

    private fun ExifInterface.rotationDegreesOrNull(): Int =
        when (getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    /** Converts an RGBA_8888 ImageProxy to a Bitmap, honouring row stride. */
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
            // Saved front-camera files are unmirrored so text reads correctly.
            if (isFront) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }
}

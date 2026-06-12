package com.renardoberou.spectralcamera.core.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.util.Size
import android.view.Surface
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
import androidx.lifecycle.LifecycleOwner
import com.renardoberou.spectralcamera.core.CameraCapabilities
import com.renardoberou.spectralcamera.core.gl.SpectralGlView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns the CameraX session. The live preview is delivered straight into the GPU
 * pipeline ([SpectralGlView]) through a SurfaceTexture, so no per-frame work
 * happens on the CPU. A tiny RGBA analysis stream is kept only for the
 * hardware-test screen and is gated by [setAnalysisEnabled].
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

    /**
     * Called (on the main thread) whenever the GL surface is created or recreated
     * and a fresh SurfaceTexture is ready to receive camera frames.
     */
    fun onSurfaceTextureAvailable(texture: SurfaceTexture) {
        surfaceTexture = texture
        if (pendingRequest != null) {
            tryFulfillRequest()
        } else {
            // The camera may be streaming into a surface that died with the old GL
            // context. Detach then re-attach the provider to guarantee CameraX
            // issues a fresh SurfaceRequest for the new texture.
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

        // Seed the renderer with the best synchronous estimate we have, then
        // let CameraX overwrite it with the authoritative transformation info
        // as soon as that becomes available.
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

    /**
     * Rotation needed to display the sensor buffer upright on the current display,
     * fetched synchronously (no callback timing involved).
     */
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
        onCapabilities: (CameraCapabilities) -> Unit,
        onAnalysisFrame: (Bitmap) -> Unit,
    ) {
        this.glView = glView
        this.lensFacing = lensFacing
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

    fun setAnalysisEnabled(enabled: Boolean) {
        analysisEnabled = enabled
    }

    /**
     * Tap-to-focus. (x, y) are coordinates inside the on-screen preview of size
     * viewWidth x viewHeight. The preview fill-crops the camera buffer, so the tap
     * space is expanded to the uncropped content rect before mapping to the sensor.
     */
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

    /** Captures a full-resolution still and returns it as an upright bitmap. */
    suspend fun capture(): Bitmap = captureMutex.withLock {
        suspendCancellableCoroutine { continuation ->
            val capture = imageCapture
            if (capture == null) {
                continuation.resumeWithException(IllegalStateException("Camera not ready"))
                return@suspendCancellableCoroutine
            }

            capture.takePicture(
                captureExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            if (!continuation.isActive) return
                            val bitmap = capturedImageToBitmap(image)
                            continuation.resume(bitmap)
                        } catch (t: Throwable) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(t)
                            }
                        } finally {
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(exception)
                        }
                    }
                },
            )
        }
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

        val previewUseCase = Preview.Builder()
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
        preview = previewUseCase

        val captureUseCase = ImageCapture.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(4000, 3000),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .setTargetRotation(targetRotation)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(95)
            .build()

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        camera = try {
            provider.bindToLifecycle(lifecycleOwner, selector, previewUseCase, captureUseCase, buildAnalysis(targetRotation))
        } catch (t: Exception) {
            // Some LEGACY-level devices refuse three concurrent use cases. Drop the
            // analysis stream (only the hardware-test screen loses live data).
            provider.unbindAll()
            previewUseCase.setSurfaceProvider(surfaceProvider)
            provider.bindToLifecycle(lifecycleOwner, selector, previewUseCase, captureUseCase)
        }
        imageCapture = captureUseCase
        sourceResolution?.let { resolution ->
            sourceRotation = currentRelativeRotation()
            glView?.configureSource(resolution.width, resolution.height, sourceRotation)
        }
        updateCapabilities()
    }

    private fun buildAnalysis(targetRotation: Int): ImageAnalysis =
        ImageAnalysis.Builder()
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
                                val bitmap = rgbaProxyToBitmap(proxy)
                                onAnalysisFrame?.invoke(bitmap)
                            }
                        }
                    } finally {
                        proxy.close()
                    }
                }
            }

    private fun updateCapabilities() {
        val camera = camera ?: return
        val info = camera.cameraInfo
        val exposureRange = info.exposureState.exposureCompensationRange
        val zoomState = info.zoomState.value
        onCapabilities?.invoke(
            CameraCapabilities(
                hasFlash = info.hasFlashUnit(),
                canFocus = true,
                exposureRange = exposureRange.lower..exposureRange.upper,
                exposureStep = 1f,
                zoomRange = 1f..(zoomState?.maxZoomRatio ?: 1f),
            ),
        )
    }

    private fun capturedImageToBitmap(image: ImageProxy): Bitmap {
        val decoded = decodeCompressedImage(image)
        return rotateBitmap(decoded, image.imageInfo.rotationDegrees)
    }

    private fun decodeCompressedImage(image: ImageProxy): Bitmap {
        val buffer = image.planes.firstOrNull()?.buffer
            ?: throw IllegalStateException("Captured image has no data plane")
        buffer.rewind()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalStateException("Failed to decode captured image")
    }

    /** Converts an RGBA_8888 ImageProxy to a Bitmap, honouring the row stride. */
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
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap
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
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(packed)
        return bitmap
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}

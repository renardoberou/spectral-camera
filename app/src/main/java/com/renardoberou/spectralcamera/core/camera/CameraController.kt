package com.renardoberou.spectralcamera.core.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.renardoberou.spectralcamera.core.CameraCapabilities
import com.renardoberou.spectralcamera.core.CameraSettings
import com.renardoberou.spectralcamera.core.filters.SpectralFilterEngine
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraController(context: Context) {
    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val captureMutex = Mutex()
    private val filterEngine = SpectralFilterEngine()

    @Volatile
    private var currentSettings: CameraSettings = CameraSettings()
    private var previewView: PreviewView? = null
    private var onFrame: ((Bitmap) -> Unit)? = null
    private var onCapabilities: ((CameraCapabilities) -> Unit)? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    @Volatile
    private var analysisEnabled: Boolean = false
    @Volatile
    private var lastPreviewFrameAt = 0L

    fun updateSettings(settings: CameraSettings) {
        currentSettings = settings
    }

    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lensFacing: Int,
        settings: CameraSettings,
        onPreviewFrame: (Bitmap) -> Unit,
        onCapabilities: (CameraCapabilities) -> Unit,
    ) {
        this.previewView = previewView
        this.onFrame = onPreviewFrame
        this.onCapabilities = onCapabilities
        this.lensFacing = lensFacing
        currentSettings = settings

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

    fun focusAt(x: Float, y: Float) {
        val view = previewView ?: return
        val point = view.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

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
        analyzerExecutor.shutdownNow()
        captureExecutor.shutdownNow()
    }

    private fun bindUseCases(provider: ProcessCameraProvider, lifecycleOwner: LifecycleOwner) {
        val previewView = previewView ?: return
        provider.unbindAll()

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(Size(480, 270))
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { useCase ->
                useCase.setAnalyzer(analyzerExecutor) { proxy ->
                    try {
                        val shouldAnalyze = analysisEnabled && run {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastPreviewFrameAt < 60L) {
                                false
                            } else {
                                lastPreviewFrameAt = now
                                true
                            }
                        }
                        if (shouldAnalyze) {
                            val raw = imageProxyToBitmap(proxy)
                            val processed = filterEngine.processPreview(raw, currentSettings)
                            onFrame?.invoke(processed)
                        }
                    } finally {
                        proxy.close()
                    }
                }
            }

        val capture = ImageCapture.Builder()
            // Prefer a high-quality 4:3 still capture while keeping memory bounded.
            .setTargetResolution(Size(2560, 1920))
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis, capture)
        imageCapture = capture
        updateCapabilities()
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

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, 2560, 1920)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalStateException("Failed to decode captured image")
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        if (width <= reqWidth && height <= reqHeight) return 1
        var sample = 1
        while (width / sample > reqWidth || height / sample > reqHeight) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val bitmap = rgbaImageProxyToBitmap(image)
        return rotateBitmap(bitmap, image.imageInfo.rotationDegrees)
    }

    private fun rgbaImageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer: ByteBuffer = image.planes[0].buffer
        buffer.rewind()
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}

package com.renardoberou.spectralcamera.core.camera

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contract for the preview-only orientation boundary.
 *
 * CameraX owns the preview rotation decision through SurfaceRequest's
 * TransformationInfo. Camera-relative rotation is still valid for CameraX
 * use-case configuration, but must not be a second preview-geometry source.
 */
class PreviewOrientationContractTest {
    private val cameraController = File("src/main/java/com/renardoberou/spectralcamera/core/camera/CameraController.kt").readText()
    private val glPipeline = File("src/main/java/com/renardoberou/spectralcamera/core/gl/SpectralGlPipeline.kt").readText()

    @Test
    fun surfaceRequestTransformationInfoIsTheOnlyPreviewGeometryRotationSource() {
        val tryFulfillRequest = cameraController.substringBetween(
            "private fun tryFulfillRequest()",
            "private fun updateSourceGeometry",
        )
        val bindUseCases = cameraController.substringBetween(
            "private fun bindUseCases(",
            "private fun buildPreview(",
        )

        assertTrue(
            "SurfaceRequest TransformationInfo must feed preview geometry",
            tryFulfillRequest.contains("updateSourceGeometry(info.rotationDegrees)"),
        )
        assertFalse(
            "currentRelativeRotation must not seed preview geometry after SurfaceRequest setup",
            tryFulfillRequest.contains("updateSourceGeometry(currentRelativeRotation())"),
        )
        assertFalse(
            "bindUseCases must not overwrite SurfaceRequest preview geometry with currentRelativeRotation",
            bindUseCases.contains("sourceRotation = currentRelativeRotation()") ||
                bindUseCases.contains("configureSource(resolution.width, resolution.height, sourceRotation)"),
        )
    }

    @Test
    fun surfaceTextureTransformIsPassedThroughWithoutManualPreviewRotation() {
        val onDrawFrame = glPipeline.substringBetween(
            "override fun onDrawFrame(gl: GL10?)",
            "fun processBitmap(",
        )
        val computePreviewPosMatrix = glPipeline.substringBetween(
            "private fun computePreviewPosMatrix()",
            "private fun drawQuad(",
        )

        assertTrue(
            "SurfaceTexture must provide the preview texture transform",
            onDrawFrame.contains("texture.getTransformMatrix(stMatrix)"),
        )
        assertTrue(
            "SurfaceTexture transform must be passed through to the preview draw",
            onDrawFrame.contains("textureMatrix = stMatrix"),
        )
        assertFalse(
            "position matrix must not apply a second manual preview rotation",
            computePreviewPosMatrix.contains("Matrix.rotateM"),
        )
    }

    private fun String.substringBetween(start: String, end: String): String =
        substringAfter(start).substringBefore(end)
}

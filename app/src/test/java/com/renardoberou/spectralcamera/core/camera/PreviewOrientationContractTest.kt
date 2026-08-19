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
    fun previewAppliesAuthoritativeRotationOnceWithSurfaceTextureSign() {
        val computePreviewPosMatrix = glPipeline.substringBetween(
            "private fun computePreviewPosMatrix()",
            "private fun drawQuad(",
        )

        assertTrue(
            "GL position transform must apply the SurfaceRequest rotation",
            computePreviewPosMatrix.contains("Matrix.rotateM(posMatrix, 0, -srcRotation.toFloat(), 0f, 0f, 1f)"),
        )
        assertFalse(
            "preview must not describe SurfaceTexture as the sole orientation transform",
            computePreviewPosMatrix.contains("SurfaceTexture transform already carries the camera orientation"),
        )
    }

    private fun String.substringBetween(start: String, end: String): String =
        substringAfter(start).substringBefore(end)
}

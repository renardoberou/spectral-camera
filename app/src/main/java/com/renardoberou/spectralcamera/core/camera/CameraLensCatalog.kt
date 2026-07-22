package com.renardoberou.spectralcamera.core.camera

import com.renardoberou.spectralcamera.core.CameraLensOption
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class CameraLensCandidate(
    val id: String,
    val frontFacing: Boolean,
    val logicalCameraId: String,
    val physicalCameraId: String?,
    val automatic: Boolean,
    val equivalentFocalLengthMm: Float?,
)

internal object CameraLensCatalog {
    fun optionId(logicalCameraId: String, physicalCameraId: String?): String =
        if (physicalCameraId == null) "logical:$logicalCameraId"
        else "logical:$logicalCameraId/physical:$physicalCameraId"

    fun equivalentFocalLengthMm(
        focalLengthMm: Float?,
        sensorWidthMm: Float?,
        sensorHeightMm: Float?,
    ): Float? {
        val focal = focalLengthMm?.takeIf { it > 0f } ?: return null
        val width = sensorWidthMm?.takeIf { it > 0f } ?: return null
        val height = sensorHeightMm?.takeIf { it > 0f } ?: return null
        val sensorDiagonal = sqrt(width * width + height * height)
        if (sensorDiagonal <= 0f) return null
        return focal * FULL_FRAME_DIAGONAL_MM / sensorDiagonal
    }

    fun buildOptions(candidates: List<CameraLensCandidate>): List<CameraLensOption> {
        val unique = candidates.distinctBy { it.id }
        val rear = unique.filter { !it.frontFacing && !it.automatic }
        val mainEquivalent = rear.mapNotNull { it.equivalentFocalLengthMm }
            .minByOrNull { abs(it - 26f) }
        val frontCount = unique.count { it.frontFacing }
        var unknownRearIndex = 0
        var frontIndex = 0

        return unique.sortedWith(
            compareBy<CameraLensCandidate> {
                when {
                    it.frontFacing -> 2
                    it.automatic -> 0
                    else -> 1
                }
            }.thenBy { it.equivalentFocalLengthMm ?: Float.MAX_VALUE }
                .thenBy { it.id },
        ).map { candidate ->
            val equivalent = candidate.equivalentFocalLengthMm
            val (label, description) = when {
                candidate.automatic ->
                    "Auto rear" to "Uses the phone's logical rear camera and allows vendor lens switching."
                candidate.frontFacing -> {
                    frontIndex++
                    val name = if (frontCount > 1) "Selfie $frontIndex" else "Selfie"
                    name to equivalentDescription(equivalent, "Front camera")
                }
                equivalent != null && mainEquivalent != null -> {
                    val ratio = equivalent / mainEquivalent
                    val kind = when {
                        ratio < 0.80f -> "Ultra-wide"
                        ratio > 1.30f -> "Tele"
                        else -> "Main"
                    }
                    "${formatZoom(ratio)}× $kind" to
                        equivalentDescription(equivalent, "Locks this physical rear lens")
                }
                else -> {
                    unknownRearIndex++
                    "Rear lens $unknownRearIndex" to "Locks this discoverable rear camera."
                }
            }
            CameraLensOption(
                id = candidate.id,
                label = label,
                description = description,
                frontFacing = candidate.frontFacing,
                logicalCameraId = candidate.logicalCameraId,
                physicalCameraId = candidate.physicalCameraId,
                approximateEquivalentFocalLengthMm = equivalent,
                isAutomatic = candidate.automatic,
            )
        }
    }

    fun choose(
        options: List<CameraLensOption>,
        requestedId: String,
        preferFront: Boolean,
    ): CameraLensOption? {
        options.firstOrNull { it.id == requestedId }?.let { return it }
        return if (preferFront) {
            options.firstOrNull { it.frontFacing }
                ?: options.firstOrNull()
        } else {
            options.firstOrNull { !it.frontFacing && it.isAutomatic }
                ?: options.firstOrNull { !it.frontFacing && it.label.contains("Main") }
                ?: options.firstOrNull { !it.frontFacing }
                ?: options.firstOrNull()
        }
    }

    private fun equivalentDescription(equivalent: Float?, prefix: String): String =
        if (equivalent == null) prefix else "$prefix • ≈ ${equivalent.roundToInt()} mm equivalent"

    private fun formatZoom(value: Float): String {
        val rounded = value.roundToInt()
        return if (abs(value - rounded) < 0.06f) rounded.toString()
        else String.format(Locale.US, "%.1f", value)
    }

    private const val FULL_FRAME_DIAGONAL_MM = 43.2666f
}

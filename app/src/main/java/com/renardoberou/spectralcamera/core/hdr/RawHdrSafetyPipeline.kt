package com.renardoberou.spectralcamera.core.hdr

import com.renardoberou.spectralcamera.core.HdrToneMap
import com.renardoberou.spectralcamera.core.camera.RawSensorFrame
import kotlin.math.abs
import kotlin.math.max

/**
 * Commercial safety wrapper around the Bayer-domain merger.
 *
 * A captured RAW bracket remains True RAW HDR. However, a globally uncertain
 * member must not be allowed to paint a translucent second scene over the
 * reference. The first pass exposes its measured alignment confidence. Unsafe
 * members are removed and the RAW development is repeated with the reliable
 * subset. If no auxiliary member is reliable, the normal RAW frame is developed
 * through the same RAW/HDR tone pipeline and the result is marked motion
 * protected rather than being relabelled Standard or failing the shutter.
 */
object RawHdrSafetyPipeline {
    private const val MIN_SAFE_CONFIDENCE = 0.55f
    private const val MAX_SAFE_SHIFT_FRACTION = 0.015f

    fun merge(
        frames: List<RawSensorFrame>,
        referenceIndex: Int,
        toneMap: HdrToneMap,
        frontFacing: Boolean,
    ): RawHdrMergeResult {
        val firstPass = RawHdrPipeline.merge(
            frames = frames,
            referenceIndex = referenceIndex,
            toneMap = toneMap,
            frontFacing = frontFacing,
        )
        val reference = frames[referenceIndex]
        val maximumShiftX = max(2f, reference.cropWidth * MAX_SAFE_SHIFT_FRACTION)
        val maximumShiftY = max(2f, reference.cropHeight * MAX_SAFE_SHIFT_FRACTION)
        val reliableIndices = frames.indices.filter { index ->
            if (index == referenceIndex) {
                true
            } else {
                val confidence = firstPass.alignmentConfidence.getOrElse(index) { 0f }
                val shift = firstPass.alignmentRawPixels.getOrElse(index) { PixelShift(0, 0) }
                confidence >= MIN_SAFE_CONFIDENCE &&
                    abs(shift.dx) <= maximumShiftX &&
                    abs(shift.dy) <= maximumShiftY
            }
        }

        if (reliableIndices.size == frames.size && !firstPass.motionProtected) {
            return firstPass
        }

        if (!firstPass.workingBitmap.isRecycled) firstPass.workingBitmap.recycle()
        val safeFrames = reliableIndices.map(frames::get)
        val safeReferenceIndex = reliableIndices.indexOf(referenceIndex).coerceAtLeast(0)
        val safeResult = if (safeFrames.size >= 2) {
            RawHdrPipeline.merge(
                frames = safeFrames,
                referenceIndex = safeReferenceIndex,
                toneMap = toneMap,
                frontFacing = frontFacing,
            )
        } else {
            // A duplicate normal RAW frame satisfies the merger's structural
            // two-frame contract but has exposure scale 1.0, so it contributes
            // no highlight/shadow replacement. This is still RAW development,
            // not the JPEG Standard path, and preserves truthful HDR identity.
            val referenceOnly = reference.copy(dngFile = null)
            RawHdrPipeline.merge(
                frames = listOf(reference, referenceOnly),
                referenceIndex = 0,
                toneMap = toneMap,
                frontFacing = frontFacing,
            )
        }
        return safeResult.copy(motionProtected = true)
    }
}

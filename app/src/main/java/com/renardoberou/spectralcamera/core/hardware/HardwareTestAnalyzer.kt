package com.renardoberou.spectralcamera.core.hardware

import android.graphics.Bitmap
import com.renardoberou.spectralcamera.core.HardwareTestState
import kotlin.math.abs
import kotlin.math.max

/**
 * Detects the near-IR LED of a TV remote in the camera preview.
 *
 * Physics: a 940nm remote LED is invisible to the eye but leaks through the
 * phone's IR-cut filter and lands mostly in the RED and BLUE Bayer wells, far
 * less in GREEN. So on screen it reads as a small bright violet/white dot whose
 * (R+B) clearly exceeds G. We look for that colour signature in a bright spot
 * near the centre, and confirm it is flashing (it pulses while a button is held).
 *
 * Two things the previous version got wrong, both seen in testing: it keyed on
 * luma > 0.88 (the dot is often dimmer than that and the test almost never
 * fired), and it averaged the centre 28% of the frame (a small dot washes out,
 * so moving the remote closer paradoxically lowered the reading). We track the
 * brightest IR-signature spot instead of an average.
 */
class HardwareTestAnalyzer {
    private val spotHistory = ArrayDeque<Float>()
    private var frames = 0

    fun reset(): HardwareTestState {
        spotHistory.clear()
        frames = 0
        return HardwareTestState.idle()
    }

    fun analyze(bitmap: Bitmap): HardwareTestState {
        frames += 1
        val signal = sampleCenter(bitmap)
        spotHistory.addLast(signal.irSpot)
        while (spotHistory.size > 10) spotHistory.removeFirst()

        val flicker = flickerScore()
        // The IR signature itself (a bright spot where R+B beats G) is the
        // primary evidence; flashing raises confidence but isn't required for a
        // steady press. Thresholds tuned to fire within a second or two at a
        // normal hand-held distance.
        val steady = signal.irSpot > 0.45f
        val detected = steady || (signal.irSpot > 0.30f && flicker > 0.10f)
        val confidence = ((signal.irSpot * 0.7f) + (flicker * 0.3f)).coerceIn(0f, 1f)
        val status = when {
            detected -> "minor near-IR leakage detected"
            signal.irSpot > 0.22f -> "faint hotspot seen — hold the button and keep it centred"
            else -> "Waiting for a flashing near-IR hotspot."
        }
        return HardwareTestState(
            instruction = "Point a TV remote at the camera and hold a button.",
            statusMessage = status,
            detected = detected,
            confidence = confidence,
            peakLuma = signal.peakLuma,
            framesAnalyzed = frames,
        )
    }

    private fun flickerScore(): Float {
        if (spotHistory.size < 2) return 0f
        var total = 0f
        var previous = spotHistory.first()
        var count = 0
        for (value in spotHistory.drop(1)) {
            total += abs(value - previous)
            previous = value
            count++
        }
        return if (count == 0) 0f else (total / count * 3f).coerceIn(0f, 1f)
    }

    private data class Signal(val irSpot: Float, val peakLuma: Float)

    /**
     * Scans a central region and returns the strongest IR-LED signature found in
     * any small neighbourhood: brightness gated by the IR colour signature
     * (R+B dominating G). This localises a small dot instead of averaging it away.
     */
    private fun sampleCenter(bitmap: Bitmap): Signal {
        val width = bitmap.width
        val height = bitmap.height
        // central 60% — generous, so the user doesn't have to aim precisely
        val sampleW = (width * 0.60f).toInt().coerceAtLeast(12)
        val sampleH = (height * 0.60f).toInt().coerceAtLeast(12)
        val startX = ((width - sampleW) / 2).coerceAtLeast(0)
        val startY = ((height - sampleH) / 2).coerceAtLeast(0)
        val pixels = IntArray(sampleW * sampleH)
        bitmap.getPixels(pixels, 0, sampleW, startX, startY, sampleW, sampleH)

        var bestSpot = 0f
        var peakLuma = 0f
        for (pixel in pixels) {
            val r = ((pixel ushr 16) and 0xFF) / 255f
            val g = ((pixel ushr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            val luma = (r * 0.299f) + (g * 0.587f) + (b * 0.114f)
            peakLuma = max(peakLuma, luma)

            // IR LED signature: bright AND (R+B) exceeds green (the violet/white
            // cast). A pure-white highlight has R+B ~ 2*G and is excluded by the
            // brightness-with-colour test only weakly, so we require the magenta
            // bias to clearly beat a neutral highlight.
            val brightness = max(r, b)
            val magentaBias = ((r + b) * 0.5f - g).coerceAtLeast(0f) // >0 when violet
            val spot = (brightness * (0.35f + 2.2f * magentaBias)).coerceIn(0f, 1f)
            bestSpot = max(bestSpot, spot)
        }
        return Signal(irSpot = bestSpot, peakLuma = peakLuma)
    }
}

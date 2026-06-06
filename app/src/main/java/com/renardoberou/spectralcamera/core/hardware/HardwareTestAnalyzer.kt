package com.renardoberou.spectralcamera.core.hardware

import android.graphics.Bitmap
import com.renardoberou.spectralcamera.core.HardwareTestState
import kotlin.math.abs
import kotlin.math.max

class HardwareTestAnalyzer {
    private val peakHistory = ArrayDeque<Float>()
    private val meanHistory = ArrayDeque<Float>()
    private var frames = 0

    fun reset(): HardwareTestState {
        peakHistory.clear()
        meanHistory.clear()
        frames = 0
        return HardwareTestState.idle()
    }

    fun analyze(bitmap: Bitmap): HardwareTestState {
        frames += 1
        val stats = sampleCenter(bitmap)
        peakHistory.addLast(stats.peak)
        meanHistory.addLast(stats.mean)
        while (peakHistory.size > 8) peakHistory.removeFirst()
        while (meanHistory.size > 8) meanHistory.removeFirst()

        val flickerScore = flickerScore()
        val confidence = ((stats.peak * 0.55f) + (flickerScore * 0.45f)).coerceIn(0f, 1f)
        val detected = stats.peak > 0.88f && flickerScore > 0.16f
        val status = when {
            detected -> "minor near-IR leakage detected"
            stats.peak > 0.84f -> "bright flashing hotspot seen — keep testing"
            else -> "Waiting for a flashing near-IR hotspot."
        }
        return HardwareTestState(
            instruction = "Point a TV remote at the camera and press a button.",
            statusMessage = status,
            detected = detected,
            confidence = confidence,
            peakLuma = stats.peak,
            framesAnalyzed = frames,
        )
    }

    private fun flickerScore(): Float {
        if (peakHistory.size < 2) return 0f
        var total = 0f
        var count = 0
        var previous = peakHistory.first()
        for (value in peakHistory.drop(1)) {
            total += abs(value - previous)
            previous = value
            count++
        }
        val peakDelta = if (count == 0) 0f else total / count
        val meanDelta = if (meanHistory.size < 2) 0f else {
            var totalMean = 0f
            var prev = meanHistory.first()
            var meanCount = 0
            for (value in meanHistory.drop(1)) {
                totalMean += abs(value - prev)
                prev = value
                meanCount++
            }
            if (meanCount == 0) 0f else totalMean / meanCount
        }
        return max(peakDelta, meanDelta * 1.8f).coerceIn(0f, 1f)
    }

    private data class Stats(val peak: Float, val mean: Float)

    private fun sampleCenter(bitmap: Bitmap): Stats {
        val width = bitmap.width
        val height = bitmap.height
        val sampleW = (width * 0.28f).toInt().coerceAtLeast(8)
        val sampleH = (height * 0.28f).toInt().coerceAtLeast(8)
        val startX = ((width - sampleW) / 2).coerceAtLeast(0)
        val startY = ((height - sampleH) / 2).coerceAtLeast(0)
        val pixels = IntArray(sampleW * sampleH)
        bitmap.getPixels(pixels, 0, sampleW, startX, startY, sampleW, sampleH)
        var peak = 0f
        var total = 0f
        for (pixel in pixels) {
            val r = ((pixel ushr 16) and 0xFF) / 255f
            val g = ((pixel ushr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            val luma = (r * 0.299f) + (g * 0.587f) + (b * 0.114f)
            peak = max(peak, luma)
            total += luma
        }
        return Stats(peak = peak, mean = total / pixels.size.coerceAtLeast(1))
    }
}

package com.renardoberou.spectralcamera.core.filters

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import com.renardoberou.spectralcamera.core.ChannelSwapMode
import com.renardoberou.spectralcamera.core.CameraSettings
import com.renardoberou.spectralcamera.core.ManualAdjustments
import com.renardoberou.spectralcamera.core.SpectralPreset

class SpectralFilterEngine {
    fun processPreview(input: Bitmap, settings: CameraSettings): Bitmap = process(input, settings, preview = true)
    fun processCapture(input: Bitmap, settings: CameraSettings): Bitmap = process(input, settings, preview = false)

    private fun process(input: Bitmap, settings: CameraSettings, preview: Boolean): Bitmap {
        val width = input.width
        val height = input.height
        val src = IntArray(width * height)
        val dst = IntArray(width * height)
        input.getPixels(src, 0, width, 0, 0, width, height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val color = src[index]
                val a = color ushr 24 and 0xFF
                val r = ((color ushr 16) and 0xFF) / 255f
                val g = ((color ushr 8) and 0xFF) / 255f
                val b = (color and 0xFF) / 255f

                val transformed = presetTransform(x, y, width, height, r, g, b, settings.preset)
                val adjusted = applyManualAdjustments(
                    x = x,
                    y = y,
                    width = width,
                    height = height,
                    source = src,
                    base = transformed,
                    inputRgb = floatArrayOf(r, g, b),
                    adjustments = settings.adjustments,
                    preview = preview,
                )

                dst[index] = (a shl 24) or
                    (clampToByte(adjusted[0]) shl 16) or
                    (clampToByte(adjusted[1]) shl 8) or
                    clampToByte(adjusted[2])
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(dst, 0, width, 0, 0, width, height)
        }
    }

    private fun presetTransform(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        r: Float,
        g: Float,
        b: Float,
        preset: SpectralPreset,
    ): FloatArray {
        val luma = weightedLuma(r, g, b)
        val foliage = maxOf(0f, g - b)
        val sky = maxOf(0f, b - g)
        val warm = maxOf(0f, r - b)
        val cool = maxOf(0f, b - r)
        val centerBoost = if (width > 0 && height > 0) {
            val nx = (x / width.toFloat()) - 0.5f
            val ny = (y / height.toFloat()) - 0.5f
            1f - min(1f, kotlin.math.sqrt(nx * nx + ny * ny) * 1.15f)
        } else 0f

        return when (preset) {
            SpectralPreset.B_W_INFRARED -> {
                val mono = toneCurve(luma + foliage * 0.42f - sky * 0.26f, 1.55f)
                floatArrayOf(mono, mono, mono)
            }
            SpectralPreset.HIGH_CONTRAST_IR -> {
                val mono = toneCurve(luma + foliage * 0.48f - sky * 0.32f + centerBoost * 0.03f, 1.95f)
                floatArrayOf(mono, mono, mono)
            }
            SpectralPreset.WHITE_FOLIAGE_DARK_SKY -> {
                val mono = toneCurve(luma + foliage * 0.78f - sky * 0.42f, 1.75f)
                floatArrayOf(mono, mono, mono)
            }
            SpectralPreset.AEROCHROME_FALSE_COLOR -> {
                val red = toneCurve(luma + foliage * 0.92f + warm * 0.08f, 1.3f)
                val green = toneCurve(luma * 0.76f + sky * 0.48f + cool * 0.08f, 1.05f)
                val blue = toneCurve(luma * 0.42f + sky * 0.88f + cool * 0.26f, 1.0f)
                floatArrayOf(red, green, blue)
            }
            SpectralPreset.RED_720_STYLE -> {
                val red = toneCurve(luma + foliage * 0.88f, 1.32f)
                val green = toneCurve(luma * 0.34f + foliage * 0.12f - sky * 0.08f, 1.0f)
                val blue = toneCurve(luma * 0.08f - sky * 0.08f, 0.88f)
                floatArrayOf(red, green, blue)
            }
            SpectralPreset.BLUE_CYAN_SPECTRAL -> {
                val red = toneCurve(luma * 0.16f + warm * 0.06f, 1.0f)
                val green = toneCurve(luma * 0.82f + sky * 0.54f + foliage * 0.12f, 1.08f)
                val blue = toneCurve(luma * 0.94f + sky * 0.66f + cool * 0.12f, 1.08f)
                floatArrayOf(red, green, blue)
            }
            SpectralPreset.FAKE_THERMAL_PALETTE -> thermalPalette(luma + foliage * 0.12f - sky * 0.08f)
            SpectralPreset.NIGHT_SURVEILLANCE_IR -> {
                val mono = toneCurve(luma + foliage * 0.22f - sky * 0.22f, 1.1f)
                floatArrayOf(mono * 0.18f, mono * 0.92f, mono * 0.22f)
            }
        }
    }

    private fun applyManualAdjustments(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        source: IntArray,
        base: FloatArray,
        inputRgb: FloatArray,
        adjustments: ManualAdjustments,
        preview: Boolean,
    ): FloatArray {
        var r = base[0]
        var g = base[1]
        var b = base[2]

        val exposure = adjustments.exposureCompensation
        val exposureScale = 2f.pow(exposure.coerceIn(-3f, 3f))
        r *= exposureScale
        g *= exposureScale
        b *= exposureScale

        val contrast = adjustments.contrast.coerceIn(0.35f, 2.2f)
        r = ((r - 0.5f) * contrast) + 0.5f
        g = ((g - 0.5f) * contrast) + 0.5f
        b = ((b - 0.5f) * contrast) + 0.5f

        val luma = weightedLuma(r, g, b)
        val blacks = adjustments.blacks.coerceIn(-1f, 1f)
        val whites = adjustments.whites.coerceIn(-1f, 1f)
        val blackLift = blacks * 0.22f
        val whitePush = whites * 0.18f
        r = adjustLevels(r, blackLift, whitePush)
        g = adjustLevels(g, blackLift, whitePush)
        b = adjustLevels(b, blackLift, whitePush)

        val foliageSignal = maxOf(0f, inputRgb[1] - inputRgb[2])
        val skySignal = maxOf(0f, inputRgb[2] - inputRgb[1])
        val foliageLift = adjustments.greenFoliageLift.coerceIn(-1f, 1f)
        val skySuppression = adjustments.blueSkySuppression.coerceIn(-1f, 1f)
        g += foliageSignal * foliageLift * 0.35f
        b -= skySignal * skySuppression * 0.32f
        r += foliageSignal * foliageLift * 0.14f

        val redWeight = adjustments.redChannelWeight.coerceIn(0.25f, 2.2f)
        r *= redWeight

        val saturation = adjustments.saturation.coerceIn(0f, 2.2f)
        val satLuma = weightedLuma(r, g, b)
        r = satLuma + (r - satLuma) * saturation
        g = satLuma + (g - satLuma) * saturation
        b = satLuma + (b - satLuma) * saturation

        val hueRotated = rotateHue(r, g, b, adjustments.hueRotation.coerceIn(-180f, 180f))
        r = hueRotated[0]
        g = hueRotated[1]
        b = hueRotated[2]

        val sharpness = adjustments.sharpness.coerceIn(0f, 1.5f)
        if (sharpness > 0f) {
            val blurred = neighborLuma(source, width, height, x, y)
            val detail = (luma - blurred) * sharpness * 0.45f
            r += detail
            g += detail
            b += detail
        }

        val bloom = adjustments.bloom.coerceIn(0f, 1.2f)
        if (bloom > 0f) {
            val highlight = maxOf(r, g, b)
            if (highlight > 0.58f) {
                val glow = ((highlight - 0.58f) / 0.42f).coerceIn(0f, 1f)
                val amount = bloom * glow * glow * 0.42f
                r += amount
                g += amount * 0.92f
                b += amount * 0.84f
            }
        }

        val grain = adjustments.grain.coerceIn(0f, 1f)
        if (grain > 0f) {
            val noise = pseudoRandom(x, y, width, height) * grain * if (preview) 0.085f else 0.065f
            r += noise
            g += noise
            b += noise
        }

        val swapped = applyChannelSwap(r, g, b, adjustments.channelSwapMode)
        return floatArrayOf(
            swapped[0].coerceIn(0f, 1f),
            swapped[1].coerceIn(0f, 1f),
            swapped[2].coerceIn(0f, 1f),
        )
    }

    private fun applyChannelSwap(r: Float, g: Float, b: Float, mode: ChannelSwapMode): FloatArray = when (mode) {
        ChannelSwapMode.NONE -> floatArrayOf(r, g, b)
        ChannelSwapMode.RB_SWAP -> floatArrayOf(b, g, r)
        ChannelSwapMode.RG_SWAP -> floatArrayOf(g, r, b)
        ChannelSwapMode.GB_SWAP -> floatArrayOf(r, b, g)
    }

    private fun thermalPalette(value: Float): FloatArray {
        val v = value.coerceIn(0f, 1f)
        return when {
            v < 0.20f -> floatArrayOf(0.05f, 0.00f, 0.12f + v * 0.58f)
            v < 0.40f -> floatArrayOf(0.10f + (v - 0.20f) * 2.0f, 0.00f, 0.60f + (v - 0.20f) * 0.85f)
            v < 0.60f -> floatArrayOf(0.50f + (v - 0.40f) * 2.0f, 0.10f + (v - 0.40f) * 1.35f, 0.90f - (v - 0.40f) * 1.25f)
            v < 0.80f -> floatArrayOf(0.95f, 0.42f + (v - 0.60f) * 2.2f, 0.05f)
            else -> floatArrayOf(0.98f, 0.82f + (v - 0.80f) * 0.9f, 0.62f + (v - 0.80f) * 0.9f)
        }
    }

    private fun toneCurve(value: Float, power: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return x.pow(power).coerceIn(0f, 1f)
    }

    private fun weightedLuma(r: Float, g: Float, b: Float): Float =
        (r * 0.299f) + (g * 0.587f) + (b * 0.114f)

    private fun adjustLevels(value: Float, blackLift: Float, whitePush: Float): Float {
        var v = value + blackLift
        if (whitePush > 0f) {
            v = v + (1f - v) * whitePush
        } else if (whitePush < 0f) {
            v = v * (1f + whitePush * 0.35f)
        }
        return v
    }

    private fun neighborLuma(source: IntArray, width: Int, height: Int, x: Int, y: Int): Float {
        var count = 0
        var total = 0f
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val sx = (x + dx).coerceIn(0, width - 1)
                val sy = (y + dy).coerceIn(0, height - 1)
                val color = source[sy * width + sx]
                val r = ((color ushr 16) and 0xFF) / 255f
                val g = ((color ushr 8) and 0xFF) / 255f
                val b = (color and 0xFF) / 255f
                total += weightedLuma(r, g, b)
                count++
            }
        }
        return if (count == 0) 0f else total / count
    }

    private fun rotateHue(r: Float, g: Float, b: Float, degrees: Float): FloatArray {
        if (degrees == 0f) return floatArrayOf(r, g, b)
        val rad = Math.toRadians(degrees.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()

        val y = (0.299f * r) + (0.587f * g) + (0.114f * b)
        val i = (0.596f * r) - (0.275f * g) - (0.321f * b)
        val q = (0.212f * r) - (0.523f * g) + (0.311f * b)

        val i2 = (i * cosA) - (q * sinA)
        val q2 = (i * sinA) + (q * cosA)

        val rr = y + (0.956f * i2) + (0.621f * q2)
        val gg = y - (0.272f * i2) - (0.647f * q2)
        val bb = y - (1.106f * i2) + (1.703f * q2)

        return floatArrayOf(rr, gg, bb)
    }

    private fun pseudoRandom(x: Int, y: Int, width: Int, height: Int): Float {
        var n = x * 374761393 + y * 668265263 + width * 1442695040888963407L.hashCode() + height * 374761393
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return ((n and 0x7fffffff) / 1073741824f) - 1f
    }

    private fun clampToByte(v: Float): Int = (v.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
}

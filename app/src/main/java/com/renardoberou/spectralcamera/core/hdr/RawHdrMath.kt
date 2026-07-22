package com.renardoberou.spectralcamera.core.hdr

import kotlin.math.exp
import kotlin.math.max

enum class BayerArrangement(val cameraValue: Int) {
    RGGB(0),
    GRBG(1),
    GBRG(2),
    BGGR(3),
    ;

    companion object {
        fun fromCameraValue(value: Int): BayerArrangement? = values().firstOrNull { it.cameraValue == value }
    }
}

enum class BayerChannel {
    RED,
    GREEN_EVEN,
    GREEN_ODD,
    BLUE,
}

object RawHdrMath {
    fun channelAt(arrangement: BayerArrangement, x: Int, y: Int): BayerChannel {
        val evenX = (x and 1) == 0
        val evenY = (y and 1) == 0
        return when (arrangement) {
            BayerArrangement.RGGB -> when {
                evenY && evenX -> BayerChannel.RED
                evenY -> BayerChannel.GREEN_EVEN
                evenX -> BayerChannel.GREEN_ODD
                else -> BayerChannel.BLUE
            }
            BayerArrangement.GRBG -> when {
                evenY && evenX -> BayerChannel.GREEN_EVEN
                evenY -> BayerChannel.RED
                evenX -> BayerChannel.BLUE
                else -> BayerChannel.GREEN_ODD
            }
            BayerArrangement.GBRG -> when {
                evenY && evenX -> BayerChannel.GREEN_EVEN
                evenY -> BayerChannel.BLUE
                evenX -> BayerChannel.RED
                else -> BayerChannel.GREEN_ODD
            }
            BayerArrangement.BGGR -> when {
                evenY && evenX -> BayerChannel.BLUE
                evenY -> BayerChannel.GREEN_EVEN
                evenX -> BayerChannel.GREEN_ODD
                else -> BayerChannel.RED
            }
        }
    }

    fun parityIndex(x: Int, y: Int): Int = ((y and 1) shl 1) or (x and 1)

    fun gainFor(channel: BayerChannel, gains: FloatArray): Float = when (channel) {
        BayerChannel.RED -> gains[0]
        BayerChannel.GREEN_EVEN -> gains[1]
        BayerChannel.GREEN_ODD -> gains[2]
        BayerChannel.BLUE -> gains[3]
    }

    fun normalizeCode(code: Int, black: Float, white: Float): Float {
        val range = (white - black).coerceAtLeast(1f)
        return ((code - black) / range).coerceIn(0f, 1.25f)
    }

    fun normalizeCode(code: Int, black: Float, white: Number): Float =
        normalizeCode(code, black, white.toFloat())

    fun rawWellExposedWeight(normalizedCode: Float): Float {
        val v = normalizedCode.coerceIn(0f, 1f)
        if (v <= 0.002f || v >= 0.995f) return 0.001f
        val distance = (v - 0.42f) / 0.28f
        return 0.02f + exp(-0.5f * distance * distance)
    }

    fun exposureProduct(exposureTimeNs: Long, sensitivityIso: Int): Double =
        max(1L, exposureTimeNs).toDouble() * max(1, sensitivityIso).toDouble()

    fun transformLinearRgb(sensorR: Float, sensorG: Float, sensorB: Float, matrix: FloatArray): FloatArray {
        require(matrix.size == 9)
        return floatArrayOf(
            matrix[0] * sensorR + matrix[1] * sensorG + matrix[2] * sensorB,
            matrix[3] * sensorR + matrix[4] * sensorG + matrix[5] * sensorB,
            matrix[6] * sensorR + matrix[7] * sensorG + matrix[8] * sensorB,
        )
    }
}

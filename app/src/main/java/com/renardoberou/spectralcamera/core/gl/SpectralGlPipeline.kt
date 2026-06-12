package com.renardoberou.spectralcamera.core.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import com.renardoberou.spectralcamera.core.CameraSettings
import com.renardoberou.spectralcamera.core.ChannelSwapMode
import com.renardoberou.spectralcamera.core.SpectralPreset
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GPU spectral pipeline.
 *
 * One GLSL filter chain, two paths:
 *  - Live preview: camera frames arrive on an external OES texture via SurfaceTexture
 *    and are rendered straight to screen at full preview resolution.
 *  - Capture: the full-resolution still is uploaded as a 2D texture, rendered through
 *    the exact same shader into an offscreen framebuffer, and read back as a Bitmap.
 *
 * This replaces the per-pixel CPU engine entirely: zero per-frame allocations,
 * identical look between preview and saved photos.
 */

private const val VERTEX_SHADER = """
attribute vec4 aPosition;
attribute vec2 aTexCoord;
uniform mat4 uPosMatrix;
uniform mat4 uTexMatrix;
varying vec2 vTexCoord;
void main() {
    gl_Position = uPosMatrix * aPosition;
    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
}
"""

private const val FRAGMENT_PRECISION = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
"""

private const val FRAGMENT_BODY = """
varying vec2 vTexCoord;
uniform vec2 uTexelSize;
uniform int uPreset;
uniform float uExposure;
uniform float uContrast;
uniform float uBlacks;
uniform float uWhites;
uniform float uBloom;
uniform float uGrain;
uniform float uGrainSeed;
uniform float uSharpness;
uniform float uRedWeight;
uniform float uFoliageLift;
uniform float uSkySuppress;
uniform float uHueCos;
uniform float uHueSin;
uniform float uSaturation;
uniform int uSwapMode;
uniform vec2 uSkyUp;

float lumaOf(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

float tone(float v, float p) {
    return pow(clamp(v, 0.0, 1.0), p);
}

float hashNoise(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y) * 2.0 - 1.0;
}

vec3 thermal(float v) {
    v = clamp(v, 0.0, 1.0);
    if (v < 0.20) return vec3(0.05, 0.00, 0.12 + v * 0.58);
    if (v < 0.40) return vec3(0.10 + (v - 0.20) * 2.0, 0.00, 0.60 + (v - 0.20) * 0.85);
    if (v < 0.60) return vec3(0.50 + (v - 0.40) * 2.0, 0.10 + (v - 0.40) * 1.35, 0.90 - (v - 0.40) * 1.25);
    if (v < 0.80) return vec3(0.95, 0.42 + (v - 0.60) * 2.2, 0.05);
    return vec3(0.98, 0.82 + (v - 0.80) * 0.9, 0.62 + (v - 0.80) * 0.9);
}

// Kodak Aerochrome / EIR false-colour emulation.
//
// The film maps NIR -> red layer, red -> green layer, green -> blue layer
// (blue is cut by the mandatory yellow filter). A normal phone sensor has no
// NIR channel, so one is synthesised: chlorophyll is detected via a pseudo
// NDVI (green vs red) gated by green-over-blue, and pushed very bright in the
// synthetic NIR, exactly as living foliage behaves on the real film. Sky and
// water are NIR-dark. The channel shift then produces the canonical result:
// hot magenta/crimson foliage, deep cyan-blue skies, white clouds, dark
// water, waxy pale-green skin, red paint turning yellow-green.
// gold = 1.0 selects the orange-filter variant (warmer foliage, teal skies).
vec3 aerochrome(vec3 c, float gold, float skyMask, float skyT) {
    float r = c.r;
    float g = c.g;
    float b = c.b;
    float luma = lumaOf(c);

    float ndvi = (g - r) / max(g + r, 0.001);
    float veg = smoothstep(0.0, 0.30, ndvi) * smoothstep(0.02, 0.16, g - b);
    veg = clamp(veg * 1.3, 0.0, 1.0);

    float skyness = smoothstep(0.02, 0.22, b - max(r, g * 0.95));

    float nir = luma * 0.92
        + veg * (0.50 + 0.50 * g)
        + max(r - b, 0.0) * 0.20
        - skyness * 0.60;
    nir = clamp(nir, 0.0, 1.0);

    vec3 ir;
    ir.r = nir * (1.0 - skyness * (0.30 - gold * 0.10));
    ir.g = (r * 0.95 + nir * 0.05) * (1.0 - veg * (0.45 - gold * 0.20));
    ir.b = (g * 0.80 + b * 0.22) * (1.0 - veg * 0.50) + skyness * 0.16;

    ir.g += gold * veg * 0.18;
    ir.b -= gold * 0.10 * (1.0 - skyness);
    ir = clamp(ir, 0.0, 1.0);

    // slide-film S-curve: crushed toe, rolled shoulder
    vec3 s1 = ir * ir * (3.0 - 2.0 * ir);
    ir = mix(ir, s1, 0.65);

    // baked-in reversal-film saturation
    float il = lumaOf(ir);
    ir = clamp(vec3(il) + (ir - vec3(il)) * 1.28, 0.0, 1.0);

    // EIR sky: deep graded blue (teal on the gold variant), regardless of
    // whether the source sky was blue or blown-out overcast white. Source
    // luminance variation modulates the result so cloud structure survives.
    vec3 zen = mix(vec3(0.05, 0.11, 0.48), vec3(0.04, 0.24, 0.40), gold);
    vec3 hor = mix(vec3(0.38, 0.50, 0.80), vec3(0.32, 0.55, 0.62), gold);
    vec3 skyCol = mix(hor, zen, smoothstep(0.45, 1.0, skyT));
    skyCol *= 0.40 + 0.85 * smoothstep(0.55, 1.0, luma);
    ir = mix(ir, clamp(skyCol, 0.0, 1.0), skyMask * 0.88);
    return ir;
}

vec3 presetColor(vec3 src, float skyMask, float skyT) {
    float r = src.r;
    float g = src.g;
    float b = src.b;
    float luma = lumaOf(src);
    float foliage = max(0.0, g - b);
    float sky = max(0.0, b - g);
    float warm = max(0.0, r - b);
    float cool = max(0.0, b - r);

    if (uPreset == 0) {
        float m = tone(luma + foliage * 0.42 - sky * 0.26, 1.55);
        // Wood effect: IR-dark sky, smooth curve, cloud structure preserved
        float skyShade = m * mix(0.22, 0.62, smoothstep(0.60, 0.98, m));
        m = mix(m, skyShade, skyMask);
        return vec3(m);
    }
    if (uPreset == 1) {
        float m = tone(luma + foliage * 0.48 - sky * 0.32, 1.95);
        m = mix(m, m * mix(0.16, 0.55, smoothstep(0.60, 0.98, m)), skyMask);
        return vec3(m);
    }
    if (uPreset == 2) {
        float m = tone(luma + foliage * 0.78 - sky * 0.42, 1.75);
        m = mix(m, m * mix(0.20, 0.58, smoothstep(0.60, 0.98, m)), skyMask);
        return vec3(m);
    }
    if (uPreset == 3) {
        return aerochrome(src, 0.0, skyMask, skyT);
    }
    if (uPreset == 4) {
        return aerochrome(src, 1.0, skyMask, skyT);
    }
    if (uPreset == 5) {
        vec3 red720 = vec3(
            tone(luma + foliage * 0.88, 1.32),
            tone(luma * 0.34 + foliage * 0.12 - sky * 0.08, 1.0),
            tone(luma * 0.08 - sky * 0.08, 0.88)
        );
        return red720 * mix(1.0, 0.30 + 0.35 * smoothstep(0.60, 0.98, luma), skyMask);
    }
    if (uPreset == 6) {
        return vec3(
            tone(luma * 0.16 + warm * 0.06, 1.0),
            tone(luma * 0.82 + sky * 0.54 + foliage * 0.12, 1.08),
            tone(luma * 0.94 + sky * 0.66 + cool * 0.12, 1.08)
        );
    }
    if (uPreset == 7) {
        return thermal(luma + foliage * 0.12 - sky * 0.08);
    }
    float m = tone(luma + foliage * 0.22 - sky * 0.22, 1.1);
    return vec3(m * 0.18, m * 0.92, m * 0.22);
}

void main() {
    vec3 src = texture2D(uTexture, vTexCoord).rgb;

    // ---- sky detection ---------------------------------------------------
    // Sky is a low-frequency phenomenon, so the mask is built from a wide
    // blur of the image: spatially smooth, immune to pixel noise, and bright
    // facades disqualify themselves because windows pull the wide average
    // down. A generous per-pixel gate then keeps the soft mask from bleeding
    // onto dark silhouettes (no matte lines on buildings or branches).
    float srcLuma = lumaOf(src);
    vec2 pix0 = vTexCoord / max(uTexelSize, vec2(0.00001));

    vec2 sr = vec2(0.045, 0.045);
    vec3 blur = src;
    blur += texture2D(uTexture, vTexCoord + sr * vec2( 1.0,  0.0)).rgb;
    blur += texture2D(uTexture, vTexCoord + sr * vec2(-1.0,  0.0)).rgb;
    blur += texture2D(uTexture, vTexCoord + sr * vec2( 0.0,  1.0)).rgb;
    blur += texture2D(uTexture, vTexCoord + sr * vec2( 0.0, -1.0)).rgb;
    blur += texture2D(uTexture, vTexCoord + sr * vec2( 0.7,  0.7)).rgb;
    blur += texture2D(uTexture, vTexCoord + sr * vec2(-0.7,  0.7)).rgb;
    blur += texture2D(uTexture, vTexCoord + sr * vec2( 0.7, -0.7)).rgb;
    blur += texture2D(uTexture, vTexCoord + sr * vec2(-0.7, -0.7)).rgb;
    blur *= 0.111111;

    float bLuma = lumaOf(blur);
    float bMax = max(blur.r, max(blur.g, blur.b));
    float bMin = min(blur.r, min(blur.g, blur.b));
    float bSat = (bMax - bMin) / max(bMax, 0.001);

    float skyT = clamp(dot(vTexCoord - vec2(0.5), uSkyUp) + 0.5, 0.0, 1.0);
    float skyPrior = smoothstep(0.25, 0.55, skyT);

    float blueSky = smoothstep(0.0, 0.10, blur.b - max(blur.r, blur.g * 0.97))
        * smoothstep(0.22, 0.45, bLuma);
    float flatSky = smoothstep(0.62, 0.80, bLuma)
        * (1.0 - smoothstep(0.10, 0.26, bSat));
    float skyMask = clamp(blueSky * (0.30 + 0.70 * skyPrior) + flatSky * skyPrior, 0.0, 1.0);

    float gate = max(
        smoothstep(0.40, 0.62, srcLuma),
        smoothstep(0.02, 0.10, src.b - max(src.r, src.g * 0.97)));
    skyMask *= gate;
    skyMask = clamp(skyMask * (1.0 + uSkySuppress * 0.8), 0.0, 1.0);
    // soft dither on the mask hides 8-bit banding along wide transitions
    skyMask = clamp(skyMask + hashNoise(pix0 * 0.31 + vec2(uGrainSeed)) * 0.02, 0.0, 1.0);
    // -----------------------------------------------------------------------

    vec3 c = presetColor(src, skyMask, skyT);

    // exposure (digital gain, stops)
    c *= exp2(clamp(uExposure, -3.0, 3.0));

    // contrast around mid grey
    float k = clamp(uContrast, 0.35, 2.2);
    c = (c - 0.5) * k + 0.5;

    // blacks / whites levels
    float blackLift = clamp(uBlacks, -1.0, 1.0) * 0.22;
    float whitePush = clamp(uWhites, -1.0, 1.0) * 0.18;
    c += blackLift;
    if (whitePush > 0.0) {
        c += (1.0 - c) * whitePush;
    } else {
        c *= 1.0 + whitePush * 0.35;
    }

    // foliage lift / sky suppression keyed on the source image
    float folSig = max(0.0, src.g - src.b);
    float skySig = max(0.0, src.b - src.g);
    c.g += folSig * uFoliageLift * 0.35;
    c.r += folSig * uFoliageLift * 0.14;
    c.b -= skySig * uSkySuppress * 0.32;

    // red channel weight
    c.r *= clamp(uRedWeight, 0.25, 2.2);

    // saturation
    float sl = lumaOf(c);
    c = vec3(sl) + (c - vec3(sl)) * clamp(uSaturation, 0.0, 2.2);

    // hue rotation (YIQ)
    float Y = dot(c, vec3(0.299, 0.587, 0.114));
    float I = dot(c, vec3(0.596, -0.275, -0.321));
    float Q = dot(c, vec3(0.212, -0.523, 0.311));
    float I2 = I * uHueCos - Q * uHueSin;
    float Q2 = I * uHueSin + Q * uHueCos;
    c = vec3(
        Y + 0.956 * I2 + 0.621 * Q2,
        Y - 0.272 * I2 - 0.647 * Q2,
        Y - 1.106 * I2 + 1.703 * Q2
    );

    // unsharp mask on the source luma (4-tap cross)
    if (uSharpness > 0.001) {
        float n = lumaOf(texture2D(uTexture, vTexCoord + vec2(uTexelSize.x, 0.0)).rgb)
            + lumaOf(texture2D(uTexture, vTexCoord - vec2(uTexelSize.x, 0.0)).rgb)
            + lumaOf(texture2D(uTexture, vTexCoord + vec2(0.0, uTexelSize.y)).rgb)
            + lumaOf(texture2D(uTexture, vTexCoord - vec2(0.0, uTexelSize.y)).rgb);
        c += (srcLuma - n * 0.25) * uSharpness * 0.5;
    }

    // highlight bloom / halation
    if (uBloom > 0.001) {
        float h = max(c.r, max(c.g, c.b));
        if (h > 0.58) {
            float glow = clamp((h - 0.58) / 0.42, 0.0, 1.0);
            float amt = uBloom * glow * glow * 0.42;
            c += vec3(amt, amt * 0.92, amt * 0.84);
        }
    }

    // film grain
    if (uGrain > 0.001) {
        vec2 pix = vTexCoord / max(uTexelSize, vec2(0.00001));
        c += hashNoise(pix * 0.73 + vec2(uGrainSeed)) * uGrain * 0.075;
    }

    // channel swap
    if (uSwapMode == 1) {
        c = c.bgr;
    } else if (uSwapMode == 2) {
        c = c.grb;
    } else if (uSwapMode == 3) {
        c = c.rbg;
    }

    // sub-LSB dither: prevents 8-bit banding in smooth gradients
    c += hashNoise(pix0 * 1.7 + vec2(uGrainSeed * 0.37)) * 0.004;

    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""

private const val FRAGMENT_OES = "#extension GL_OES_EGL_image_external : require\n" +
    FRAGMENT_PRECISION +
    "uniform samplerExternalOES uTexture;\n" +
    FRAGMENT_BODY

private const val FRAGMENT_2D = FRAGMENT_PRECISION +
    "uniform sampler2D uTexture;\n" +
    FRAGMENT_BODY

internal fun SpectralPreset.toShaderIndex(): Int = when (this) {
    SpectralPreset.B_W_INFRARED -> 0
    SpectralPreset.HIGH_CONTRAST_IR -> 1
    SpectralPreset.WHITE_FOLIAGE_DARK_SKY -> 2
    SpectralPreset.AEROCHROME_FALSE_COLOR -> 3
    SpectralPreset.AEROCHROME_GOLD -> 4
    SpectralPreset.RED_720_STYLE -> 5
    SpectralPreset.BLUE_CYAN_SPECTRAL -> 6
    SpectralPreset.FAKE_THERMAL_PALETTE -> 7
    SpectralPreset.NIGHT_SURVEILLANCE_IR -> 8
}

internal fun ChannelSwapMode.toShaderIndex(): Int = when (this) {
    ChannelSwapMode.NONE -> 0
    ChannelSwapMode.RB_SWAP -> 1
    ChannelSwapMode.RG_SWAP -> 2
    ChannelSwapMode.GB_SWAP -> 3
}

class SpectralRenderer(
    private val onSurfaceTexture: (SurfaceTexture) -> Unit,
) : GLSurfaceView.Renderer {

    @Volatile
    private var settings: CameraSettings = CameraSettings()

    @Volatile
    private var srcWidth = 0

    @Volatile
    private var srcHeight = 0

    @Volatile
    private var srcRotation = 0

    private var viewWidth = 1
    private var viewHeight = 1

    private var oesProgram: ShaderProgram? = null
    private var flatProgram: ShaderProgram? = null
    private var oesTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null

    private val stMatrix = FloatArray(16)
    private val posMatrix = FloatArray(16)
    private val identityMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val flipYMatrix = FloatArray(16).also {
        Matrix.setIdentityM(it, 0)
        Matrix.scaleM(it, 0, 1f, -1f, 1f)
    }

    private var frameIndex = 0

    var maxTextureSize: Int = 2048
        private set

    private val quadPositions: FloatBuffer = floatBufferOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f,
    )
    private val quadTexCoords: FloatBuffer = floatBufferOf(
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f,
    )

    fun updateSettings(newSettings: CameraSettings) {
        settings = newSettings
    }

    fun setSourceSize(width: Int, height: Int) {
        srcWidth = width
        srcHeight = height
    }

    fun setSourceRotation(degrees: Int) {
        srcRotation = degrees
    }

    /** Atomically configures the camera source geometry (size + upright rotation). */
    fun configureSource(width: Int, height: Int, rotationDegrees: Int) {
        srcWidth = width
        srcHeight = height
        srcRotation = rotationDegrees
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // With preserveEGLContextOnPause the context (and therefore the OES texture
        // and SurfaceTexture the camera streams into) usually survives surface
        // recreation. In that case keep everything: the camera connection stays
        // unbroken and no re-negotiation is needed.
        val contextPreserved = oesProgram?.let { GLES20.glIsProgram(it.id) } == true &&
            surfaceTexture != null
        if (contextPreserved) return

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        val maxSize = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0)
        maxTextureSize = maxSize[0].coerceAtLeast(2048)

        oesProgram?.release()
        flatProgram?.release()
        oesProgram = ShaderProgram(VERTEX_SHADER, FRAGMENT_OES)
        flatProgram = ShaderProgram(VERTEX_SHADER, FRAGMENT_2D)

        if (oesTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
        }
        oesTextureId = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)

        surfaceTexture?.release()
        val texture = SurfaceTexture(oesTextureId)
        surfaceTexture = texture
        onSurfaceTexture(texture)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width.coerceAtLeast(1)
        viewHeight = height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val texture = surfaceTexture ?: return
        val program = oesProgram ?: return
        texture.updateTexImage()
        texture.getTransformMatrix(stMatrix)
        computePreviewPosMatrix()
        frameIndex = (frameIndex + 1) % 997

        val width = if (srcWidth > 0) srcWidth else viewWidth
        val height = if (srcHeight > 0) srcHeight else viewHeight
        // Image-up in texcoord space = the texture matrix image of the attr
        // up axis (column 1); the position matrix is scale/mirror only.
        var skyUpX = stMatrix[4]
        var skyUpY = stMatrix[5]
        val skyLen = sqrt(skyUpX * skyUpX + skyUpY * skyUpY)
        if (skyLen < 1e-3f) {
            skyUpX = 0f
            skyUpY = -1f
        } else {
            skyUpX /= skyLen
            skyUpY /= skyLen
        }
        drawQuad(
            program = program,
            currentSettings = settings,
            textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            textureId = oesTextureId,
            positionMatrix = posMatrix,
            textureMatrix = stMatrix,
            texWidth = width,
            texHeight = height,
            grainSeed = frameIndex.toFloat(),
            skyUpX = skyUpX,
            skyUpY = skyUpY,
        )
    }

    /**
     * Runs the full filter chain over a still image. Must be called on the GL thread
     * (see [SpectralGlView.process]). Returns a new upright Bitmap.
     */
    fun processBitmap(input: Bitmap, captureSettings: CameraSettings): Bitmap {
        val program = flatProgram ?: throw IllegalStateException("GL pipeline not ready")

        var working = if (input.config == Bitmap.Config.ARGB_8888) {
            input
        } else {
            input.copy(Bitmap.Config.ARGB_8888, false)
        }
        val limit = maxTextureSize
        if (working.width > limit || working.height > limit) {
            val scale = min(limit / working.width.toFloat(), limit / working.height.toFloat())
            val scaled = Bitmap.createScaledBitmap(
                working,
                (working.width * scale).toInt().coerceAtLeast(1),
                (working.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            if (working !== input) working.recycle()
            working = scaled
        }
        val width = working.width
        val height = working.height

        val sourceTexture = createTexture(GLES20.GL_TEXTURE_2D)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, working, 0)

        val targetTexture = createTexture(GLES20.GL_TEXTURE_2D)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, targetTexture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )

        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, targetTexture, 0,
        )

        try {
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw IllegalStateException("Capture framebuffer incomplete: $status")
            }

            GLES20.glViewport(0, 0, width, height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawQuad(
                program = program,
                currentSettings = captureSettings,
                textureTarget = GLES20.GL_TEXTURE_2D,
                textureId = sourceTexture,
                // No Y flip: the bitmap-upload and readPixels conventions already
                // cancel; an extra flip is what made captures come out upside down.
                positionMatrix = identityMatrix,
                textureMatrix = identityMatrix,
                texWidth = width,
                texHeight = height,
                grainSeed = (System.currentTimeMillis() % 997L).toFloat(),
                // Rendered content is NDC-inverted here, so image-up is -v.
                skyUpX = 0f,
                skyUpY = -1f,
            )

            val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
            buffer.rewind()

            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            output.copyPixelsFromBuffer(buffer)
            return output
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, fbo, 0)
            GLES20.glDeleteTextures(2, intArrayOf(sourceTexture, targetTexture), 0)
            if (working !== input) working.recycle()
            GLES20.glViewport(0, 0, viewWidth, viewHeight)
        }
    }

    private fun computePreviewPosMatrix() {
        Matrix.setIdentityM(posMatrix, 0)
        if (srcWidth <= 0 || srcHeight <= 0) {
            // Source geometry not yet known: show the frame edge-to-edge without
            // inventing an aspect ratio (never stretch on a fallback).
            var fx = 1f
            if (settings.frontFacing) fx = -1f
            Matrix.scaleM(posMatrix, 0, fx, 1f, 1f)
            return
        }
        val rotated = srcRotation == 90 || srcRotation == 270
        val contentW = (if (rotated) srcHeight else srcWidth).coerceAtLeast(1)
        val contentH = (if (rotated) srcWidth else srcHeight).coerceAtLeast(1)
        val contentAspect = contentW / contentH.toFloat()
        val viewAspect = viewWidth / viewHeight.toFloat()

        var sx = contentAspect / viewAspect
        var sy = 1f
        if (sx < 1f) {
            sy = 1f / sx
            sx = 1f
        }
        if (settings.frontFacing) sx = -sx

        Matrix.scaleM(posMatrix, 0, sx, sy, 1f)
        // The SurfaceTexture transform already carries the camera orientation,
        // so the quad only needs aspect scaling and optional mirroring here.
    }

    private fun drawQuad(
        program: ShaderProgram,
        currentSettings: CameraSettings,
        textureTarget: Int,
        textureId: Int,
        positionMatrix: FloatArray,
        textureMatrix: FloatArray,
        texWidth: Int,
        texHeight: Int,
        grainSeed: Float,
        skyUpX: Float,
        skyUpY: Float,
    ) {
        val adj = currentSettings.adjustments
        GLES20.glUseProgram(program.id)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(textureTarget, textureId)
        GLES20.glUniform1i(program.uTexture, 0)

        GLES20.glUniformMatrix4fv(program.uPosMatrix, 1, false, positionMatrix, 0)
        GLES20.glUniformMatrix4fv(program.uTexMatrix, 1, false, textureMatrix, 0)
        GLES20.glUniform2f(
            program.uTexelSize,
            1f / texWidth.coerceAtLeast(1),
            1f / texHeight.coerceAtLeast(1),
        )

        GLES20.glUniform1i(program.uPreset, currentSettings.preset.toShaderIndex())
        GLES20.glUniform1f(program.uExposure, adj.exposureCompensation)
        GLES20.glUniform1f(program.uContrast, adj.contrast)
        GLES20.glUniform1f(program.uBlacks, adj.blacks)
        GLES20.glUniform1f(program.uWhites, adj.whites)
        GLES20.glUniform1f(program.uBloom, adj.bloom)
        GLES20.glUniform1f(program.uGrain, adj.grain)
        GLES20.glUniform1f(program.uGrainSeed, grainSeed)
        GLES20.glUniform2f(program.uSkyUp, skyUpX, skyUpY)
        GLES20.glUniform1f(program.uSharpness, adj.sharpness)
        GLES20.glUniform1f(program.uRedWeight, adj.redChannelWeight)
        GLES20.glUniform1f(program.uFoliageLift, adj.greenFoliageLift)
        GLES20.glUniform1f(program.uSkySuppress, adj.blueSkySuppression)
        val radians = Math.toRadians(adj.hueRotation.toDouble())
        GLES20.glUniform1f(program.uHueCos, cos(radians).toFloat())
        GLES20.glUniform1f(program.uHueSin, sin(radians).toFloat())
        GLES20.glUniform1f(program.uSaturation, adj.saturation)
        GLES20.glUniform1i(program.uSwapMode, adj.channelSwapMode.toShaderIndex())

        GLES20.glEnableVertexAttribArray(program.aPosition)
        quadPositions.position(0)
        GLES20.glVertexAttribPointer(program.aPosition, 2, GLES20.GL_FLOAT, false, 0, quadPositions)
        GLES20.glEnableVertexAttribArray(program.aTexCoord)
        quadTexCoords.position(0)
        GLES20.glVertexAttribPointer(program.aTexCoord, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(program.aPosition)
        GLES20.glDisableVertexAttribArray(program.aTexCoord)
        GLES20.glBindTexture(textureTarget, 0)
    }

    private fun createTexture(target: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private class ShaderProgram(vertexSource: String, fragmentSource: String) {
        val id: Int = linkProgram(vertexSource, fragmentSource)
        val aPosition = GLES20.glGetAttribLocation(id, "aPosition")
        val aTexCoord = GLES20.glGetAttribLocation(id, "aTexCoord")
        val uPosMatrix = GLES20.glGetUniformLocation(id, "uPosMatrix")
        val uTexMatrix = GLES20.glGetUniformLocation(id, "uTexMatrix")
        val uTexture = GLES20.glGetUniformLocation(id, "uTexture")
        val uTexelSize = GLES20.glGetUniformLocation(id, "uTexelSize")
        val uPreset = GLES20.glGetUniformLocation(id, "uPreset")
        val uExposure = GLES20.glGetUniformLocation(id, "uExposure")
        val uContrast = GLES20.glGetUniformLocation(id, "uContrast")
        val uBlacks = GLES20.glGetUniformLocation(id, "uBlacks")
        val uWhites = GLES20.glGetUniformLocation(id, "uWhites")
        val uBloom = GLES20.glGetUniformLocation(id, "uBloom")
        val uGrain = GLES20.glGetUniformLocation(id, "uGrain")
        val uGrainSeed = GLES20.glGetUniformLocation(id, "uGrainSeed")
        val uSkyUp = GLES20.glGetUniformLocation(id, "uSkyUp")
        val uSharpness = GLES20.glGetUniformLocation(id, "uSharpness")
        val uRedWeight = GLES20.glGetUniformLocation(id, "uRedWeight")
        val uFoliageLift = GLES20.glGetUniformLocation(id, "uFoliageLift")
        val uSkySuppress = GLES20.glGetUniformLocation(id, "uSkySuppress")
        val uHueCos = GLES20.glGetUniformLocation(id, "uHueCos")
        val uHueSin = GLES20.glGetUniformLocation(id, "uHueSin")
        val uSaturation = GLES20.glGetUniformLocation(id, "uSaturation")
        val uSwapMode = GLES20.glGetUniformLocation(id, "uSwapMode")

        fun release() {
            if (id != 0) GLES20.glDeleteProgram(id)
        }

        companion object {
            private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
                val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
                val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
                val program = GLES20.glCreateProgram()
                GLES20.glAttachShader(program, vertex)
                GLES20.glAttachShader(program, fragment)
                GLES20.glLinkProgram(program)
                val linked = IntArray(1)
                GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
                GLES20.glDeleteShader(vertex)
                GLES20.glDeleteShader(fragment)
                if (linked[0] == 0) {
                    val log = GLES20.glGetProgramInfoLog(program)
                    GLES20.glDeleteProgram(program)
                    throw IllegalStateException("Shader program link failed: $log")
                }
                return program
            }

            private fun compileShader(type: Int, source: String): Int {
                val shader = GLES20.glCreateShader(type)
                GLES20.glShaderSource(shader, source)
                GLES20.glCompileShader(shader)
                val compiled = IntArray(1)
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
                if (compiled[0] == 0) {
                    val log = GLES20.glGetShaderInfoLog(shader)
                    GLES20.glDeleteShader(shader)
                    throw IllegalStateException("Shader compile failed: $log")
                }
                return shader
            }
        }
    }
}

class SpectralGlView(context: Context) : GLSurfaceView(context) {

    val renderer: SpectralRenderer
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Set by the camera layer; receives a fresh SurfaceTexture whenever the GL surface is (re)created. */
    var onSurfaceTextureReady: ((SurfaceTexture) -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        renderer = SpectralRenderer { surfaceTexture ->
            surfaceTexture.setOnFrameAvailableListener { requestRender() }
            mainHandler.post { onSurfaceTextureReady?.invoke(surfaceTexture) }
        }
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun updateSettings(settings: CameraSettings) {
        renderer.updateSettings(settings)
        requestRender()
    }

    fun setSourceSize(width: Int, height: Int) {
        renderer.setSourceSize(width, height)
        requestRender()
    }

    fun configureSource(width: Int, height: Int, rotationDegrees: Int) {
        renderer.configureSource(width, height, rotationDegrees)
        requestRender()
    }

    fun setSourceRotation(degrees: Int) {
        renderer.setSourceRotation(degrees)
        requestRender()
    }

    /** Runs the GPU filter chain over a captured still on the GL thread. */
    suspend fun process(bitmap: Bitmap, settings: CameraSettings): Bitmap =
        suspendCancellableCoroutine { continuation ->
            queueEvent {
                try {
                    continuation.resume(renderer.processBitmap(bitmap, settings))
                } catch (t: Throwable) {
                    continuation.resumeWithException(t)
                }
            }
        }
}

private fun floatBufferOf(vararg values: Float): FloatBuffer =
    ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(values)
            position(0)
        }

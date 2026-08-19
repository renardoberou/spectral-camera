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
import com.renardoberou.spectralcamera.BuildConfig
import com.renardoberou.spectralcamera.core.CameraSettings
import com.renardoberou.spectralcamera.core.ChannelSwapMode
import com.renardoberou.spectralcamera.core.FilmLookLibrary
import com.renardoberou.spectralcamera.core.LookFamily
import com.renardoberou.spectralcamera.core.SpectralPreset
import com.renardoberou.spectralcamera.core.SharedFilmProfile
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

internal const val FRAGMENT_BODY = """
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
uniform float uAutoLo;
uniform float uAutoHi;
uniform float uIntensity;
uniform float uZebra;
// Temporary diagnostic view (2026-07-24): visualizes the material classifier
// signals as false colour instead of the finished look, to investigate a
// device-observed blocky/patchy artifact on dark surfaces with small
// reflective points. R = sky-suppression signal (skyDown), G = foliage/veg
// classification, B = the deep-shadow classifier confidence gate (conf) -
// exactly the "chromaticity is numerically unstable near black" zone the
// comment below already calls out. Written as a side effect from inside
// irLuminance()/presetColor() so no function signature needs to change;
// read once at the very end of main(), same override pattern as uZebra.
// Intended to be removed once the artifact is root-caused - not a shipped
// feature.
uniform float uDebugClassifier;
vec3 gClassifierDebug = vec3(0.0);
uniform float uSharpness;
uniform float uRedWeight;
uniform float uFoliageLift;
uniform float uSkySuppress;
uniform float uHueCos;
uniform float uHueSin;
uniform float uSaturation;
uniform int uSwapMode;
uniform vec2 uSkyUp;

// ---- structured film-look parameters (FilmLookLibrary, core/FilmLook.kt) --
// Rendering engine reads these generically; the Kotlin-side look table is
// the only place per-stock numbers live. Only the active preset's family
// (mono XOR aero) is actually consumed per draw call.
uniform vec4 uMonoCurve;   // lo, span, toePow, k
uniform vec4 uMonoCurve2;  // ceiling, woodLift, skyStrength, waterFloor
uniform vec4 uAeroTone;    // curveMix, satCap, magentaBoost, skyDepthBoost
uniform vec4 uAeroTone2;   // gold, fade, (reserved), (reserved)
uniform vec4 uHaloGrain;   // haloThreshold, haloTight, haloWide, grainClump
uniform vec4 uStdTone;     // warmth, tealShadows, saturation, contrast
uniform vec4 uStdTone2;    // toeLift, ceiling, redBias, blueBias
uniform vec4 uStdTone3;    // monoMix, panRed, -, -
uniform vec3 uHaloTint;    // halation dye colour (CineStill = red)
uniform float uGrainBias;      // per-stock grain amplitude multiplier
uniform float uGrainBase;      // per-stock always-on baseline grain (film is never grainless)
uniform float uAcutanceBias;   // per-stock structure bias, adds to uSharpness
// Shared Fuji-inspired stage. w in each vector is not used; the stage is
// enabled by uSharedDensity.w; legacy spectral families receive conservative
// post-transform refinement values and visible profiles receive stock values.
uniform vec4 uSharedTone;       // toe, shoulder, highlight chroma compression, reserved
uniform vec4 uSharedProtection; // skin, foliage, sky, neutral confidence weights
uniform vec4 uSharedDensity;    // density, chroma compression, blue density, enabled
uniform vec4 uSharedHueA;       // red, yellow, green, cyan sector weights
uniform vec2 uSharedHueB;       // blue, magenta sector weights

float lumaOf(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

float hashNoise(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y) * 2.0 - 1.0;
}

float grainHash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

// Smooth value noise: interpolation between lattice hashes gives SPATIALLY
// STRUCTURED clumps instead of per-pixel white noise - the difference between
// film grain and sensor noise at print scale.
float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(grainHash(i), grainHash(i + vec2(1.0, 0.0)), u.x),
        mix(grainHash(i + vec2(0.0, 1.0)), grainHash(i + vec2(1.0, 1.0)), u.x),
        u.y);
}

// Two octaves of clumpy value noise, zero-centered; scales are in
// 720p-normalized pixels (grain clump ~1.6px / ~3.4px at 720 tall).
float filmGrain(vec2 uv, float seed) {
    vec2 g = uv + vec2(seed * 17.31, seed * 9.77);
    float n = valueNoise(g / 1.6) * 0.65 + valueNoise(g / 3.4) * 0.35;
    return n - 0.5;
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
// cc is the CLASSIFICATION colour: chroma-denoised (small bilateral) so the
// material classifiers do not flicker on per-pixel/JPEG-block chroma noise.
// Tone still comes from the full-detail c.
vec3 aerochrome(vec3 c, vec3 cc, float gold, float skyMask, float skyT, float smoothLuma,
    float curveMix, float satCap, float magentaBoost, float skyDepthBoost) {
    float r = c.r;
    float g = c.g;
    float b = c.b;
    float luma = lumaOf(c);

    // ---- physically-grounded EIR / Aerochrome model -----------------------
    // Real film: 3 layers sense GREEN, RED, NIR; a yellow filter blocks BLUE.
    // Output remap: R<-NIR, G<-Red, B<-Green, with NIR kept as its own clean
    // channel (it does not contaminate the others). We synthesise NIR from a
    // vegetation proxy (no real IR channel exists on a phone sensor). Swatch-
    // validated against the canonical EIR targets: foliage->hot red, red
    // objects->green, skin->sallow yellow, blue sky->deep dark, greys->pale
    // neutral.

    // NIR fires for GREEN-dominant pixels (foliage) and is suppressed on
    // RED-dominant pixels (skin, red paint) - that gate is what keeps flesh
    // from going red. Dark-foliage lift keeps shadowed canopy reading as veg.
    // ---- exposure-invariant classification via chromaticity ---------------
    // Raw channel differences shrink as exposure drops, which made shadowed
    // foliage go muddy brown and let red leak into blue water (purple pools).
    // Chromaticity (each channel's share of r+g+b) is invariant to exposure,
    // so a leaf classifies as a leaf whether sunlit or in shadow.
    float total = cc.r + cc.g + cc.b + 0.001;
    float nr = cc.r / total;
    float ng = cc.g / total;
    float nb = cc.b / total;

    float greenDom = smoothstep(0.0, 0.05, ng - nr);
    float grn = smoothstep(-0.01, 0.08, ng - nb);
    float notBlueC = 1.0 - smoothstep(0.0, 0.06, nb - max(nr, ng));
    float veg = clamp(grn * notBlueC * greenDom, 0.0, 1.0);

    // Olive/yellow-green foliage: chlorophyll present but nr approaches ng
    // (naturally yellow-green species, partial senescence), which fails the
    // strict greenDom gate above and fell through to the generic warm base,
    // rendering real foliage as a flat toxic-yellow patch. Pavement/concrete/
    // facades sit at the same near-zero ng-nr but never reach this ng-nb
    // margin (measured ~0.20+ on real foliage vs <=0.06 on built surfaces),
    // so that alone safely separates them without a texture gate.
    float oliveGreenBlue = smoothstep(0.12, 0.22, ng - nb);
    float nearNeutralRG = 1.0 - smoothstep(0.03, 0.09, abs(ng - nr));
    float oliveVeg = clamp(oliveGreenBlue * nearNeutralRG * notBlueC * (1.0 - veg), 0.0, 1.0);

    // Water/glass is CYAN-LEANING blue (green share well above red) and keeps
    // the vivid indigo rendering. Skylight-lit shadow on walls is NEUTRAL-
    // leaning blue and goes DARK - which is what the film's yellow filter
    // actually does to blue light. This split removes the blue wash on shaded
    // building faces without touching the pool.
    float blueC = smoothstep(0.030, 0.10, nb - max(nr, ng));
    float waterC = smoothstep(0.05, 0.14, ng - nr) * smoothstep(0.02, 0.08, nb - nr);
    float surfCalm = 1.0 - smoothstep(0.015, 0.06, abs(luma - smoothLuma));
    // cyan-vivid is for water and glass - SMOOTH surfaces. Backlit palm
    // fronds are cyan-leaning but textured; without this gate they flipped
    // vivid blue.
    float cyanC = smoothstep(0.025, 0.09, min(ng, nb) - nr) * surfCalm;
    float vividBlue = clamp(waterC * max(blueC, smoothstep(0.02, 0.08, nb - nr)) + cyanC * 0.6, 0.0, 1.0);
    float plainBlue = clamp(blueC * (1.0 - waterC), 0.0, 1.0);
    float blueBright = clamp(luma * 1.35 + 0.02, 0.0, 1.0) * smoothstep(0.10, 0.35, luma);
    // bright sky-gaps through the canopy render pale sky-blue, not cobalt blobs
    float paleGap = smoothstep(0.55, 0.85, luma) * 0.5;
    vec3 blueHue = mix(vec3(0.03, 0.08 + ng * 0.35, 0.85), vec3(0.42, 0.58, 0.95), paleGap);
    vec3 blueOut = blueHue * blueBright;

    // ---- P1: water signals FIRST so foliage can respect water sanctity ----
    float weakGreen = smoothstep(0.015, 0.06, ng - nr) * smoothstep(0.0, 0.04, ng - nb);
    float chromaDistC = max(max(abs(nr - 0.3333), abs(ng - 0.3333)), abs(nb - 0.3333));
    float lowChroma = 1.0 - smoothstep(0.045, 0.10, chromaDistC);
    float surfSmooth = 1.0 - smoothstep(0.015, 0.06, abs(luma - smoothLuma));
    float murky = weakGreen * lowChroma * surfSmooth * (1.0 - veg) * (1.0 - vividBlue);

    // ---- P1.3: chroma-energy floor + coherent-cast guard --------------------
    // Large near-neutral casts (through-glass walls, haze) sit close to the
    // neutral point in the CLASSIFICATION average; real foliage never does.
    // This kills the red speckle rain without touching genuine vegetation.
    // Confidence-aware neutral guard. The old greyC protection ran after
    // vividBlue had already accepted small blue/cyan differences as a strong
    // material decision. On pale walls and buildings that let JPEG/chroma
    // noise become blue/lilac islands before the cream fallback could help.
    // Keep the signal continuous, use the smoothed local luma only for
    // continuity, and exempt credible foliage/water evidence so the
    // Aerochrome signature remains authoritative where it should.
    float neutralChromaConfidence = 1.0 - smoothstep(0.035, 0.10, chromaDistC);
    float neutralGreenBalance = 1.0 - smoothstep(0.0, 0.05, abs(ng - 0.3333));
    float neutralReliability = smoothstep(0.08, 0.20, luma);
    float neutralSurfaceConfidence = neutralChromaConfidence * neutralGreenBalance
        * neutralReliability * surfSmooth;
    float competingMaterial = clamp(max(veg + oliveVeg, waterC), 0.0, 1.0);
    float neutralArtifactConfidence = clamp(
        neutralSurfaceConfidence * (1.0 - competingMaterial), 0.0, 1.0);
    // Development-only classifier-stage diagnostic: R=neutral confidence,
    // G=remaining blue/cyan authority, B=credible foliage authority. This is
    // intentionally captured before the false-colour output and finishing
    // stages so the confirmed artifact can be localized without guessing
    // from the final JPEG.
    gClassifierDebug = vec3(
        neutralArtifactConfidence,
        vividBlue * (1.0 - neutralArtifactConfidence * 0.90),
        clamp(veg + oliveVeg, 0.0, 1.0)
    );
    vividBlue *= 1.0 - neutralArtifactConfidence * 0.90;
    // Re-evaluate murky water after the neutral guard changes blue authority.
    murky = weakGreen * lowChroma * surfSmooth * (1.0 - veg) * (1.0 - vividBlue);

    float chromaFloor = smoothstep(0.035, 0.058, chromaDistC);
    // ---- P1.4: water sanctity - foliage output may not bleed into water ----
    float waterStrong = clamp(max(vividBlue, murky * 1.2), 0.0, 1.0);
    float vegAll = clamp(veg + oliveVeg, 0.0, 1.0) * chromaFloor * (1.0 - waterStrong * 0.9);

    // ---- P1.1 + P1.2: tone-preserving colorization on a continuous hue ----
    // manifold. Hue position follows species/vigor (deep green -> crimson,
    // yellow-green/olive -> coral) exactly as varying NIR reflectance renders
    // on the real film; luminance keeps the SOURCE tonal structure with an
    // expanded spread, so canopy clump shadows and leaf texture survive
    // instead of flattening into crimson upholstery.
    // Authentic Aerochrome hue: IR-reflective leaves reflect BOTH near-IR
    // (renders red) AND green visible light (renders blue); their ratio is
    // what makes real foliage MAGENTA-red, not pure red. We add a blue
    // component scaled by the source's green-visible strength, giving the
    // crimson->magenta manifold the film is known for.
    float species = clamp((ng - nr) / 0.085, 0.0, 1.0);
    // green-visible share drives the magenta lift; grass (thin blades, high
    // green share) skews bluer/magenta, broadleaf canopy stays redder - the
    // documented "grass shows a bluer hue of red" behaviour.
    float greenShare = smoothstep(0.30, 0.42, ng);
    // magentaBoost is the per-look family dial: Soft/Faded pull this toward
    // a plainer red, Dense pushes it toward a more vivid magenta-crimson.
    // Ceiling raised 2026-07-24 (0.30+0.30*species -> 0.45+0.45*species):
    // measured against realistic foliage chromaticity, the old constants
    // capped magenta at 0.60 even on Dense's best-case pixel (deep healthy
    // green) and landed around 0.4-0.5 typically - the result stayed
    // majority folRed (red/crimson) and never actually reached the
    // characteristic pink/magenta on any existing preset, regardless of
    // scene or dial setting. This was a hard ceiling in the constants, not
    // a style restraint - real Aerochrome documentation and reviews
    // describe the effect as reaching magenta/pink, and the formula
    // couldn't get there. Re-verified in numpy before this change: Classic
    // now reaches 0.90 on deep healthy green (was 0.60) and 0.57 typical
    // (was 0.38); relative ordering across all variants is unchanged.
    float magenta = clamp(greenShare * (0.45 + 0.45 * species) * magentaBoost, 0.0, 1.0);
    vec3 folRed = mix(vec3(1.0, 0.46, 0.30), vec3(1.0, 0.06, 0.13), species);
    vec3 folMag = mix(folRed, vec3(0.92, 0.05, 0.48), magenta);
    float folL = clamp((luma - 0.15) * 1.55, 0.0, 1.0);
    float hiRoll = smoothstep(0.62, 0.95, folL) * 0.5;
    vec3 folCol = mix(folMag, vec3(1.0, 0.62, 0.72), hiRoll) * folL;

    // warmth requires actual RED participation (cyan can no longer read warm)
    float warmth = clamp(r * 0.72 + max(r, g) * 0.28, 0.0, 1.0);
    vec3 base = vec3(clamp(warmth * 0.95, 0.0, 1.0),
                     clamp(r * 0.78 + g * 0.10, 0.0, 1.0),
                     clamp(b * 0.85, 0.0, 1.0));
    // Per-look density on the DARK BLUE paths (vividBlue water/glass and
    // plainBlue skylight shadow): these paths previously had no family dial
    // at all, which made all five looks render a nearly identical dark
    // window on the real dusk validation photo (that region never enters
    // the skyMask path). skyDepthBoost doubles as the family density dial:
    // Dense crushes these regions harder, Soft/Faded lift them. A mid-tone
    // multiply is fine here (these paths sit at luma ~0.1-0.4, unlike the
    // near-black deepCol sky ramp that needed a power curve).
    float densityScale = clamp(2.0 - skyDepthBoost, 0.6, 1.4);
    vec3 ir = mix(base, folCol, vegAll);
    ir = mix(ir, clamp(blueOut * densityScale, 0.0, 1.0), vividBlue * (1.0 - vegAll));
    // skylight shadow: film-correct darkening, faintly cool
    vec3 shadowCol = vec3(luma * 0.64, luma * 0.67, luma * 0.80) * densityScale;
    // saturated blue OBJECTS (denim, paint) darken harder than faint skylight
    // shadow - the yellow filter kills blue light in proportion
    float blueObj = mix(0.72, 0.93, smoothstep(0.06, 0.12, chromaDistC));
    ir = mix(ir, shadowCol, plainBlue * (1.0 - vegAll) * (1.0 - vividBlue) * blueObj);

    // Murky (algae/sediment) water renders NIR-dark and NEUTRAL - never red.
    vec3 murkyCol = vec3(luma * 0.30, luma * 0.34, luma * 0.42);
    ir = mix(ir, murkyCol, murky * 0.55);

    // gold (orange-filter) variant: warmer foliage, cooler/teal sky
    ir.g = clamp(ir.g + gold * veg * 0.10, 0.0, 1.0);
    ir.b = clamp(ir.b - gold * 0.05, 0.0, 1.0);

    // neutral preservation on CHROMATICITY: slightly-warm sunlit grey still
    // counts as grey and renders the film's pale cream instead of hard yellow.
    //
    // FIX ("neon wall" bug, found on a real warm-lit stucco wall): a colour-
    // temperature CAST on an intrinsically neutral surface (warm low-sun
    // light, cool shade) shifts red vs. blue while leaving GREEN close to
    // its neutral 1/3 share; a genuinely saturated coloured object (paint,
    // foliage) shifts green too. That is a more direct, symmetric signal
    // than the old cool-only `coolCast` gate, which left warm-cast neutrals
    // with zero widening - they fell straight through to the warmth-driven
    // base colour and the reversal-saturation boost inflated it to a
    // saturated yellow instead of a pale gold/cream.
    float greenNeutral = 1.0 - smoothstep(0.0, 0.05, abs(ng - 0.3333));
    float greyWide = 1.0 - smoothstep(0.020, mix(0.075, 0.20, greenNeutral), chromaDistC);
    // Exclude genuine vegetation/water/murky - they can sit at similarly
    // modest chromaDistC in weakly-saturated cases (shaded olive foliage,
    // hazy pools) but must never be pulled toward neutral cream.
    float greyC = max(greyWide * surfSmooth, neutralArtifactConfidence)
        * smoothstep(0.25, 0.60, luma)
        * (1.0 - vegAll) * (1.0 - vividBlue) * (1.0 - murky);
    vec3 cream = vec3(clamp(luma * 1.04, 0.0, 1.0), luma, clamp(luma * 0.92, 0.0, 1.0));
    ir = mix(ir, cream, greyC * 0.85);

    // P1.5: unified reversal grade - strongly-colored man-made objects pass
    // through a soft dye pull instead of keeping native color, so playgrounds
    // and signage read as part of the same film frame, not stickers.
    float manMade = smoothstep(0.10, 0.16, chromaDistC)
        * (1.0 - vegAll) * (1.0 - vividBlue) * (1.0 - skyMask);
    vec3 dyeG = mix(ir, vec3(lumaOf(ir)) * vec3(1.06, 1.0, 0.90), 0.40);
    ir = mix(ir, dyeG, manMade * 0.45);

    // slide-film S-curve: crushed toe, rolled shoulder. curveMix is the
    // per-look contrast-character dial (Soft/Faded flatten it, Dense hardens it).
    vec3 s1 = ir * ir * (3.0 - 2.0 * ir);
    ir = mix(ir, s1, curveMix);

    // Baked-in reversal-film saturation, HEADROOM-LIMITED: the boost tapers
    // per pixel to the largest hue-preserving factor that keeps every channel
    // in range. A flat 1.18x hard-clipped vivid subjects to solid primaries
    // (texture-less max-red foliage, pure-yellow water); this keeps their
    // internal gradation while identical elsewhere.
    float il = lumaOf(ir);
    vec3 dSat = ir - vec3(il);
    vec3 tPos = mix(vec3(1000.0), (vec3(1.0 - il)) / max(dSat, vec3(0.0001)),
        step(vec3(0.0001), dSat));
    vec3 tNeg = mix(vec3(1000.0), vec3(il) / max(-dSat, vec3(0.0001)),
        step(vec3(0.0001), -dSat));
    vec3 tCh = min(tPos, tNeg);
    // satCap is the per-look reversal-film saturation headroom ceiling.
    float tSat = clamp(min(min(tCh.r, tCh.g), tCh.b), 1.0, satCap);
    // water keeps its transparency: no reversal-sat push on pools/ponds
    tSat = mix(tSat, 1.0, clamp(vividBlue + murky + plainBlue * 0.8, 0.0, 1.0) * 0.6);
    // FIX (part 2 of the neon-wall bug): a cast-neutral pixel the greyC step
    // above just pulled toward cream must not have its small residual
    // warmth re-inflated by this headroom boost - the boost is meant for
    // genuinely saturated subjects, not corrected neutrals.
    tSat = mix(tSat, 1.0, greyC * 0.7);
    ir = clamp(vec3(il) + dSat * tSat, 0.0, 1.0);


    // EIR sky: a single hue-locked ramp from deep blue to pale blue-white,
    // keyed monotonically on source luminance. No hue rotation and a gentle
    // slope, so 8-bit steps in blown-out gradients stay small, colourless,
    // and dither away instead of becoming coloured contour bands.
    vec3 deepCol = mix(
        mix(vec3(0.10, 0.20, 0.55), vec3(0.03, 0.09, 0.42), smoothstep(0.35, 0.95, skyT)),
        mix(vec3(0.07, 0.30, 0.48), vec3(0.02, 0.20, 0.36), smoothstep(0.35, 0.95, skyT)),
        gold);
    // skyDepthBoost is the per-look sky-density dial: >1 darkens/deepens the
    // clear-sky colour (Dense), <1 pales it toward a hazier vintage sky
    // (Soft/Faded), 1.0 leaves the reference Classic/Gold ramp untouched.
    //
    // FIX: a plain multiply has almost no visible effect once deepCol is
    // already near-black (a dark clear sky at dusk, found on a real photo
    // where Classic/Soft/Dense/Faded were nearly indistinguishable there
    // despite skyDepthBoost ranging 0.70-1.25). A power curve keyed on
    // skyDepthBoost has far more effect in exactly that low range while
    // skyDepthBoost=1.0 (Classic/Gold) stays an exact identity - no change
    // to those two already-tuned reference looks.
    float skyGamma = clamp(1.0 + (skyDepthBoost - 1.0) * 1.6, 0.35, 3.0);
    deepCol = clamp(pow(max(deepCol, vec3(0.0001)), vec3(skyGamma)), 0.0, 1.0);
    vec3 paleCol = mix(vec3(0.86, 0.90, 0.97), vec3(0.84, 0.92, 0.95), gold);
    // Drive the colour ramp from the SMOOTHED luma so JPEG luma plateaus do not
    // become colour plateaus (the source of the 8-bit banding). Fine per-pixel
    // detail is added back as a brightness-only modulation - no hue steps - so
    // clouds stay crisp. (Verified in simulation: peak adjacent-pixel colour
    // step drops below the source's own.)
    // Key the pale lift on CLOUDINESS, not brightness: clouds are bright AND
    // desaturated; a clear noon sky is bright AND saturated-blue and must stay
    // deep (the washed-lavender sky bug). Real EIR renders clear sky deep blue
    // at any brightness.
    float clearBlue = smoothstep(0.05, 0.14, nb - max(nr, ng));
    float lift = smoothstep(0.30, 1.0, smoothLuma) * (1.0 - clearBlue);
    vec3 skyCol = mix(deepCol, paleCol, lift);
    skyCol = clamp(skyCol + (luma - smoothLuma) * 0.6, 0.0, 1.0);
    ir = mix(ir, clamp(skyCol, 0.0, 1.0), skyMask * 0.94);
    return ir;
}

// ---- Monochrome IR film model (Rollei Infrared 400, research-derived) -------
// Stage 1: synthesize IR luminance (red-weighted + vegetation NIR boost, sky
// suppressed with gradient, skin lifted). Stage 2: H&D characteristic curve
// with a long toe (compressed but separated shadows), steep midsection, and a
// gently rolled shoulder so sunlit foliage sits at Zone VII-VIII TEXTURED and
// never clips to paper white. Grain and halation are applied by the caller.
// Curve shape and lift/sky strength are supplied by the caller from
// FilmLookLibrary (core/FilmLook.kt) - this function is stock-agnostic.
float irHDCurve(float e, float lo, float span, float toePow, float k, float ceiling) {
    // Aviphot Pan 200 / Rollei IR 400 characteristic curve, calibrated against
    // Serger's densitometry (Zone I-X neg densities 0.07..1.27, DD-X 1+4) and
    // validated on reference photos: soft compressed toe (Zone I-III sit low
    // but separated), steep midsection, gently rolled Reinhard shoulder.
    // Sunlit foliage lands ~0.82-0.85, sky ~0.01-0.15 with gradient, and the
    // ceiling stays below paper white (anti-clipping: the make-or-break rule).
    // Per-stock lo/span/toePow/k/ceiling reshape this same curve family into
    // Rollei/HIE/SFX/Moderate/Fine-Grain/Soft-Vintage without new code paths.
    float le = log2(max(e, 0.0005));
    float x = clamp((le + lo) / span, 0.0, 1.0);
    float toe = pow(x, toePow);
    float sh = toe * (1.0 + k) / (toe + k);
    return clamp(sh * ceiling + 0.008, 0.0, 1.0);
}

float irLuminance(vec3 c, vec3 cc, float veg, float smoothLuma, float skyT, float liftAmt, float skyStr, out float suppressOut) {
    // R72-filtered emulsion: blue is blocked, green heavily attenuated, so the
    // base signal is dominated by red (the film's declining NIR tail rides on
    // its red sensitisation).
    float irBase = 0.78 * c.r + 0.19 * c.g + 0.03 * c.b;

    // Wood effect: chlorophyll goes from ~5% reflectance in visible red to
    // ~50% in NIR. The lift is saturation-aware (sigmoid, not multiply) so
    // sunlit foliage lands at Zone VII-VIII TEXTURED, never clipped.
    // liftAmt is the per-stock Wood-effect strength from FilmLookLibrary
    // (HIE reaches deepest into NIR, SFX/Fine-Grain are extended-red only).
    // Deep-shadow confidence: chromaticity is numerically unstable near black
    // (tiny noisy RGB over tiny totals), which rendered night shadows as a
    // blocky classifier patchwork. Below the floor everything falls back to
    // the plain film response.
    float conf = smoothstep(0.035, 0.12, smoothLuma);
    gClassifierDebug.b = conf;
    veg = veg * conf;
    // P1.1 (mono): tone-modulated lift - darker canopy clumps lift less than
    // sunlit ones, so the Wood effect keeps intra-canopy structure instead of
    // fusing into a white sheet. Spread validated on the overcast field shot.
    float toneMod = 0.55 + 0.70 * clamp((irBase - 0.28) * 1.7, 0.0, 1.0);
    float ir = irBase + veg * smoothstep(0.0, 0.80, 1.0 - irBase) * liftAmt * toneMod;

    // chroma-denoised classification colour: stops the sky/skin detectors
    // flickering on JPEG chroma-block noise (the leopard-spot artifact)
    float total = cc.r + cc.g + cc.b + 0.001;
    float nrr = cc.r / total;
    float ngg = cc.g / total;
    float nbb = cc.b / total;

    // Sky: Rayleigh scattering is absent in NIR, so sky goes Zone I-II.
    // Two detectors: chromaticity (saturated blue sky) plus an absolute
    // pale/hazy-sky signal (bright with B >= G >= R), which chromaticity
    // alone misses. Vegetation is excluded (foliage always has G >= B).
    // Proportional ramp (upper edge 0.20, not 0.11): the old near-binary
    // classifier snapped from ~0 to ~1 across its decision boundary and
    // imprinted that boundary as a hard tonal edge (the "sky blob" artifact
    // seen on-device). A wide ramp turns partial blueness into partial,
    // spatially smooth density instead.
    float skyChroma = smoothstep(0.03, 0.20, nbb - max(nrr, ngg * 0.97));
    // Hazy detector needs DECISIVE blueness (b-g edge 0.04..0.12): neutral
    // overcast cloud (b ~= g) must not partially fire and stamp grey smudges.
    float skyHazy = smoothstep(0.34, 0.85, cc.b)
        * smoothstep(0.04, 0.12, cc.b - cc.g)
        * smoothstep(-0.01, 0.05, cc.b - cc.r);
    // Neutral-overcast detector (2026-07-24, second finding from device
    // re-shoot): a real photo of a genuinely grey/white overcast sky (not
    // blue-hazy, truly neutral - b approx g approx r) measured at ~0.90 luma
    // in the OUTPUT with zero suppression - skyChroma requires blue
    // dominance and skyHazy requires a DECISIVE b-g gap, so a flat neutral
    // sky satisfies neither and was passing through completely unprocessed.
    // Real IR film suppresses sky regardless of whether it's blue or grey
    // (Rayleigh scattering is absent in NIR either way) - this was a real
    // gap, not a deliberate design choice. Gated on brightness + LOW
    // saturation (not high, unlike skyHazy) + skyT position, so it only
    // catches genuinely flat/bright/neutral regions positioned sky-ward in
    // frame, not a bright neutral shirt or wall lower in the picture.
    float ccLuma = (cc.r + cc.g + cc.b) / 3.0;
    float ccSat = max(cc.r, max(cc.g, cc.b)) - min(cc.r, min(cc.g, cc.b));
    float skyOvercast = smoothstep(0.68, 0.90, ccLuma)
        * (1.0 - smoothstep(0.03, 0.12, ccSat))
        * smoothstep(0.35, 0.75, skyT);
    float skyDown = clamp(skyChroma * 0.9 + skyHazy * 0.35 + skyOvercast * 0.30, 0.0, 1.0) * (1.0 - veg * 0.7) * conf;
    gClassifierDebug.r = skyDown;
    // skyStr is the per-stock sky-suppression strength from FilmLookLibrary
    // (HIE: denser skies; SFX/Fine-Grain: milder).
    // positional: zenith sky suppresses fully; low-in-frame blue (pools,
    // reflections) darkens far less, keeping Zone II texture instead of void.
    // Suppression is capped: fully classified sky keeps a Zone I-II floor
    // (like Aerochrome's pale-sky path) rather than going to void black.
    float suppress = min(skyDown * (skyStr * (0.59 + 0.50 * skyT)), 0.86);
    ir = ir * (1.0 - suppress);
    suppressOut = suppress;

    // Water absorbs NIR heavily -> near black
    float water = skyDown * smoothstep(0.0, 0.40, smoothLuma) * (1.0 - veg);
    ir = ir * (1.0 - water * 0.20);

    // Skin: NIR penetrates a few mm -> pale, smooth, waxy
    float skin = smoothstep(0.03, 0.12, nrr - ngg)
        * smoothstep(-0.01, 0.06, ngg - nbb)
        * smoothstep(0.20, 0.70, smoothLuma);
    ir = ir * (1.0 + skin * 0.30);

    // Bright neutral surfaces (clouds, light stone) reflect IR well
    float bright = smoothstep(0.62, 0.90, irBase) * (1.0 - veg) * (1.0 - skyDown * 0.5);
    ir = ir * (1.0 + bright * 0.15);
    return clamp(ir, 0.0, 1.0);
}

// Physics-correct halation (threshold-then-spread): only energy exceeding
// the film's latitude halates. Light passes through the emulsion, reflects
// off the base, and re-exposes the layers from behind - ORANGE right at the
// overexposed border (reflected energy still reaches the green layer), RED
// further out. Keyed on SOURCE exposure, never output tone, and it must
// never bleed from midtones - that is the classic amateur-emulation tell.
// Returns vec2(tight ring, wide ring), each 0..1. Radii are normalized to a
// 720px-tall frame so the halo is the same visual size at any resolution.
vec2 halationEnergy(vec2 uv, float threshold) {
    float span = max(1.0 - threshold, 0.001);
    vec2 hUnit = uTexelSize / (720.0 * uTexelSize.y);
    vec2 rT = hUnit * 2.6;
    vec2 rW = hUnit * 7.0;
    float tight = 0.0;
    tight += max(0.0, lumaOf(texture2D(uTexture, uv + vec2(rT.x, 0.0)).rgb) - threshold);
    tight += max(0.0, lumaOf(texture2D(uTexture, uv - vec2(rT.x, 0.0)).rgb) - threshold);
    tight += max(0.0, lumaOf(texture2D(uTexture, uv + vec2(0.0, rT.y)).rgb) - threshold);
    tight += max(0.0, lumaOf(texture2D(uTexture, uv - vec2(0.0, rT.y)).rgb) - threshold);
    tight += max(0.0, lumaOf(texture2D(uTexture, uv + rT * 0.707).rgb) - threshold);
    tight += max(0.0, lumaOf(texture2D(uTexture, uv - rT * 0.707).rgb) - threshold);
    tight += max(0.0, lumaOf(texture2D(uTexture, uv + vec2(rT.x, -rT.y) * 0.707).rgb) - threshold);
    tight += max(0.0, lumaOf(texture2D(uTexture, uv + vec2(-rT.x, rT.y) * 0.707).rgb) - threshold);
    float wide = 0.0;
    wide += max(0.0, lumaOf(texture2D(uTexture, uv + vec2(rW.x, 0.0)).rgb) - threshold);
    wide += max(0.0, lumaOf(texture2D(uTexture, uv - vec2(rW.x, 0.0)).rgb) - threshold);
    wide += max(0.0, lumaOf(texture2D(uTexture, uv + vec2(0.0, rW.y)).rgb) - threshold);
    wide += max(0.0, lumaOf(texture2D(uTexture, uv - vec2(0.0, rW.y)).rgb) - threshold);
    wide += max(0.0, lumaOf(texture2D(uTexture, uv + rW * 0.707).rgb) - threshold);
    wide += max(0.0, lumaOf(texture2D(uTexture, uv - rW * 0.707).rgb) - threshold);
    wide += max(0.0, lumaOf(texture2D(uTexture, uv + vec2(rW.x, -rW.y) * 0.707).rgb) - threshold);
    wide += max(0.0, lumaOf(texture2D(uTexture, uv + vec2(-rW.x, rW.y) * 0.707).rgb) - threshold);
    return vec2(tight, wide) / (8.0 * span);
}

float hueSectorWeight(float hueDegrees, float centerDegrees, float widthDegrees) {
    float distanceDegrees = abs(mod(hueDegrees - centerDegrees + 540.0, 360.0) - 180.0);
    float inside = 1.0 - step(widthDegrees * 0.5, distanceDegrees);
    return inside * max(0.0, cos(3.14159265 * distanceDegrees / widthDegrees));
}

float sharedProtectionConfidence(vec3 c, float luma, float chroma) {
    float reliability = smoothstep(0.035, 0.16, luma);
    float warm = smoothstep(0.0, 0.12, c.r - c.b) * smoothstep(-0.04, 0.10, c.g - c.b);
    float foliage = smoothstep(0.02, 0.16, c.g - c.b) * smoothstep(-0.02, 0.12, c.g - c.r);
    float sky = smoothstep(0.02, 0.14, c.b - max(c.r, c.g)) * smoothstep(0.22, 0.60, luma);
    float neutral = 1.0 - smoothstep(0.04, 0.18, chroma);
    return reliability * clamp(max(max(warm * uSharedProtection.x, foliage * uSharedProtection.y),
        max(sky * uSharedProtection.z, neutral * uSharedProtection.w)), 0.0, 1.0);
}

// Shared visible-spectrum refinement. It runs after presetColor(), so the
// synthetic-NIR and mono-IR classifiers always see the unmodified source.
// Hue-sector density is continuous and luminance-weighted; confidence only
// reduces the stock mapping rather than creating hard segmentation seams.
vec3 sharedFujiStage(vec3 c) {
    if (uSharedDensity.w < 0.5) return c;
    float l = lumaOf(c);
    float chroma = max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b);
    float protected = sharedProtectionConfidence(c, l, chroma);

    float toe = uSharedTone.x * (1.0 - smoothstep(0.0, 0.34, l));
    float shoulder = uSharedTone.y * smoothstep(0.62, 1.0, l);
    c = mix(c, c + vec3(toe), 1.0 - protected);
    c = mix(c, c - (c - vec3(0.72)) * shoulder, 1.0 - protected);

    float hue = degrees(atan(c.g - lumaOf(c), c.r - lumaOf(c)));
    float sector = hueSectorWeight(hue, 0.0, 60.0) * uSharedHueA.x
        + hueSectorWeight(hue, 60.0, 60.0) * uSharedHueA.y
        + hueSectorWeight(hue, 120.0, 60.0) * uSharedHueA.z
        + hueSectorWeight(hue, 180.0, 60.0) * uSharedHueA.w
        + hueSectorWeight(hue, 240.0, 60.0) * uSharedHueB.x
        + hueSectorWeight(hue, 300.0, 60.0) * uSharedHueB.y;
    float midtone = smoothstep(0.08, 0.30, l) * (1.0 - smoothstep(0.72, 0.98, l));
    float density = uSharedDensity.x * sector * midtone * (1.0 - protected);
    float compression = clamp(uSharedDensity.y + uSharedTone.z * smoothstep(0.45, 1.0, l), 0.0, 1.0);
    vec3 chromaVector = c - vec3(l);
    float highlightCompression = uSharedTone.z * smoothstep(0.62, 1.0, l);
    chromaVector *= (1.0 + density) * (1.0 - compression * smoothstep(0.35, 1.0, chroma))
        * (1.0 - highlightCompression * smoothstep(0.12, 0.42, chroma));
    float blueSector = hueSectorWeight(hue, 240.0, 60.0) + hueSectorWeight(hue, 200.0, 42.0);
    chromaVector.b *= 1.0 + uSharedDensity.z * blueSector * midtone * (1.0 - protected);
    return clamp(vec3(l) + chromaVector, 0.0, 1.0);
}

// ---- Classic (non-IR) film engine: uPreset 12-17. One generic path driven
// entirely by StandardFilmLook dials; deliberately independent of the IR
// engines (zero changes to monoLook/aeroLook paths).
vec3 standardFilm(vec3 src, float smoothLuma) {
    vec3 c = src;
    float luma = lumaOf(c);
    // white-balance character: + = warm (Ektar), - = tungsten-cool (800T day)
    float wb = uStdTone.x;
    c.r = clamp(c.r * (1.0 + wb), 0.0, 1.0);
    c.b = clamp(c.b * (1.0 - wb * 0.9), 0.0, 1.0);
    // teal shadow split-tone (CineStill's tungsten shadow character)
    float shadowW = 1.0 - smoothstep(0.0, 0.55, luma);
    c = clamp(c + vec3(-0.6, 0.25, 1.0) * (uStdTone.y * 0.25) * shadowW, 0.0, 1.0);
    // panchromatic mono conversion (Tri-X): slightly red-favouring mix
    float pan = dot(c, vec3(uStdTone3.y, 0.50, 0.50 - uStdTone3.y));
    c = mix(c, vec3(pan), uStdTone3.x);
    // negative-film tone: contrast s-curve, then lifted-toe..shoulder remap
    vec3 s1 = c * c * (3.0 - 2.0 * c);
    c = mix(c, s1, uStdTone.w);
    c = vec3(uStdTone2.x) + c * (uStdTone2.y - uStdTone2.x);
    // saturation with per-channel bias (Ektar's red/blue pop)
    float il = lumaOf(c);
    vec3 d = c - vec3(il);
    d.r *= uStdTone2.z;
    d.b *= uStdTone2.w;
    c = clamp(vec3(il) + d * uStdTone.z, 0.0, 1.0);
    // halation in the stock's own dye colour - CineStill's is RED (no remjet)
    // edgeGate was one-sided (2026-07-24, second finding from device re-shoot):
    // smoothstep(src - smoothLuma) only fires where THIS pixel is itself
    // brighter than its blurred surround - i.e. only the bright source's own
    // rim, which is usually already near-clipped, so the additive red tint
    // was invisible exactly where it mattered. A real CineStill night-lamp
    // photo showed zero red skew (measured R-B ~ -0.10, if anything blue)
    // in the dark ring immediately around a bright light - the halo never
    // reached the dark surround it's supposed to bleed into. abs() lets the
    // DARK side of the same edge qualify too, without opening the gate on
    // flat midtones (abs stays ~0 there) or flooding deep inside a large
    // bright source (abs stays ~0 there too, since interior pixels don't
    // differ from their own blurred neighbourhood). hal.x/hal.y still gate
    // the actual energy on the per-stock brightness threshold, unchanged.
    float edgeGate = smoothstep(0.015, 0.09, abs(lumaOf(src) - smoothLuma));
    vec2 hal = halationEnergy(vTexCoord, uHaloGrain.x);
    c += uHaloTint * (hal.x * uHaloGrain.y + hal.y * uHaloGrain.z) * edgeGate;
    return clamp(c, 0.0, 1.0);
}

vec3 presetColor(vec3 src, vec3 srcC, float skyMask, float skyT, float smoothLuma) {
    float luma = lumaOf(src);
    // synthetic NIR proxy shared by the IR presets: vegetation glows (Wood
    // effect) via greenness-over-blue plus a dark-foliage lift; sky = blueness
    // B&W IR model, separate from Aerochrome: exposure-invariant chromaticity
    // vegetation drives the Wood-effect glow, so foliage classifies the same
    // in sun or shadow (no more muddy shaded canopy in the mono presets).
    float totM = srcC.r + srcC.g + srcC.b + 0.001;
    float nrM = srcC.r / totM;
    float ngM = srcC.g / totM;
    float nbM = srcC.b / totM;
    float foliage = clamp(
        smoothstep(-0.01, 0.08, ngM - nbM)
            * smoothstep(0.0, 0.05, ngM - nrM)
            * (1.0 - smoothstep(0.0, 0.06, nbM - max(nrM, ngM))),
        0.0, 1.0);
    // Olive/yellow-green foliage (see the matching branch in aerochrome()):
    // fails the strict ngM-nrM gate above but is unambiguously vegetation by
    // its ngM-nbM margin, which pavement/concrete/facades never reach. Adds
    // the same Wood-effect brightness lift so this foliage type is treated
    // consistently across all presets, not just where a wrong hue made the
    // gap visible.
    float oliveGreenBlueM = smoothstep(0.12, 0.22, ngM - nbM);
    float nearNeutralRGM = 1.0 - smoothstep(0.03, 0.09, abs(ngM - nrM));
    float oliveFoliageM = oliveGreenBlueM * nearNeutralRGM
        * (1.0 - smoothstep(0.0, 0.06, nbM - max(nrM, ngM)));
    foliage = clamp(foliage + oliveFoliageM * (1.0 - foliage), 0.0, 1.0);
    float chromaDistM = max(max(abs(nrM - 0.3333), abs(ngM - 0.3333)), abs(nbM - 0.3333));
    foliage = foliage * smoothstep(0.035, 0.058, chromaDistM);
    // Shadow-canopy branch: shaded dense foliage picks up a blue skylight
    // cast and desaturates toward neutral, so the gates above drop it to
    // zero Wood lift and it crushes to a posterized black mass (the M3/A4
    // failure seen on-device). Green-over-red dominance with a mild
    // tolerated blue excess, dark regions only - sky (strong blue excess)
    // and neutral facades (no green margin) both stay excluded.
    float shadowVegM = smoothstep(0.012, 0.05, ngM - nrM)
        * (1.0 - smoothstep(0.03, 0.09, nbM - ngM))
        * (1.0 - smoothstep(0.28, 0.50, smoothLuma));
    foliage = max(foliage, shadowVegM * 0.75);
    gClassifierDebug.g = foliage;

    // Halation is a POINT-SOURCE effect: light punching through the emulsion
    // around genuinely bright spots. Keyed on local contrast (luma above the
    // smoothed surround) so a uniformly bright hazy sky or blown backlit
    // canopy no longer fogs the whole frame flat; discrete highlights still
    // bloom fully.
    float halo = 0.35 + 0.65 * smoothstep(0.015, 0.09, luma - smoothLuma);

    // ---- Monochrome IR: uPreset 0-5, one generic engine for all six stocks.
    // Curve/lift/sky/halo/water numbers all come from the uMonoCurve* /
    // uHaloGrain uniforms (Kotlin FilmLookLibrary.monoLookFor), so adding a
    // seventh stock never touches this function.
    if (uPreset <= 5) {
        float suppress = 0.0;
        float ir = irLuminance(src, srcC, foliage, smoothLuma, skyT, uMonoCurve2.y, uMonoCurve2.z, suppress);
        float m = irHDCurve(ir, uMonoCurve.x, uMonoCurve.y, uMonoCurve.z, uMonoCurve.w, uMonoCurve2.x);
        vec2 hal = halationEnergy(vTexCoord, uHaloGrain.x);
        m = clamp(m + (hal.x * uHaloGrain.y + hal.y * uHaloGrain.z) * halo, 0.0, 1.0);
        // Water floor + sheen: dark regions keep Zone-I tone and their
        // specular ripple back - IR water is never a void. Keys on BOTH
        // source darkness AND classified suppressed low-in-frame blue
        // (pools/reflections): a bright blue pool the shader itself darkens
        // was previously missed by the source-darkness test alone and
        // rendered as a dead void.
        float darkFloor = 1.0 - smoothstep(0.04, 0.14, smoothLuma);
        float waterLife = max(darkFloor, suppress * (1.0 - smoothstep(0.25, 0.55, skyT)));
        m = max(m, waterLife * uMonoCurve2.w);
        m = clamp(m + max(0.0, lumaOf(src) - smoothLuma) * waterLife * 0.8, 0.0, 1.0);
        return vec3(m);
    }

    // ---- Classic film: uPreset 12-18 --------------------------------
    if (uPreset >= 12) {
        return standardFilm(src, smoothLuma);
    }
    // ---- Aerochrome: uPreset 6-11, one shared colorimetry engine for all
    // six grades. Family-dial numbers come from uAeroTone* / uHaloGrain
    // (Kotlin FilmLookLibrary.aeroLookFor()). This is the only remaining
    // family after uPreset 0-5 (monochrome IR), so no further bound check.
    vec3 col = aerochrome(src, srcC, uAeroTone2.x, skyMask, skyT, smoothLuma,
        uAeroTone.x, uAeroTone.y, uAeroTone.z, uAeroTone.w);
    // Even protected stock shows a slight red halo around the brightest
    // elements; orange at the border, red further out. The local-contrast
    // gate keeps broad bright areas (sky) from ringing skylines - only
    // genuine local highlights halate.
    float edgeGate = smoothstep(0.015, 0.09, luma - smoothLuma);
    vec2 hal = halationEnergy(vTexCoord, uHaloGrain.x);
    vec3 haloTight = mix(vec3(1.0, 0.42, 0.18), vec3(1.0, 0.48, 0.16), uAeroTone2.x);
    vec3 haloWide = mix(vec3(0.95, 0.12, 0.08), vec3(0.95, 0.16, 0.08), uAeroTone2.x);
    col += (haloTight * hal.x * uHaloGrain.y + haloWide * hal.y * uHaloGrain.z) * edgeGate;
    // Faded/Vintage fade dial: lift blacks and pull toward a warm neutral
    // grey, an aged-print character none of the other grades apply.
    float fade = uAeroTone2.y;
    if (fade > 0.001) {
        float l = lumaOf(col);
        vec3 warmGrey = vec3(l) * vec3(1.06, 1.0, 0.90);
        col = mix(col, warmGrey, fade * 0.6);
        col = col * (1.0 - 0.10 * fade) + vec3(0.05, 0.045, 0.04) * fade;
    }
    return clamp(col, 0.0, 1.0);
}

void main() {
    vec3 raw = texture2D(uTexture, vTexCoord).rgb;
    float rawLuma = lumaOf(raw);
    // Capture-time auto-levels: gentle black/white points measured from the
    // still's own histogram (Kotlin side). Preview passes 0/1 = identity.
    vec3 src = clamp((raw - vec3(uAutoLo)) / max(uAutoHi - uAutoLo, 0.001), 0.0, 1.0);

    // ---- sky detection ---------------------------------------------------
    // Sky is a low-frequency phenomenon, so the mask is built from a wide
    // blur of the image: spatially smooth, immune to pixel noise, and bright
    // facades disqualify themselves because windows pull the wide average
    // down. A generous per-pixel gate then keeps the soft mask from bleeding
    // onto dark silhouettes (no matte lines on buildings or branches).
    float srcLuma = lumaOf(src);
    // Resolution-normalized noise coordinate: grain/dither cells stay a
    // constant FRACTION of the image regardless of capture size, so a 12MP
    // print shows film-like grain rather than pixel-fine digital speckle.
    vec2 pix0 = vTexCoord / max(uTexelSize, vec2(0.00001));
    vec2 grainUv = vTexCoord * 720.0;

    // ---- classification chroma denoise -------------------------------------
    // Small-radius luma-bilateral average feeding ONLY the material
    // classifiers (vegetation/sky/water/skin chromaticity). Phone JPEGs carry
    // per-pixel and 8x8-block chroma noise; the narrow smoothstep gates in the
    // classifiers flicker across it, which rendered foliage and bark as a
    // black/white leopard-spot patchwork. Averaging chroma over a few texels
    // (luma-gated so material edges stay crisp) removes the flicker; tone is
    // still taken from the full-detail pixel. Verified in simulation: spatial
    // classifier noise drops below the clean-source level with BETTER
    // classification accuracy than per-pixel.
    vec2 cr1 = uTexelSize * 2.0;
    vec2 cr2 = uTexelSize * 4.0;
    vec3 cAcc = raw;
    float cWsum = 1.0;
    vec3 cTap;
    float cW;
    cTap = texture2D(uTexture, vTexCoord + vec2( cr1.x, 0.0)).rgb;
    cW = 1.0 - smoothstep(0.0, 0.35, abs(lumaOf(cTap) - rawLuma)); cAcc += cTap * cW; cWsum += cW;
    cTap = texture2D(uTexture, vTexCoord + vec2(-cr1.x, 0.0)).rgb;
    cW = 1.0 - smoothstep(0.0, 0.35, abs(lumaOf(cTap) - rawLuma)); cAcc += cTap * cW; cWsum += cW;
    cTap = texture2D(uTexture, vTexCoord + vec2(0.0,  cr1.y)).rgb;
    cW = 1.0 - smoothstep(0.0, 0.35, abs(lumaOf(cTap) - rawLuma)); cAcc += cTap * cW; cWsum += cW;
    cTap = texture2D(uTexture, vTexCoord + vec2(0.0, -cr1.y)).rgb;
    cW = 1.0 - smoothstep(0.0, 0.35, abs(lumaOf(cTap) - rawLuma)); cAcc += cTap * cW; cWsum += cW;
    cTap = texture2D(uTexture, vTexCoord + vec2( cr2.x,  cr2.y) * 0.7).rgb;
    cW = 1.0 - smoothstep(0.0, 0.35, abs(lumaOf(cTap) - rawLuma)); cAcc += cTap * cW; cWsum += cW;
    cTap = texture2D(uTexture, vTexCoord + vec2(-cr2.x,  cr2.y) * 0.7).rgb;
    cW = 1.0 - smoothstep(0.0, 0.35, abs(lumaOf(cTap) - rawLuma)); cAcc += cTap * cW; cWsum += cW;
    cTap = texture2D(uTexture, vTexCoord + vec2( cr2.x, -cr2.y) * 0.7).rgb;
    cW = 1.0 - smoothstep(0.0, 0.35, abs(lumaOf(cTap) - rawLuma)); cAcc += cTap * cW; cWsum += cW;
    cTap = texture2D(uTexture, vTexCoord + vec2(-cr2.x, -cr2.y) * 0.7).rgb;
    cW = 1.0 - smoothstep(0.0, 0.35, abs(lumaOf(cTap) - rawLuma)); cAcc += cTap * cW; cWsum += cW;
    vec3 srcC = cAcc / cWsum;
    // auto-levels must hit the classification colour identically
    srcC = clamp((srcC - vec3(uAutoLo)) / max(uAutoHi - uAutoLo, 0.001), 0.0, 1.0);
    // ------------------------------------------------------------------------

    vec2 r1 = vec2(0.016, 0.016);
    vec2 r2 = vec2(0.032, 0.032);
    vec2 r3 = vec2(0.050, 0.050);
    vec3 acc = src;
    float wsum = 1.0;
    vec3 tap;
    float tw;
    tap = texture2D(uTexture, vTexCoord + r1 * vec2( 1.0,  0.0)).rgb;
    tw = 1.0 - smoothstep(0.0, 0.35, abs(lumaOf(tap) - srcLuma)); acc += tap * tw; wsum += tw;
    tap = texture2D(uTexture, vTexCoord + r1 * vec2(-1.0,  0.0)).rgb;
    tw = 1.0 - smoothstep(0.0, 0.35, abs(lumaOf(tap) - srcLuma)); acc += tap * tw; wsum += tw;
    tap = texture2D(uTexture, vTexCoord + r1 * vec2( 0.0,  1.0)).rgb;
    tw = 1.0 - smoothstep(0.0, 0.35, abs(lumaOf(tap) - srcLuma)); acc += tap * tw; wsum += tw;
    tap = texture2D(uTexture, vTexCoord + r1 * vec2( 0.0, -1.0)).rgb;
    tw = 1.0 - smoothstep(0.0, 0.35, abs(lumaOf(tap) - srcLuma)); acc += tap * tw; wsum += tw;
    tap = texture2D(uTexture, vTexCoord + r2 * vec2( 0.7,  0.7)).rgb;
    tw = 1.0 - smoothstep(0.0, 0.35, abs(lumaOf(tap) - srcLuma)); acc += tap * tw; wsum += tw;
    tap = texture2D(uTexture, vTexCoord + r2 * vec2(-0.7,  0.7)).rgb;
    tw = 1.0 - smoothstep(0.0, 0.35, abs(lumaOf(tap) - srcLuma)); acc += tap * tw; wsum += tw;
    tap = texture2D(uTexture, vTexCoord + r2 * vec2( 0.7, -0.7)).rgb;
    tw = 1.0 - smoothstep(0.0, 0.35, abs(lumaOf(tap) - srcLuma)); acc += tap * tw; wsum += tw;
    tap = texture2D(uTexture, vTexCoord + r2 * vec2(-0.7, -0.7)).rgb;
    tw = 1.0 - smoothstep(0.0, 0.35, abs(lumaOf(tap) - srcLuma)); acc += tap * tw; wsum += tw;
    tap = texture2D(uTexture, vTexCoord + r3 * vec2( 1.0,  0.0)).rgb;
    tw = 1.0 - smoothstep(0.0, 0.35, abs(lumaOf(tap) - srcLuma)); acc += tap * tw; wsum += tw;
    tap = texture2D(uTexture, vTexCoord + r3 * vec2(-1.0,  0.0)).rgb;
    tw = 1.0 - smoothstep(0.0, 0.35, abs(lumaOf(tap) - srcLuma)); acc += tap * tw; wsum += tw;
    tap = texture2D(uTexture, vTexCoord + r3 * vec2( 0.0,  1.0)).rgb;
    tw = 1.0 - smoothstep(0.0, 0.35, abs(lumaOf(tap) - srcLuma)); acc += tap * tw; wsum += tw;
    tap = texture2D(uTexture, vTexCoord + r3 * vec2( 0.0, -1.0)).rgb;
    tw = 1.0 - smoothstep(0.0, 0.35, abs(lumaOf(tap) - srcLuma)); acc += tap * tw; wsum += tw;
    tap = texture2D(uTexture, vTexCoord + r3 * vec2( 0.7,  0.7)).rgb;
    tw = 1.0 - smoothstep(0.0, 0.35, abs(lumaOf(tap) - srcLuma)); acc += tap * tw; wsum += tw;
    tap = texture2D(uTexture, vTexCoord + r3 * vec2(-0.7,  0.7)).rgb;
    tw = 1.0 - smoothstep(0.0, 0.35, abs(lumaOf(tap) - srcLuma)); acc += tap * tw; wsum += tw;
    tap = texture2D(uTexture, vTexCoord + r3 * vec2( 0.7, -0.7)).rgb;
    tw = 1.0 - smoothstep(0.0, 0.35, abs(lumaOf(tap) - srcLuma)); acc += tap * tw; wsum += tw;
    tap = texture2D(uTexture, vTexCoord + r3 * vec2(-0.7, -0.7)).rgb;
    tw = 1.0 - smoothstep(0.0, 0.35, abs(lumaOf(tap) - srcLuma)); acc += tap * tw; wsum += tw;
    vec3 blur = acc / wsum;

    float bLuma = lumaOf(blur);
    float bMax = max(blur.r, max(blur.g, blur.b));
    float bMin = min(blur.r, min(blur.g, blur.b));
    float bSat = (bMax - bMin) / max(bMax, 0.001);

    float skyT = clamp(dot(vTexCoord - vec2(0.5), uSkyUp) + 0.5, 0.0, 1.0);
    float skyPrior = smoothstep(0.18, 0.45, skyT);

    float blueSky = smoothstep(0.0, 0.10, blur.b - max(blur.r, blur.g * 0.97))
        * smoothstep(0.22, 0.45, bLuma);
    // flat/overcast sky: bright + desaturated, but ONLY high in the frame.
    // Bright desaturated skin highlights (forehead, nose) are NOT up there, so
    // this no longer paints faces blue. Strong positional requirement.
    float highInFrame = smoothstep(0.40, 0.70, skyT);
    float satHi = 0.26 + 0.34 * skyPrior;
    float flatSky = smoothstep(0.60, 0.78, bLuma)
        * (1.0 - smoothstep(satHi - 0.14, satHi, bSat))
        * highInFrame;
    float skyMask = clamp(blueSky * (0.30 + 0.70 * skyPrior) + flatSky, 0.0, 1.0);
    // saturate the mask inside the sky so partially-treated chalky fringes
    // cannot appear around clouds; the soft edge lives only at the horizon
    skyMask = smoothstep(0.12, 0.45, skyMask);

    // Skin/warm guard: any pixel that is warm-toned (red >= green >= blue, the
    // skin signature) is excluded from the sky mask outright. This is what
    // removes the blue blotches on faces.
    float warmSkin = smoothstep(0.0, 0.06, src.r - src.g) * smoothstep(-0.02, 0.06, src.g - src.b);
    float gate = max(
        smoothstep(0.40, 0.62, srcLuma) * (1.0 - warmSkin),
        smoothstep(0.02, 0.10, src.b - max(src.r, src.g * 0.97)));
    skyMask *= gate * (1.0 - warmSkin * 0.85);
    skyMask = clamp(skyMask * (1.0 + uSkySuppress * 0.8), 0.0, 1.0);
    skyMask = clamp(skyMask + hashNoise(grainUv * 0.31 + vec2(uGrainSeed)) * 0.008, 0.0, 1.0);
    // -----------------------------------------------------------------------

    vec3 c = presetColor(src, srcC, skyMask, skyT, bLuma);
    c = sharedFujiStage(c);

    // Monochrome IR film character (Rollei/HIE/SFX presets only): density-
    // dependent grain in the shared grain stage below; halation is applied
    // once, inside presetColor, calibrated per emulsion. (A duplicate
    // grain+halation post-block used to live here: stacking both was the
    // source of the harsh sky speckle and blown canopy blobs.)
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

    // unsharp mask on the source luma (4-tap cross). uAcutanceBias is the
    // per-stock structure dial (FilmLookLibrary) added on top of the user's
    // sharpness control - Fine-Grain IR reads crisper, Soft Vintage softer,
    // even at the user default of zero.
    float effSharp = uSharpness + uAcutanceBias;
    if (abs(effSharp) > 0.001) {
        float n = lumaOf(texture2D(uTexture, vTexCoord + vec2(uTexelSize.x, 0.0)).rgb)
            + lumaOf(texture2D(uTexture, vTexCoord - vec2(uTexelSize.x, 0.0)).rgb)
            + lumaOf(texture2D(uTexture, vTexCoord + vec2(0.0, uTexelSize.y)).rgb)
            + lumaOf(texture2D(uTexture, vTexCoord - vec2(0.0, uTexelSize.y)).rgb);
        c += (srcLuma - n * 0.25) * effSharp * 0.5;
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
    // Film is never grainless: every stock carries a small always-on
    // baseline (uGrainBase, per-look from FilmLookLibrary) so the per-stock
    // grain personality is visible at default settings; the user's Grain
    // slider adds on top of it. grainBias/grainClump are the per-stock
    // amplitude and clump-scale dials - HIE and Soft Vintage read coarser,
    // Fine-Grain reads tighter, independent of the user's Grain slider.
    //
    // Exposure-dependent density (Poisson-like: strongest in midtones,
    // tapering toward both deep shadow and bright highlight - the same
    // curve real grain visibility follows in a print) is universal across
    // every preset family (2026-07-23e).
    float effGrain = uGrain + uGrainBase;
    float grainDitherScale = 1.0;
    if (effGrain > 0.001) {
        float gLuma = lumaOf(c);
        float d = (gLuma - 0.42) / 0.30;
        float densityWeight = exp(-d * d);

        // Deep-shadow density floor (2026-07-24). The bare Gaussian falls to
        // 0.14-0.22 below luma 0.05, which on the fine stocks drops the grain
        // excursion under half an 8-bit LSB - it quantises away completely and
        // the region renders as a dead flat plateau. That is the measured
        // cause of the "flat pool blacks" reported from device captures (see
        // docs/PLAN_2026-07-23d, step 5). Film granularity does fall at low
        // density, but print grain VISIBILITY does not fall this fast, and
        // "less grain" must not become "no grain". The floor lifts ONLY the
        // deep end: for luma >= 0.34 this expression is bit-identical to the
        // bare Gaussian, so highlight protection is exactly preserved.
        //
        // uStdTone3.z is a per-look shadow-floor SCALE (2026-07-24, second
        // pass), 1.0 by default for every family/stock. CineStill 800T's
        // real base stock (KODAK VISION3 500T) uses "Dye Layering
        // Technology" specifically engineered to REDUCE shadow grain for
        // better shadow signal-to-noise - the opposite direction from a
        // uniform floor - so its look entry overrides this down to 0.35.
        float shadowLift = 1.0 - smoothstep(0.02, 0.34, gLuma);
        densityWeight = max(densityWeight, 0.62 * uStdTone3.z * shadowLift);

        float grainAmp = effGrain * 0.040 * densityWeight * uGrainBias;
        vec2 gUv = grainUv / max(uHaloGrain.w, 0.05);

        // Grain-aware dither (2026-07-24). The sub-LSB IGN dither further
        // down runs AFTER grain at a fixed +/-0.77 LSB. Measured against the
        // per-stock grain amplitudes, that dither was equal to or LOUDER than
        // the grain across most of the tonal range on the finer stocks - so
        // the texture a user actually saw was substantially the dither's
        // fixed screen-space pattern rather than the grain's clump structure.
        // Film grain is itself a dither, so where grain is strong the IGN
        // pass is redundant: back it off in proportion to grain amplitude.
        // Deliberately conservative (55% max displacement, referenced to
        // 3.2 LSB) because banding lives in smooth BRIGHT gradients where the
        // density curve makes grain weakest - fine stocks and highlights keep
        // essentially all of their dither. Verified on ramp tests to leave
        // banding protection intact; the separate sky dither assist below is
        // not touched at all.
        grainDitherScale = 1.0 - clamp(grainAmp * 175.3, 0.0, 1.0) * 0.55;

        // Shared structural (luma) noise: every channel's base component,
        // giving the grain its spatial "clump" shape.
        float nLuma = filmGrain(gUv, uGrainSeed);
        vec3 grainDelta = vec3(nLuma);

        // Per-channel ("chroma") grain (2026-07-23f): real color-negative
        // dye clouds carry independent per-channel variation on top of the
        // shared silver/structural noise, not one scalar broadcast to
        // R=G=B - a scalar-only signal is the single biggest gap against
        // reference grain tools on color stock. Decomposed on an
        // opponent-color axis (R vs. B, G holding the balancing term) so
        // the noise reads as chroma speckle rather than RGB misregistration,
        // and stays zero-mean so it introduces no systematic color cast.
        // Gated by chromaAmt, not a preset-family switch: mono-IR is always
        // fully achromatic (uPreset<=5 forces 0 - chroma noise on a B&W
        // stock would be a real regression). Classic Film blends this out
        // through its own monoMix uniform, so Tri-X (monoMix=1) falls back
        // to scalar-only automatically, with no special case needed.
        // Aerochrome has no monoMix of its own and is always false-color,
        // so it always gets full chroma grain. Verified in
        // docs/assets/grain-baseline-2026-07-23/step3-4/.
        float chromaAmt = (uPreset <= 5) ? 0.0 : (1.0 - uStdTone3.x);
        if (chromaAmt > 0.001) {
            // Chroma grain is COARSER than the luma grain (2026-07-24, was
            // gUv * 1.7 = finer). Two reasons, both pointing the same way:
            // colour-negative dye clouds are physically larger than the
            // silver grains that form them, and human chroma acuity is far
            // below luma acuity. Fine, high-frequency per-pixel colour noise
            // is precisely what reads as chromatic aberration / colour
            // fringing rather than film speckle - measured spectral centroid
            // went from 1.54x the luma grain (finer) to 0.52x (coarser).
            vec2 cUv = gUv / 1.8;
            float nCr = filmGrain(cUv + vec2(31.7, 11.3), uGrainSeed + 7.0);
            float nCb = filmGrain(cUv + vec2(-19.1, 47.7), uGrainSeed + 13.0);
            // Luma-neutral opponent axis (2026-07-24). The previous green
            // coefficient -0.5*(nCr+nCb) cancelled the red term (0.299 -
            // 0.587*0.5 = +0.006, negligible) but left the BLUE term leaking
            // 0.114 - 0.587*0.5 = -0.180 into luma: the "chroma" term was
            // injecting extra luma noise equal to 18% of the chroma
            // amplitude, on an axis biased toward blue. Solving
            // dot(vec3(nCr, g, nCb), REC601) = 0 for g gives these exact
            // coefficients, which hold for any nCr/nCb independently.
            float nCg = -0.5094 * nCr - 0.1942 * nCb;
            grainDelta += chromaAmt * 0.35 * vec3(nCr, nCg, nCb);
        }

        // Clump irregularity (2026-07-23f): a coarser, independent
        // value-noise field multiplies grain AMPLITUDE (not the noise
        // value itself), so visible grain strength clusters into irregular
        // patches instead of reading as a uniform texture everywhere -
        // closer to the Boolean/Poisson-disk crystal-cluster model real
        // grain follows. Derived from the same per-look gUv, so a coarser
        // stock's clumps are proportionally coarser too. Range [0.5, 1.5],
        // mean ~1.0 (verified numerically) - shifts WHERE grain is visible,
        // not the calibrated overall amount.
        vec2 mUv = gUv / 4.0;
        float clumpMask = 0.5 + valueNoise(mUv + vec2(uGrainSeed * 5.11, uGrainSeed * 8.87));

        c += grainDelta * grainAmp * 2.2 * clumpMask;
    }

    // channel swap
    if (uSwapMode == 1) {
        c = c.bgr;
    } else if (uSwapMode == 2) {
        c = c.grb;
    } else if (uSwapMode == 3) {
        c = c.rbg;
    }

    // sub-LSB dither: prevents 8-bit banding in smooth gradients; the sky
    // gets a stronger decorrelated octave where ramps amplify source steps
    // Interleaved-gradient noise: dramatically better distributed than white
    // noise at the same amplitude - smooth sky gradients stay SILKY while
    // banding is still broken. Sky assist halved accordingly.
    float ign = fract(52.9829189 * fract(0.06711056 * gl_FragCoord.x + 0.00583715 * gl_FragCoord.y)) - 0.5;
    // Base dither is scaled by grainDitherScale (1.0 where there is little or
    // no grain, down to 0.45 where grain is strong enough to break banding by
    // itself) so the fixed IGN pattern stops competing with the film grain's
    // own clump structure. See the grain block above for the rationale.
    c += ign * 0.006 * grainDitherScale;
    // Sky assist is deliberately NOT scaled: smooth bright sky ramps are both
    // the most banding-prone region and the region where the grain density
    // curve is weakest, so this pass must keep its full strength.
    c += ign * skyMask * 0.003;

    // Look intensity: blend the finished film look against the levelled
    // source. 1.0 = full effect; lower values are the pro dial-it-back
    // control every film-emulation workflow expects.
    c = mix(src, c, clamp(uIntensity, 0.0, 1.0));

    // Clipping zebras (preview only; the capture path passes uZebra = 0):
    // diagonal stripes over anything within a breath of clipping, so blown
    // exposure is visible BEFORE the shot, not in the gallery afterwards.
    if (uZebra > 0.5 && lumaOf(c) > 0.965) {
        if (mod(gl_FragCoord.x + gl_FragCoord.y, 14.0) < 7.0) {
            c = mix(c, vec3(1.0, 0.15, 0.15), 0.75);
        }
    }

    // Temporary classifier debug view - see uDebugClassifier declaration above.
    if (uDebugClassifier > 0.5) {
        c = gClassifierDebug;
    }

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

// Index layout matches the shader's presetColor() dispatch: 0-5 monochrome
// IR, 6-11 Aerochrome, 12-18 Standard Film.
internal fun SpectralPreset.toShaderIndex(): Int = when (this) {
    SpectralPreset.B_W_INFRARED -> 0
    SpectralPreset.HIGH_CONTRAST_IR -> 1
    SpectralPreset.WHITE_FOLIAGE_DARK_SKY -> 2
    SpectralPreset.MONO_IR_MODERATE -> 3
    SpectralPreset.MONO_IR_FINE_GRAIN -> 4
    SpectralPreset.MONO_IR_SOFT_VINTAGE -> 5
    SpectralPreset.AEROCHROME_FALSE_COLOR -> 6
    SpectralPreset.AEROCHROME_SOFT -> 7
    SpectralPreset.AEROCHROME_DENSE -> 8
    SpectralPreset.AEROCHROME_GOLD -> 9
    SpectralPreset.AEROCHROME_FADED -> 10
    SpectralPreset.AEROCHROME_VIVID -> 11
    SpectralPreset.EKTAR_100 -> 12
    SpectralPreset.CINESTILL_800T -> 13
    SpectralPreset.TRI_X_400 -> 14
    SpectralPreset.PORTRA_400 -> 15
    SpectralPreset.ARCHIVE_CHROME -> 16
    SpectralPreset.CINEMATIC_NEUTRAL -> 17
    SpectralPreset.WARM_NEGATIVE -> 18
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
        maxTextureSize = maxSize[0].coerceAtLeast(4096)

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
            zebraOverlay = settings.zebraEnabled,
            classifierDebugView = settings.classifierDebugView,
        )
    }

    /**
     * Runs the full filter chain over a still image. Must be called on the GL thread
     * (see [SpectralGlView.process]). Returns a new upright Bitmap.
     */
    fun processBitmap(input: Bitmap, captureSettings: CameraSettings): Bitmap {
        // Auto-levels (capture only): sample the still's luma histogram and set
        // gentle black/white points, Lightroom-Auto style. Caps keep it safe on
        // already-good exposures: shadow lift <= 0.10, stretch <= ~1.25x.
        val probe = Bitmap.createScaledBitmap(input, 48, 48, true)
        val probePx = IntArray(48 * 48)
        probe.getPixels(probePx, 0, 48, 0, 0, 48, 48)
        if (probe != input) probe.recycle()
        val lumas = FloatArray(probePx.size)
        for (i in probePx.indices) {
            val px = probePx[i]
            lumas[i] = (((px ushr 16) and 0xFF) * 0.299f +
                ((px ushr 8) and 0xFF) * 0.587f +
                (px and 0xFF) * 0.114f) / 255f
        }
        lumas.sort()
        val autoLo = lumas[lumas.size / 100].coerceAtMost(0.10f)
        var autoHi = lumas[(lumas.size * 99) / 100].coerceAtLeast(0.90f)
        // Overexposure rescue: a plain p1/p99 stretch can only brighten. If the
        // median says the capture is badly blown (stale manual settings, harsh
        // sun), raise the mapped white point ABOVE 1 so the frame is gently
        // compressed back into range before the film curve. Capped at 1.8 so a
        // deliberately high-key shot is respected.
        val median = lumas[lumas.size / 2]
        if (median > 0.72f) {
            autoHi = (median / 0.60f).coerceIn(autoHi, 1.8f)
        }

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
                autoLo = autoLo,
                autoHi = autoHi,
                // Unlike zebra (preview-only aid), the debug classifier view
                // needs to land in the actual saved file so it can be sent
                // for inspection - explicitly threaded through here rather
                // than left at the false default.
                classifierDebugView = captureSettings.classifierDebugView,
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
            // No front-camera mirror: the preview shows reality, matching the
            // saved file (explicit user requirement).
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

        Matrix.scaleM(posMatrix, 0, sx, sy, 1f)
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
        autoLo: Float = 0f,
        autoHi: Float = 1f,
        zebraOverlay: Boolean = false,
        classifierDebugView: Boolean = false,
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
        GLES20.glUniform1f(program.uAutoLo, autoLo)
        GLES20.glUniform1f(program.uAutoHi, autoHi)
        GLES20.glUniform1f(program.uIntensity, currentSettings.intensity)
        GLES20.glUniform1f(program.uZebra, if (zebraOverlay) 1f else 0f)
        GLES20.glUniform1f(
            program.uDebugClassifier,
            if (classifierDebugEnabled(classifierDebugView, BuildConfig.DEBUG)) 1f else 0f,
        )
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

        // Structured film-look parameters (FilmLookLibrary, core/FilmLook.kt).
        // Only the active preset's family is actually read by the shader, so
        // it is harmless to always populate both tables from a default -
        // uStdTone3 (monoMix/panRed) was missing from this cross-family
        // reset until 2026-07-23f: it is only ever WRITTEN by the
        // STANDARD_FILM branch, so a stale monoMix left over from a
        // previous Tri-X frame could silently leak into the next
        // Aerochrome/mono-IR draw on the same shared GL program. This
        // mattered only cosmetically before (nothing downstream read it
        // outside STANDARD_FILM); it matters functionally now that the
        // grain stage's chroma gate reads uStdTone3.x for every family.
        // Reset every family block on every draw. Preview and capture share the
        // program, so no inactive family's values may leak across presets.
        GLES20.glUniform4f(program.uMonoCurve, 4.8f, 5.5f, 2.30f, 0.36f)
        GLES20.glUniform4f(program.uMonoCurve2, 0.948f, 0.52f, 0.88f, 0.055f)
        GLES20.glUniform4f(program.uAeroTone, 0.55f, 1.18f, 1.0f, 1.0f)
        GLES20.glUniform4f(program.uAeroTone2, 0f, 0f, 0f, 0f)
        GLES20.glUniform4f(program.uStdTone, 0f, 0f, 1.0f, 0f)
        GLES20.glUniform4f(program.uStdTone2, 0f, 1.0f, 1.0f, 1.0f)
        GLES20.glUniform4f(program.uStdTone3, 0f, 0f, 1.0f, 0f)
        GLES20.glUniform3f(program.uHaloTint, 1.0f, 0.55f, 0.35f)
        GLES20.glUniform4f(program.uHaloGrain, 0.90f, 0.10f, 0.05f, 1.0f)
        GLES20.glUniform1f(program.uGrainBias, 1.0f)
        GLES20.glUniform1f(program.uGrainBase, 0f)
        GLES20.glUniform1f(program.uAcutanceBias, 0f)
        // Identity defaults preserve all existing presets. Only the three new
        // visible-spectrum stocks opt into the shared refinement stage.
        GLES20.glUniform4f(program.uSharedTone, 0f, 0f, 0f, 0f)
        GLES20.glUniform4f(program.uSharedProtection, 0f, 0f, 0f, 0f)
        GLES20.glUniform4f(program.uSharedDensity, 0f, 0f, 0f, 0f)
        GLES20.glUniform4f(program.uSharedHueA, 1f, 1f, 1f, 1f)
        GLES20.glUniform2f(program.uSharedHueB, 1f, 1f)

        when (currentSettings.preset.family) {
            LookFamily.MONOCHROME_IR -> {
                val look = FilmLookLibrary.monoLookFor(currentSettings.preset)
                GLES20.glUniform4f(program.uMonoCurve, look.toeLo, look.toeSpan, look.toePow, look.toeK)
                GLES20.glUniform4f(program.uMonoCurve2, look.ceiling, look.woodLift, look.skyStrength, look.waterFloor)
                GLES20.glUniform4f(program.uHaloGrain, look.haloThreshold, look.haloTight, look.haloWide, look.grainClump)
                GLES20.glUniform1f(program.uGrainBias, look.grainBias)
                GLES20.glUniform1f(program.uGrainBase, look.grainBase)
                GLES20.glUniform1f(program.uAcutanceBias, look.acutanceBias)
                GLES20.glUniform4f(program.uSharedTone, look.sharedProfile.tone.toe, look.sharedProfile.tone.shoulder,
                    look.sharedProfile.tone.highlightChromaCompression, 0f)
                GLES20.glUniform4f(program.uSharedProtection, look.sharedProfile.protection.skin,
                    look.sharedProfile.protection.foliage, look.sharedProfile.protection.sky,
                    look.sharedProfile.protection.neutral)
                GLES20.glUniform4f(program.uSharedDensity, look.sharedProfile.density.density,
                    look.sharedProfile.density.chromaCompression, look.sharedProfile.density.blueDensity, 1f)
                GLES20.glUniform4f(program.uSharedHueA, look.sharedProfile.density.redWeight,
                    look.sharedProfile.density.yellowWeight, look.sharedProfile.density.greenWeight,
                    look.sharedProfile.density.cyanWeight)
                GLES20.glUniform2f(program.uSharedHueB, look.sharedProfile.density.blueWeight,
                    look.sharedProfile.density.magentaWeight)
                GLES20.glUniform4f(program.uAeroTone, 0.55f, 1.18f, 1.0f, 1.0f)
                GLES20.glUniform4f(program.uAeroTone2, 0f, 0f, 0f, 0f)
                // .z = shadow-floor scale (2026-07-24, second pass); 1.0 =
                // unchanged universal floor. No sourced evidence any mono-IR
                // stock needs a different value from the universal fix.
                GLES20.glUniform4f(program.uStdTone3, 0f, 0f, 1.0f, 0f)
            }
            LookFamily.STANDARD_FILM -> {
                val look = FilmLookLibrary.standardLookFor(currentSettings.preset)
                GLES20.glUniform4f(program.uStdTone, look.warmth, look.tealShadows, look.saturation, look.contrast)
                GLES20.glUniform4f(program.uStdTone2, look.toeLift, look.ceiling, look.redBias, look.blueBias)
                GLES20.glUniform4f(program.uStdTone3, look.monoMix, look.panRed, look.shadowFloorScale, 0f)
                GLES20.glUniform3f(program.uHaloTint, look.haloR, look.haloG, look.haloB)
                GLES20.glUniform4f(program.uHaloGrain, look.haloThreshold, look.haloTight, look.haloWide, look.grainClump)
                GLES20.glUniform1f(program.uGrainBias, look.grainBias)
                GLES20.glUniform1f(program.uGrainBase, look.grainBase)
                GLES20.glUniform1f(program.uAcutanceBias, look.acutanceBias)
                val profile = look.sharedProfile
                GLES20.glUniform4f(
                    program.uSharedTone,
                    profile.tone.toe,
                    profile.tone.shoulder,
                    profile.tone.highlightChromaCompression,
                    0f,
                )
                GLES20.glUniform4f(
                    program.uSharedProtection,
                    profile.protection.skin,
                    profile.protection.foliage,
                    profile.protection.sky,
                    profile.protection.neutral,
                )
                GLES20.glUniform4f(
                    program.uSharedDensity,
                    profile.density.density,
                    profile.density.chromaCompression,
                    profile.density.blueDensity,
                    if (profile != SharedFilmProfile.IDENTITY) 1f else 0f,
                )
                GLES20.glUniform4f(
                    program.uSharedHueA,
                    profile.density.redWeight,
                    profile.density.yellowWeight,
                    profile.density.greenWeight,
                    profile.density.cyanWeight,
                )
                GLES20.glUniform2f(
                    program.uSharedHueB,
                    profile.density.blueWeight,
                    profile.density.magentaWeight,
                )
            }
            LookFamily.AEROCHROME -> {
                val look = FilmLookLibrary.aeroLookFor(currentSettings.preset)
                GLES20.glUniform4f(program.uAeroTone, look.curveMix, look.satCap, look.magentaBoost, look.skyDepthBoost)
                GLES20.glUniform4f(program.uAeroTone2, look.gold, look.fade, 0f, 0f)
                GLES20.glUniform4f(program.uHaloGrain, look.haloThreshold, look.haloTight, look.haloWide, look.grainClump)
                GLES20.glUniform1f(program.uGrainBias, look.grainBias)
                GLES20.glUniform1f(program.uGrainBase, look.grainBase)
                GLES20.glUniform1f(program.uAcutanceBias, look.acutanceBias)
                GLES20.glUniform4f(program.uSharedTone, look.sharedProfile.tone.toe, look.sharedProfile.tone.shoulder,
                    look.sharedProfile.tone.highlightChromaCompression, 0f)
                GLES20.glUniform4f(program.uSharedProtection, look.sharedProfile.protection.skin,
                    look.sharedProfile.protection.foliage, look.sharedProfile.protection.sky,
                    look.sharedProfile.protection.neutral)
                GLES20.glUniform4f(program.uSharedDensity, look.sharedProfile.density.density,
                    look.sharedProfile.density.chromaCompression, look.sharedProfile.density.blueDensity, 1f)
                GLES20.glUniform4f(program.uSharedHueA, look.sharedProfile.density.redWeight,
                    look.sharedProfile.density.yellowWeight, look.sharedProfile.density.greenWeight,
                    look.sharedProfile.density.cyanWeight)
                GLES20.glUniform2f(program.uSharedHueB, look.sharedProfile.density.blueWeight,
                    look.sharedProfile.density.magentaWeight)
                GLES20.glUniform4f(program.uMonoCurve, 4.8f, 5.5f, 2.30f, 0.36f)
                GLES20.glUniform4f(program.uMonoCurve2, 0.948f, 0.52f, 0.88f, 0.055f)
                // .z = shadow-floor scale (2026-07-24, second pass); 1.0 =
                // unchanged universal floor. No sourced evidence any
                // Aerochrome look needs a different value.
                GLES20.glUniform4f(program.uStdTone3, 0f, 0f, 1.0f, 0f)
            }
        }

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
        val uAutoLo = GLES20.glGetUniformLocation(id, "uAutoLo")
        val uAutoHi = GLES20.glGetUniformLocation(id, "uAutoHi")
        val uIntensity = GLES20.glGetUniformLocation(id, "uIntensity")
        val uZebra = GLES20.glGetUniformLocation(id, "uZebra")
        val uDebugClassifier = GLES20.glGetUniformLocation(id, "uDebugClassifier")
        val uSkyUp = GLES20.glGetUniformLocation(id, "uSkyUp")
        val uSharpness = GLES20.glGetUniformLocation(id, "uSharpness")
        val uRedWeight = GLES20.glGetUniformLocation(id, "uRedWeight")
        val uFoliageLift = GLES20.glGetUniformLocation(id, "uFoliageLift")
        val uSkySuppress = GLES20.glGetUniformLocation(id, "uSkySuppress")
        val uHueCos = GLES20.glGetUniformLocation(id, "uHueCos")
        val uHueSin = GLES20.glGetUniformLocation(id, "uHueSin")
        val uSaturation = GLES20.glGetUniformLocation(id, "uSaturation")
        val uSwapMode = GLES20.glGetUniformLocation(id, "uSwapMode")
        val uMonoCurve = GLES20.glGetUniformLocation(id, "uMonoCurve")
        val uMonoCurve2 = GLES20.glGetUniformLocation(id, "uMonoCurve2")
        val uAeroTone = GLES20.glGetUniformLocation(id, "uAeroTone")
        val uAeroTone2 = GLES20.glGetUniformLocation(id, "uAeroTone2")
        val uHaloGrain = GLES20.glGetUniformLocation(id, "uHaloGrain")
        val uStdTone = GLES20.glGetUniformLocation(id, "uStdTone")
        val uStdTone2 = GLES20.glGetUniformLocation(id, "uStdTone2")
        val uStdTone3 = GLES20.glGetUniformLocation(id, "uStdTone3")
        val uHaloTint = GLES20.glGetUniformLocation(id, "uHaloTint")
        val uGrainBias = GLES20.glGetUniformLocation(id, "uGrainBias")
        val uGrainBase = GLES20.glGetUniformLocation(id, "uGrainBase")
        val uAcutanceBias = GLES20.glGetUniformLocation(id, "uAcutanceBias")
        val uSharedTone = GLES20.glGetUniformLocation(id, "uSharedTone")
        val uSharedProtection = GLES20.glGetUniformLocation(id, "uSharedProtection")
        val uSharedDensity = GLES20.glGetUniformLocation(id, "uSharedDensity")
        val uSharedHueA = GLES20.glGetUniformLocation(id, "uSharedHueA")
        val uSharedHueB = GLES20.glGetUniformLocation(id, "uSharedHueB")

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

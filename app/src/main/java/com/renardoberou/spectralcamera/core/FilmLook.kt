package com.renardoberou.spectralcamera.core

/**
 * Structured film-look parameterization.
 *
 * This is the "look definition" layer described in the master plan: every
 * Aerochrome and monochrome-IR family member is expressed as data, not as a
 * bespoke shader branch. The rendering engine (SpectralGlPipeline's
 * `monoLook` / `aeroLook` GLSL functions) reads these fields generically, so
 * adding a new stock is a table entry, not new shader logic.
 *
 * Field groups map directly onto the emulsion behavior they model:
 *  - toe/span/toePow/toeK/ceiling: the H&D characteristic (tone) curve.
 *  - woodLift / skyStrength: synthetic-NIR magnitude (Wood effect, sky
 *    suppression) - how hard the film "sees" infrared.
 *  - haloThreshold/Tight/Wide: halation - anti-halation backing strength.
 *  - grainClump / grainBias / grainBase: grain clump scale, density
 *    multiplier, and the small always-on baseline amount (film is never
 *    grainless; the user's Grain dial adds on top of grainBase).
 *  - waterFloor: how far off pure black IR-dark water is allowed to sit
 *    (anti-halation stocks show more shadow life than none-backed ones).
 *  - acutanceBias: baked-in structure/microcontrast bias, additive to the
 *    user's sharpness control.
 */
data class MonoIRLook(
    val toeLo: Float,
    val toeSpan: Float,
    val toePow: Float,
    val toeK: Float,
    val ceiling: Float,
    val woodLift: Float,
    val skyStrength: Float,
    val haloThreshold: Float,
    val haloTight: Float,
    val haloWide: Float,
    val grainClump: Float,
    val grainBias: Float,
    val grainBase: Float,
    val waterFloor: Float,
    val acutanceBias: Float,
)

/**
 * Aerochrome / EIR false-colour family parameters. These modulate the shared
 * `aerochrome()` GLSL colorimetry (which stays physically-grounded and
 * common to every member) rather than replacing it per-look.
 *
 *  - gold: orange-filter warmth (0 = standard yellow filter, 1 = full
 *    orange-filter EIR variant - warmer foliage, teal sky).
 *  - curveMix: strength of the slide-film S-curve (contrast character).
 *  - satCap: reversal-film saturation headroom ceiling.
 *  - magentaBoost: multiplier on the foliage magenta-shift manifold.
 *  - skyDepthBoost: sky density multiplier (>1 = denser/deeper, <1 = paler).
 *  - fade: vintage lifted-black / desaturation amount applied post-grade.
 */
/** Shared visible-spectrum rendering controls. Zero/one defaults are identity. */
data class ToneProfile(
    val toe: Float = 0f,
    val shoulder: Float = 0f,
    val highlightChromaCompression: Float = 0f,
)

data class ProtectionProfile(
    val skin: Float = 0f,
    val foliage: Float = 0f,
    val sky: Float = 0f,
    val neutral: Float = 0f,
)

data class DensityProfile(
    val density: Float = 0f,
    val chromaCompression: Float = 0f,
    val blueDensity: Float = 0f,
)

data class SharedFilmProfile(
    val tone: ToneProfile = ToneProfile(),
    val protection: ProtectionProfile = ProtectionProfile(),
    val density: DensityProfile = DensityProfile(),
) {
    companion object {
        val IDENTITY = SharedFilmProfile()
    }
}

/**
 * One standard (non-IR) film stock. Canonical characteristics per stock are
 * documented in docs/PLAN_2026-07-23c_classic-film-family.md.
 */
data class StandardFilmLook(
    val warmth: Float,        // +warm / -tungsten-cool white-balance character
    val tealShadows: Float,   // shadow split-tone toward teal (CineStill daylight)
    val saturation: Float,
    val contrast: Float,      // s-curve mix
    val toeLift: Float,       // lifted blacks (cine negative)
    val ceiling: Float,       // highlight shoulder cap
    val redBias: Float,       // per-channel saturation bias (Ektar pop)
    val blueBias: Float,
    val monoMix: Float,       // 1 = panchromatic B&W (Tri-X)
    val panRed: Float,        // red weight of the panchromatic mix
    val haloR: Float, val haloG: Float, val haloB: Float,  // halation dye colour
    val haloThreshold: Float, val haloTight: Float, val haloWide: Float,
    val grainClump: Float, val grainBias: Float, val grainBase: Float,
    val acutanceBias: Float,
    // Shadow-floor scale for the deep-shadow density-floor fix (2026-07-24,
    // second pass). Default 1.0 = unchanged from the universal floor. Real
    // KODAK VISION3 500T (CineStill 800T's base stock) uses "Dye Layering
    // Technology" specifically engineered to REDUCE shadow-region grain for
    // better shadow signal-to-noise - the opposite direction from a uniform
    // floor - so CineStill overrides this down. No source gives a precise
    // magnitude; 0.35 is a reasoned, clearly-reduced-but-not-zero judgment
    // call, not a derived number. Ektar and Tri-X have no such documented
    // engineering and keep the default.
    val shadowFloorScale: Float = 1.0f,
    val sharedProfile: SharedFilmProfile = SharedFilmProfile.IDENTITY,
)

data class AerochromeLook(
    val gold: Float,
    val curveMix: Float,
    val satCap: Float,
    val magentaBoost: Float,
    val skyDepthBoost: Float,
    val fade: Float,
    val haloThreshold: Float,
    val haloTight: Float,
    val haloWide: Float,
    val grainClump: Float,
    val grainBias: Float,
    val grainBase: Float,
    val acutanceBias: Float,
)

/**
 * The look table. One entry per family member; this is the only place a new
 * stock's numbers live.
 */
object FilmLookLibrary {

    private val monoLooks: Map<SpectralPreset, MonoIRLook> = mapOf(
        // Rollei Infrared 400: fine-grained, sharp, controlled halation,
        // elegant negative-film contrast - the reference restrained IR look.
        // grainBase=0.14 (2026-07-24, third pass - was 0.19, originally 0.10).
        // The 0.19 value correctly matched real RMS granularity ratios
        // against Tri-X (17) and HIE (18) - but that fix anchored Rollei's
        // ABSOLUTE amplitude to Tri-X/HIE's existing absolute values, which
        // were themselves only ever tuned by eye and never independently
        // re-verified. Nearly doubling Rollei's peak grain (2.24->4.26 LSB)
        // read as noisy on a real device photo of a detailed subject (camo-
        // pattern bag) - the first real stress test that specific value had
        // seen. 0.14 (peak ~3.14 LSB, ratio to Tri-X ~0.47/HIE ~0.49) is a
        // deliberate partial correction: still meaningfully closer to the
        // real ratio (0.65/0.61) than the original 0.10 was (ratio 0.33),
        // without fully committing to a target whose absolute scale
        // depends on Tri-X/HIE's own unverified baseline. Tri-X, HIE, and
        // Soft Vintage's absolute grain levels are flagged as open -
        // they've never been independently stress-tested on a real busy
        // subject the way this correction just was for Rollei.
        SpectralPreset.B_W_INFRARED to MonoIRLook(
            toeLo = 4.8f, toeSpan = 5.5f, toePow = 2.30f, toeK = 0.36f, ceiling = 0.948f,
            woodLift = 0.52f, skyStrength = 0.88f,
            haloThreshold = 0.86f, haloTight = 0.28f, haloWide = 0.14f,
            grainClump = 1.0f, grainBias = 1.0f, grainBase = 0.14f,
            waterFloor = 0.055f, acutanceBias = 0.15f,
        ),
        // Kodak HIE: no anti-halation backing, deep toe, hardest drama,
        // strongest bloom - the famous ethereal glow.
        SpectralPreset.HIGH_CONTRAST_IR to MonoIRLook(
            toeLo = 4.6f, toeSpan = 5.2f, toePow = 2.75f, toeK = 0.22f, ceiling = 0.968f,
            woodLift = 0.68f, skyStrength = 0.95f,
            haloThreshold = 0.78f, haloTight = 0.70f, haloWide = 0.45f,
            grainClump = 1.25f, grainBias = 1.2f, grainBase = 0.24f,
            waterFloor = 0.05f, acutanceBias = 0.05f,
        ),
        // Ilford SFX 200: gentler extended-red response, grey acetate base
        // gives good halation protection - minimal glow, smoother tonality.
        SpectralPreset.WHITE_FOLIAGE_DARK_SKY to MonoIRLook(
            toeLo = 5.0f, toeSpan = 5.8f, toePow = 1.95f, toeK = 0.42f, ceiling = 0.945f,
            woodLift = 0.36f, skyStrength = 0.74f,
            haloThreshold = 0.87f, haloTight = 0.22f, haloWide = 0.10f,
            grainClump = 0.85f, grainBias = 0.90f, grainBase = 0.08f,
            waterFloor = 0.06f, acutanceBias = 0.10f,
        ),
        // Moderate IR / Konica-style: a deliberate middle ground between
        // restrained (Rollei) and dramatic (HIE) - broadly usable default.
        SpectralPreset.MONO_IR_MODERATE to MonoIRLook(
            toeLo = 4.9f, toeSpan = 5.6f, toePow = 2.15f, toeK = 0.34f, ceiling = 0.950f,
            woodLift = 0.46f, skyStrength = 0.82f,
            haloThreshold = 0.84f, haloTight = 0.30f, haloWide = 0.16f,
            grainClump = 0.95f, grainBias = 0.95f, grainBase = 0.08f,
            waterFloor = 0.06f, acutanceBias = 0.08f,
        ),
        // Fine-Grain Infrared: neutral, print-friendly, minimal drama. The
        // mildest Wood effect and sky suppression in the family, finest
        // grain, tightest halation - a "clean" IR stock, not a mood stock.
        SpectralPreset.MONO_IR_FINE_GRAIN to MonoIRLook(
            toeLo = 5.05f, toeSpan = 5.65f, toePow = 1.88f, toeK = 0.46f, ceiling = 0.932f,
            woodLift = 0.30f, skyStrength = 0.62f,
            haloThreshold = 0.90f, haloTight = 0.14f, haloWide = 0.06f,
            grainClump = 0.55f, grainBias = 0.65f, grainBase = 0.03f,
            waterFloor = 0.07f, acutanceBias = 0.20f,
        ),
        // Soft Vintage IR: print-oriented, romantic. Wide low-contrast span,
        // soft toe and shoulder, low ceiling (milky highlights), dreamy wide
        // halation, coarser grain, lifted blacks throughout.
        SpectralPreset.MONO_IR_SOFT_VINTAGE to MonoIRLook(
            toeLo = 4.85f, toeSpan = 6.3f, toePow = 1.78f, toeK = 0.54f, ceiling = 0.882f,
            woodLift = 0.44f, skyStrength = 0.60f,
            haloThreshold = 0.80f, haloTight = 0.45f, haloWide = 0.30f,
            grainClump = 1.45f, grainBias = 1.25f, grainBase = 0.20f,
            waterFloor = 0.09f, acutanceBias = -0.05f,
        ),
    )

    private val standardLooks: Map<SpectralPreset, StandardFilmLook> = mapOf(
        // Kodak Ektar 100: the world's finest-grain colour negative. Vivid
        // (esp. reds/blues) yet faithful; punchy clean contrast; whisper grain.
        // grainBase=0.05 (2026-07-24, was 0.02): Kodak's own Print Grain
        // Index data (135 format) shows Ektar crossing the PGI=25 visibility
        // threshold at 8x10 print (PGI 38) and clearly above it at 16x20
        // (PGI 66) - real Ektar is subtly but genuinely visible at normal
        // print sizes, not literally grainless. The old 0.02 never exceeded
        // 0.27 LSB at ANY tone - always at or under the anti-banding dither,
        // i.e. functionally zero. PGI and this shader's LSB units are not on
        // a convertible scale (Kodak's own disclaimer), so 0.05 is a
        // reasoned judgment call, not a derived number: it clears the dither
        // floor by a real margin for the first time while staying under
        // half of (corrected) Rollei - the next-quietest stock - preserving
        // Ektar as clearly, unambiguously the subtlest of the six real
        // stocks, matching its ~25-30% PGI gap under same-class Kodak
        // colour negative film even at identical print size/format.
        SpectralPreset.EKTAR_100 to StandardFilmLook(
            warmth = 0.045f, tealShadows = 0f, saturation = 1.30f, contrast = 0.60f,
            toeLift = 0.004f, ceiling = 0.985f, redBias = 1.15f, blueBias = 1.10f,
            monoMix = 0f, panRed = 0.30f,
            haloR = 1.0f, haloG = 0.55f, haloB = 0.35f,
            haloThreshold = 0.965f, haloTight = 0.06f, haloWide = 0.02f,
            grainClump = 0.45f, grainBias = 0.6f, grainBase = 0.05f,
            acutanceBias = 0.18f,
        ),
        // CineStill 800T: Vision3 500T with the remjet anti-halation layer
        // removed - hence the signature RED halos around lights - tungsten-
        // balanced (daylight goes cool/teal), lifted cinematic blacks.
        SpectralPreset.CINESTILL_800T to StandardFilmLook(
            warmth = -0.10f, tealShadows = 0.55f, saturation = 1.06f, contrast = 0.36f,
            toeLift = 0.035f, ceiling = 0.975f, redBias = 1.0f, blueBias = 1.06f,
            monoMix = 0f, panRed = 0.30f,
            haloR = 1.0f, haloG = 0.20f, haloB = 0.14f,
            haloThreshold = 0.80f, haloTight = 0.55f, haloWide = 0.42f,
            grainClump = 1.05f, grainBias = 1.0f, grainBase = 0.14f,
            acutanceBias = 0f,
            shadowFloorScale = 0.35f,
        ),
        // Kodak Tri-X 400: the photojournalism classic - punchy panchromatic
        // curve, rich textured blacks, forgiving shoulder, honest gritty grain.
        SpectralPreset.TRI_X_400 to StandardFilmLook(
            warmth = 0f, tealShadows = 0f, saturation = 1.0f, contrast = 0.68f,
            toeLift = 0.012f, ceiling = 0.958f, redBias = 1.0f, blueBias = 1.0f,
            monoMix = 1f, panRed = 0.30f,
            haloR = 0.85f, haloG = 0.85f, haloB = 0.85f,
            haloThreshold = 0.90f, haloTight = 0.10f, haloWide = 0.05f,
            grainClump = 1.35f, grainBias = 1.15f, grainBase = 0.26f,
            acutanceBias = 0.12f,
        ),
        // Kodak Portra 400 (2026-07-24): the professional portrait standard.
        // Researched against Kodak's current (Jan 2025, E-4050) technical
        // data sheet plus consistent, independent photographer testimony.
        //
        // Colour: universally described as restrained/natural/"honest" -
        // explicitly the opposite of Ektar's vivid punch, and specifically
        // known for NOT over-reddening skin the way Ektar can. saturation
        // (1.05) and redBias (1.04) sit well below Ektar's (1.30/1.15);
        // blueBias (1.00, vs Ektar's 1.10) reflects Portra's more muted,
        // less "deep blue" rendering. warmth (0.055) is slightly above
        // Ektar's (0.045) - warm but gentle "creamy" skin tones are its
        // signature - but the lower saturation keeps that warmth from
        // reading as vivid the way Ektar's warmth does.
        //
        // Tonality: "wide exposure latitude" and "gentle shadows" are the
        // most consistent, repeated descriptors found - toeLift (0.020) is
        // well above Ektar's (0.004) for gentler shadow rendering, and
        // contrast (0.46) sits below Ektar's (0.60) and Tri-X's (0.68) for
        // the flatter, more forgiving response latitude implies. ceiling
        // (0.975, vs Ektar's 0.985) gives a touch more highlight
        // compression, matching the "soft highlight rolloff" reputation.
        //
        // Sharpness: Kodak's own spec sheet uses nearly identical language
        // to Ektar's ("optimized sharpness... distinct edges, fine detail")
        // - both are T-GRAIN emulsions from the same technology lineage, so
        // acutanceBias (0.14) tracks close to Ektar's (0.18) rather than
        // diverging the way saturation/contrast do.
        //
        // Grain: NOT a design choice - real, sourced, and larger than
        // Ektar's. Kodak's current E-4050 data sheet gives Portra 400
        // (135, 8x10 print, 8.8x mag) a Print Grain Index of 59, against
        // Ektar 100's 38 at identical conditions (same scale, same format,
        // same print size - directly comparable) - a real ratio of ~1.55x,
        // independently confirmed by photographer testimony describing
        // Portra as visibly grainier than Ektar, especially in shadow.
        // grainBase=0.07, bias=0.65 gives a peak amplitude ratio of 1.52x
        // Ektar's (verified in numpy before shipping) - within a couple of
        // percent of the real PGI ratio, while keeping the absolute level
        // modest (peak ~1.0 LSB) given the Rollei lesson from earlier this
        // session: a correct ratio does not excuse skipping a sanity check
        // on the resulting absolute magnitude. No halation signature is
        // expected or added - unlike CineStill, Portra's anti-halation
        // backing is intact, so haloThreshold is left even more restrained
        // than Ektar's already-minimal value.
        SpectralPreset.PORTRA_400 to StandardFilmLook(
            warmth = 0.055f, tealShadows = 0f, saturation = 1.05f, contrast = 0.46f,
            toeLift = 0.020f, ceiling = 0.975f, redBias = 1.04f, blueBias = 1.00f,
            monoMix = 0f, panRed = 0.30f,
            haloR = 1.0f, haloG = 0.55f, haloB = 0.35f,
            haloThreshold = 0.97f, haloTight = 0.05f, haloWide = 0.015f,
            grainClump = 0.55f, grainBias = 0.65f, grainBase = 0.07f,
            acutanceBias = 0.14f,
        ),
        SpectralPreset.ARCHIVE_CHROME to StandardFilmLook(
            warmth = 0.02f, tealShadows = 0f, saturation = 1.04f, contrast = 0.40f,
            toeLift = 0.012f, ceiling = 0.978f, redBias = 1.01f, blueBias = 1.01f,
            monoMix = 0f, panRed = 0.30f,
            haloR = 1.0f, haloG = 0.55f, haloB = 0.35f,
            haloThreshold = 0.96f, haloTight = 0.04f, haloWide = 0.012f,
            grainClump = 0.65f, grainBias = 0.72f, grainBase = 0.06f,
            acutanceBias = 0.10f,
            sharedProfile = SharedFilmProfile(
                tone = ToneProfile(toe = 0.10f, shoulder = 0.18f, highlightChromaCompression = 0.18f),
                protection = ProtectionProfile(skin = 0.72f, foliage = 0.35f, sky = 0.55f, neutral = 0.80f),
                density = DensityProfile(density = 0.22f, chromaCompression = 0.16f, blueDensity = 0.14f),
            ),
        ),
        SpectralPreset.CINEMATIC_NEUTRAL to StandardFilmLook(
            warmth = -0.02f, tealShadows = 0.12f, saturation = 0.98f, contrast = 0.32f,
            toeLift = 0.028f, ceiling = 0.970f, redBias = 1.00f, blueBias = 1.02f,
            monoMix = 0f, panRed = 0.30f,
            haloR = 1.0f, haloG = 0.55f, haloB = 0.35f,
            haloThreshold = 0.90f, haloTight = 0.18f, haloWide = 0.08f,
            grainClump = 0.90f, grainBias = 0.90f, grainBase = 0.10f,
            acutanceBias = 0.02f,
            sharedProfile = SharedFilmProfile(
                tone = ToneProfile(toe = 0.16f, shoulder = 0.24f, highlightChromaCompression = 0.24f),
                protection = ProtectionProfile(skin = 0.80f, foliage = 0.45f, sky = 0.62f, neutral = 0.88f),
                density = DensityProfile(density = 0.12f, chromaCompression = 0.24f, blueDensity = 0.10f),
            ),
        ),
        SpectralPreset.WARM_NEGATIVE to StandardFilmLook(
            warmth = 0.075f, tealShadows = 0f, saturation = 1.02f, contrast = 0.38f,
            toeLift = 0.025f, ceiling = 0.968f, redBias = 1.05f, blueBias = 0.97f,
            monoMix = 0f, panRed = 0.30f,
            haloR = 1.0f, haloG = 0.55f, haloB = 0.35f,
            haloThreshold = 0.93f, haloTight = 0.10f, haloWide = 0.035f,
            grainClump = 0.80f, grainBias = 0.82f, grainBase = 0.09f,
            acutanceBias = 0.04f,
            sharedProfile = SharedFilmProfile(
                tone = ToneProfile(toe = 0.13f, shoulder = 0.20f, highlightChromaCompression = 0.22f),
                protection = ProtectionProfile(skin = 0.90f, foliage = 0.30f, sky = 0.48f, neutral = 0.72f),
                density = DensityProfile(density = 0.18f, chromaCompression = 0.20f, blueDensity = 0.06f),
            ),
        ),
    )

    private val aeroLooks: Map<SpectralPreset, AerochromeLook> = mapOf(
        // Aerochrome Classic: the reference EIR grade this app was built on.
        SpectralPreset.AEROCHROME_FALSE_COLOR to AerochromeLook(
            gold = 0f, curveMix = 0.55f, satCap = 1.18f, magentaBoost = 1.0f, skyDepthBoost = 1.0f, fade = 0f,
            haloThreshold = 0.86f, haloTight = 0.30f, haloWide = 0.12f,
            grainClump = 1.0f, grainBias = 1.0f, grainBase = 0.08f, acutanceBias = 0.0f,
        ),
        // Aerochrome Soft: gentler contrast, pastel magenta, paler sky,
        // minimal glow - the "everyday" member of the family.
        SpectralPreset.AEROCHROME_SOFT to AerochromeLook(
            gold = 0f, curveMix = 0.35f, satCap = 1.08f, magentaBoost = 0.75f, skyDepthBoost = 0.85f, fade = 0.10f,
            haloThreshold = 0.88f, haloTight = 0.18f, haloWide = 0.08f,
            grainClump = 0.9f, grainBias = 0.85f, grainBase = 0.06f, acutanceBias = -0.03f,
        ),
        // Aerochrome Dense: punchier contrast, deeper cyan sky, more
        // saturation headroom, dramatic halation - the "hero shot" grade.
        SpectralPreset.AEROCHROME_DENSE to AerochromeLook(
            gold = 0f, curveMix = 0.72f, satCap = 1.28f, magentaBoost = 1.25f, skyDepthBoost = 1.25f, fade = 0f,
            haloThreshold = 0.82f, haloTight = 0.38f, haloWide = 0.20f,
            grainClump = 1.1f, grainBias = 1.1f, grainBase = 0.10f, acutanceBias = 0.06f,
        ),
        // Aerochrome Gold: orange-filter EIR - warmer foliage, teal sky.
        SpectralPreset.AEROCHROME_GOLD to AerochromeLook(
            gold = 1f, curveMix = 0.55f, satCap = 1.18f, magentaBoost = 1.0f, skyDepthBoost = 1.0f, fade = 0f,
            haloThreshold = 0.86f, haloTight = 0.30f, haloWide = 0.12f,
            grainClump = 1.0f, grainBias = 1.0f, grainBase = 0.08f, acutanceBias = 0.0f,
        ),
        // Aerochrome Faded / Vintage: desaturated, lifted blacks, warm cast,
        // hazy pale sky, subdued everything - an aged-print character.
        SpectralPreset.AEROCHROME_FADED to AerochromeLook(
            gold = 0.3f, curveMix = 0.30f, satCap = 1.0f, magentaBoost = 0.65f, skyDepthBoost = 0.70f, fade = 0.35f,
            haloThreshold = 0.90f, haloTight = 0.15f, haloWide = 0.06f,
            grainClump = 1.2f, grainBias = 1.05f, grainBase = 0.14f, acutanceBias = -0.08f,
        ),
        // Aerochrome Vivid (2026-07-24): added alongside the shared magenta-
        // ceiling fix (see aerochrome() in SpectralGlPipeline.kt) - the fix
        // benefits all six variants, but none of the original five were
        // designed to chase maximum foliage hue as their defining trait
        // (Dense is an overall density/contrast dial, not a hue target).
        // magentaBoost=1.6 is deliberately past Dense's 1.25, chosen so
        // typical mid-green foliage reaches ~0.91 on the new ceiling
        // (verified in numpy) - i.e. actually reaches the characteristic
        // hot pink/magenta on ordinary foliage, not just best-case deep
        // green. curveMix/satCap held near Classic's neutral density
        // rather than Dense's, so the distinguishing trait is foliage hue,
        // not an overall darker/punchier image.
        SpectralPreset.AEROCHROME_VIVID to AerochromeLook(
            gold = 0f, curveMix = 0.58f, satCap = 1.20f, magentaBoost = 1.6f, skyDepthBoost = 1.05f, fade = 0f,
            haloThreshold = 0.86f, haloTight = 0.28f, haloWide = 0.14f,
            grainClump = 1.0f, grainBias = 1.0f, grainBase = 0.10f, acutanceBias = 0f,
        ),
    )

    fun monoLookFor(preset: SpectralPreset): MonoIRLook =
        monoLooks[preset] ?: monoLooks.getValue(SpectralPreset.B_W_INFRARED)

    fun aeroLookFor(preset: SpectralPreset): AerochromeLook =
        aeroLooks[preset] ?: aeroLooks.getValue(SpectralPreset.AEROCHROME_FALSE_COLOR)

    fun standardLookFor(preset: SpectralPreset): StandardFilmLook =
        standardLooks[preset] ?: standardLooks.getValue(SpectralPreset.EKTAR_100)

}

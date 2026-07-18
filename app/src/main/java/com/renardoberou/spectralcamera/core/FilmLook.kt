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
 *  - grainClump / grainBias: grain clump scale and density (film-specific,
 *    not the user's global grain dial, which multiplies on top of this).
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
        SpectralPreset.B_W_INFRARED to MonoIRLook(
            toeLo = 4.8f, toeSpan = 5.5f, toePow = 2.30f, toeK = 0.36f, ceiling = 0.948f,
            woodLift = 0.52f, skyStrength = 0.88f,
            haloThreshold = 0.86f, haloTight = 0.28f, haloWide = 0.14f,
            grainClump = 1.0f, grainBias = 1.0f,
            waterFloor = 0.055f, acutanceBias = 0.15f,
        ),
        // Kodak HIE: no anti-halation backing, deep toe, hardest drama,
        // strongest bloom - the famous ethereal glow.
        SpectralPreset.HIGH_CONTRAST_IR to MonoIRLook(
            toeLo = 4.6f, toeSpan = 5.2f, toePow = 2.60f, toeK = 0.26f, ceiling = 0.965f,
            woodLift = 0.64f, skyStrength = 0.92f,
            haloThreshold = 0.78f, haloTight = 0.70f, haloWide = 0.45f,
            grainClump = 1.15f, grainBias = 1.15f,
            waterFloor = 0.05f, acutanceBias = 0.05f,
        ),
        // Ilford SFX 200: gentler extended-red response, grey acetate base
        // gives good halation protection - minimal glow, smoother tonality.
        SpectralPreset.WHITE_FOLIAGE_DARK_SKY to MonoIRLook(
            toeLo = 5.0f, toeSpan = 5.8f, toePow = 2.05f, toeK = 0.40f, ceiling = 0.945f,
            woodLift = 0.38f, skyStrength = 0.78f,
            haloThreshold = 0.87f, haloTight = 0.22f, haloWide = 0.10f,
            grainClump = 0.85f, grainBias = 0.90f,
            waterFloor = 0.06f, acutanceBias = 0.10f,
        ),
        // Moderate IR / Konica-style: a deliberate middle ground between
        // restrained (Rollei) and dramatic (HIE) - broadly usable default.
        SpectralPreset.MONO_IR_MODERATE to MonoIRLook(
            toeLo = 4.9f, toeSpan = 5.6f, toePow = 2.15f, toeK = 0.34f, ceiling = 0.950f,
            woodLift = 0.46f, skyStrength = 0.82f,
            haloThreshold = 0.84f, haloTight = 0.30f, haloWide = 0.16f,
            grainClump = 0.95f, grainBias = 0.95f,
            waterFloor = 0.06f, acutanceBias = 0.08f,
        ),
        // Fine-Grain Infrared: neutral, print-friendly, minimal drama. The
        // mildest Wood effect and sky suppression in the family, finest
        // grain, tightest halation - a "clean" IR stock, not a mood stock.
        SpectralPreset.MONO_IR_FINE_GRAIN to MonoIRLook(
            toeLo = 5.05f, toeSpan = 5.65f, toePow = 2.00f, toeK = 0.42f, ceiling = 0.940f,
            woodLift = 0.34f, skyStrength = 0.70f,
            haloThreshold = 0.90f, haloTight = 0.14f, haloWide = 0.06f,
            grainClump = 0.65f, grainBias = 0.70f,
            waterFloor = 0.07f, acutanceBias = 0.20f,
        ),
        // Soft Vintage IR: print-oriented, romantic. Wide low-contrast span,
        // soft toe and shoulder, low ceiling (milky highlights), dreamy wide
        // halation, coarser grain, lifted blacks throughout.
        SpectralPreset.MONO_IR_SOFT_VINTAGE to MonoIRLook(
            toeLo = 4.85f, toeSpan = 6.0f, toePow = 1.85f, toeK = 0.50f, ceiling = 0.900f,
            woodLift = 0.44f, skyStrength = 0.68f,
            haloThreshold = 0.80f, haloTight = 0.45f, haloWide = 0.30f,
            grainClump = 1.30f, grainBias = 1.20f,
            waterFloor = 0.09f, acutanceBias = -0.05f,
        ),
    )

    private val aeroLooks: Map<SpectralPreset, AerochromeLook> = mapOf(
        // Aerochrome Classic: the reference EIR grade this app was built on.
        SpectralPreset.AEROCHROME_FALSE_COLOR to AerochromeLook(
            gold = 0f, curveMix = 0.55f, satCap = 1.18f, magentaBoost = 1.0f, skyDepthBoost = 1.0f, fade = 0f,
            haloThreshold = 0.86f, haloTight = 0.30f, haloWide = 0.12f,
            grainClump = 1.0f, grainBias = 1.0f, acutanceBias = 0.0f,
        ),
        // Aerochrome Soft: gentler contrast, pastel magenta, paler sky,
        // minimal glow - the "everyday" member of the family.
        SpectralPreset.AEROCHROME_SOFT to AerochromeLook(
            gold = 0f, curveMix = 0.35f, satCap = 1.08f, magentaBoost = 0.75f, skyDepthBoost = 0.85f, fade = 0.10f,
            haloThreshold = 0.88f, haloTight = 0.18f, haloWide = 0.08f,
            grainClump = 0.9f, grainBias = 0.85f, acutanceBias = -0.03f,
        ),
        // Aerochrome Dense: punchier contrast, deeper cyan sky, more
        // saturation headroom, dramatic halation - the "hero shot" grade.
        SpectralPreset.AEROCHROME_DENSE to AerochromeLook(
            gold = 0f, curveMix = 0.72f, satCap = 1.28f, magentaBoost = 1.25f, skyDepthBoost = 1.25f, fade = 0f,
            haloThreshold = 0.82f, haloTight = 0.38f, haloWide = 0.20f,
            grainClump = 1.1f, grainBias = 1.1f, acutanceBias = 0.06f,
        ),
        // Aerochrome Gold: orange-filter EIR - warmer foliage, teal sky.
        SpectralPreset.AEROCHROME_GOLD to AerochromeLook(
            gold = 1f, curveMix = 0.55f, satCap = 1.18f, magentaBoost = 1.0f, skyDepthBoost = 1.0f, fade = 0f,
            haloThreshold = 0.86f, haloTight = 0.30f, haloWide = 0.12f,
            grainClump = 1.0f, grainBias = 1.0f, acutanceBias = 0.0f,
        ),
        // Aerochrome Faded / Vintage: desaturated, lifted blacks, warm cast,
        // hazy pale sky, subdued everything - an aged-print character.
        SpectralPreset.AEROCHROME_FADED to AerochromeLook(
            gold = 0.3f, curveMix = 0.30f, satCap = 1.0f, magentaBoost = 0.65f, skyDepthBoost = 0.70f, fade = 0.35f,
            haloThreshold = 0.90f, haloTight = 0.15f, haloWide = 0.06f,
            grainClump = 1.2f, grainBias = 1.05f, acutanceBias = -0.08f,
        ),
    )

    fun monoLookFor(preset: SpectralPreset): MonoIRLook =
        monoLooks[preset] ?: monoLooks.getValue(SpectralPreset.B_W_INFRARED)

    fun aeroLookFor(preset: SpectralPreset): AerochromeLook =
        aeroLooks[preset] ?: aeroLooks.getValue(SpectralPreset.AEROCHROME_FALSE_COLOR)
}

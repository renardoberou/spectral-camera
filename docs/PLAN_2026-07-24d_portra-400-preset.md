# Plan: Kodak Portra 400 preset (2026-07-24)

**Trigger:** requested as a new, fully-researched, fully-integrated Classic Film preset,
built to the same standard as every other fix this session - sourced data, numpy
verification before touching the shader, nothing else in the codebase changed.

## Research

**Source:** Kodak's current official technical data sheet (E-4050, revised January
2025), plus consistent, independent photographer testimony across multiple review
sites, forums, and comparison articles specifically contrasting Portra 400 against
Ektar 100 (already in-app, giving a direct, real anchor for every parameter).

**Grain (the concrete, sourced number):** Portra 400 (135 format, 8x10 print, 8.8x
magnification) has a Print Grain Index of 59. Ektar 100 at identical conditions: 38.
Same scale, same format, same print size - directly comparable, no PGI-to-RMS
conversion problem this time. Real ratio: 1.55x. Independently confirmed qualitatively:
multiple photographers describe Portra as visibly grainier than Ektar, "especially in
shadow areas," when shot side by side. This is real and expected - Portra 400 is a
400-speed film, Ektar is 100-speed, and faster film means more grain (Kodak's own E-58
document states this as general physics, not stock-specific).

**Colour:** overwhelmingly consistent across every source found - "restrained,"
"natural," "honest," explicitly contrasted against Ektar's "vivid," "punchy," "bold"
saturation. Most specific, most repeated claim: Portra gives natural/flattering skin,
Ektar can render skin "too red" / "more intense." Warmth is present (the signature
"creamy" skin tone) but paired with restraint, not vividness - warm and muted together,
not warm and saturated.

**Tonality:** "wide exposure latitude" and "gentle shadows" are the most repeated
descriptors, alongside "soft highlight rolloff." Directly contrasted against narrower-
latitude, punchier stocks including Ektar.

**Sharpness:** Kodak's own spec sheet for Portra 400 uses nearly identical language to
Ektar's own sheet ("optimized sharpness... distinct edges, fine detail") - both are
T-GRAIN emulsions from the same technology lineage. This is the one axis where Portra
should NOT diverge from Ektar the way saturation/contrast/grain do.

**Halation:** no source describes any distinctive halation signature for Portra -
unlike CineStill (remjet removed specifically exposes it), ordinary anti-halation-
backed color negative stock doesn't show this. Kept minimal, more restrained than even
Ektar's already-minimal values.

## Implementation

Added as a new `StandardFilmLook` entry (`SpectralPreset.PORTRA_400`), appended at the
end of the enum and given shader index 15 - deliberately NOT inserted between existing
entries, so no other preset's index shifts and no boundary check anywhere in the shader
needs to change. This was the safer structural choice available after the Aerochrome
Vivid addition required reindexing three other presets.

Parameter choices, each referenced directly against Ektar's existing shipped values:

| Parameter | Ektar 100 | Portra 400 | Reasoning |
|---|---|---|---|
| saturation | 1.30 | 1.05 | restrained vs. vivid - the most consistent finding |
| redBias | 1.15 | 1.04 | Ektar's known over-reddening on skin; Portra explicitly avoids it |
| blueBias | 1.10 | 1.00 | Ektar's "deep blues" vs. Portra's more muted rendering |
| warmth | 0.045 | 0.055 | warm "creamy" skin signature - slightly more than Ektar, but restrained by lower saturation rather than pushed vivid |
| contrast | 0.60 | 0.46 | wide latitude implies a flatter response than Ektar's already-moderate curve |
| toeLift | 0.004 | 0.020 | "gentle shadows" - well above Ektar's near-zero shadow lift |
| ceiling | 0.985 | 0.975 | "soft highlight rolloff" - more compression than Ektar |
| acutanceBias | 0.18 | 0.14 | same T-GRAIN sharpness lineage as Ektar - tracks close, doesn't diverge |
| grainBase / bias | 0.05 / 0.6 | 0.07 / 0.65 | verified in numpy: peak amplitude ratio 1.52x Ektar's, target 1.55x from real PGI data |
| halo* | minimal | even more minimal than Ektar | no distinctive halation signature expected for this stock |

Grain ratio verified in numpy before touching the shader (peak 1.02 LSB for Portra vs
0.67 for Ektar, ratio 1.517 vs the 1.55 target) - and the absolute magnitude was
deliberately kept modest given the lesson from Rollei IR 400 earlier this session: a
correct real-world ratio does not excuse skipping a sanity check on the resulting
absolute amplitude. 1.02 LSB peak is well below Rollei's newly-corrected 3.14 or Tri-X's
6.71 - appropriately grainier than Ektar without approaching those stocks' territory.

## What was deliberately NOT touched

Per the request: nothing else in the engine changed. No other preset's parameters,
no shared shader logic, no grain formula constants, no classifier code. The only
touch points beyond the new preset itself are the mechanical ones a new enum entry
requires (shader index mapping) and documentation (README, VALIDATION.md).

## Verification status

Grain ratio verified in numpy. Colour/tonality parameters are reasoned directly from
sourced research and cross-referenced against the in-app stock (Ektar) most consistently
used as the comparison point in every source found - but colour grading, unlike grain
amplitude, doesn't reduce to a single verifiable number the way PGI ratios do. **Still
requires the device**, same as every preset in this app - a real photo of skin tones and
a detailed non-skin subject, per the new S4 row in VALIDATION.md.

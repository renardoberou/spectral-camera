# Fix: olive/yellow-green foliage misclassification in spectral shader

**Date:** 2026-07-14
**Status:** Implemented in commit `fec5077` on `claude/results-review-plan-fd5l0k`.
Shader validation and numeric gate checks passed; device re-capture of the same
garden/pool scene is the remaining verification step.

## Context

The remaining item from the earlier results-review plan (item 6, "expand calibration
swatch set") was left aspirational because it had no concrete failing case. The user
just supplied three real captures from a Motorola Edge 60 Fusion (mono IR, Aerochrome,
and the true-color original of the same garden/pool scene). This is the start of step 5
(physical-device verification) and it surfaced a genuine, reproducible gap for step 6.

Pixel-sampling the true-color original against the shader's classifier math (done this
session, read-only, no repo changes) confirms:

- The mono IR capture is correct as-is: the bright foliage is genuine Wood-effect
  rendering, not clipping (0% of sampled foliage pixels reach ≥235/255, mean 178 with
  full tonal spread). No fix needed there.
- The Aerochrome capture has one reproducible defect: a patch of olive/yellow-green
  foliage (chlorophyll present, but visually yellow rather than deep green) renders as
  a flat yellow-brown blob instead of the expected warm foliage tone. Sampled
  chromaticity: `ng - nr ≈ 0.000`, which fails the vegetation classifier's `greenDom`
  gate (`smoothstep(0.0, 0.05, ng - nr)`), so the pixel falls through to the generic
  `base` warmth formula instead of the foliage treatment.
- Checked against pavement/concrete/facade samples from the *same* photo so a broadened
  gate doesn't reintroduce false positives:

  | region | ng-nr | ng-nb |
  |---|---|---|
  | olive foliage (defect) | -0.002 to 0.000 | **+0.20 to +0.22** |
  | yellow pavers | -0.002 | +0.042 |
  | concrete pathway | +0.028 | +0.060 |
  | building facade | +0.043 to +0.047 | -0.028 to +0.003 |

  `ng-nr` alone can't separate them (pavers match the foliage almost exactly), but
  `ng-nb` gives a clean ~4x margin (0.20+ for real foliage vs ≤0.06 for pavement/
  concrete/facade). A gate combining both is safe.

User decisions for this fix:
- Olive/yellow-green foliage should render as a **distinct, less-saturated warm
  red/coral** — not identical to the vivid red used for deep-green foliage — so the
  two foliage types stay visually differentiated (closer to how real EIR film shows
  varying NIR reflectance across species/health states).
- Proceed with just this confirmed gap now; broader calibration-swatch expansion
  (skin tones, other water bodies, etc.) stays future work pending more field photos.

## Implementation

File: `app/src/main/java/com/renardoberou/spectralcamera/core/gl/SpectralGlPipeline.kt`

### 1. `aerochrome()` — add an "olive foliage" branch (near the existing `veg`
   calculation around line 148-153)

After the existing `veg` mask, add:

```glsl
// Olive/yellow-green foliage: chlorophyll present but nr approaches ng (naturally
// yellow-green species, partial senescence), which fails the strict greenDom gate
// above. Pavement/concrete/facades sit at the same ng-nr but never reach this
// ng-nb margin, so that alone safely separates true foliage from built surfaces.
float oliveGreenBlue = smoothstep(0.12, 0.22, ng - nb);
float nearNeutralRG = 1.0 - smoothstep(0.03, 0.09, abs(ng - nr));
float oliveVeg = clamp(oliveGreenBlue * nearNeutralRG * notBlueC * (1.0 - veg), 0.0, 1.0);
```

Then, after `folCol`/`ir` are computed (after the `veg` mix, before the murky-water
blend), blend a muted/warmer tone into `ir` for `oliveVeg` pixels — partial saturation
toward `folCol`, not the full hot-red used for `veg`:

```glsl
vec3 oliveCol = mix(vec3(luma), folCol, 0.55);
ir = mix(ir, oliveCol, oliveVeg * 0.75);
```

Keep this before the murky-water blend (murky is already gated on `(1.0 - veg)` and
uses different chroma signs, so no interference expected — verify no overlap once
written).

### 2. Mono-preset foliage classifier — same broadened gate (near line 360-369)

The mono presets' `foliage` mask (feeding `irLuminance()`/halation) uses the identical
gate shape on `srcC`-derived chromaticity (`ngM`, `nrM`, `nbM`). Apply the same
broadening there so the Wood-effect brightness lift also applies consistently to this
foliage type in the monochrome presets — this is a brightness-only change (no hue
decision needed), and keeps the classifier logic consistent across all presets rather
than fixing it only where the visible defect happened to be worse:

```glsl
float oliveGreenBlueM = smoothstep(0.12, 0.22, ngM - nbM);
float nearNeutralRGM = 1.0 - smoothstep(0.03, 0.09, abs(ngM - nrM));
float oliveFoliageM = oliveGreenBlueM * nearNeutralRGM * (1.0 - smoothstep(0.0, 0.06, nbM - max(nrM, ngM)));
foliage = clamp(foliage + oliveFoliageM * (1.0 - foliage), 0.0, 1.0);
```

(Exact integration point/variable naming to match surrounding style — adjust during
implementation if the local variable names differ slightly from this reference.)

## Verification

1. `glslangValidator` on both assembled fragment shaders (OES + 2D), same as prior
   shader commits in this branch.
2. Re-run the numeric gate check (Python, using the same sampled RGB triples already
   gathered from the user's photos) to confirm `oliveVeg`/`oliveFoliageM` evaluate to
   ~1.0 on the olive-foliage sample and ~0.0 on the pavers/concrete/facade samples,
   before committing.
3. Note the limit of this verification: no attached device this session, so the actual
   rendered look can't be re-captured here. Recommend the user rebuild and re-shoot the
   same garden/pool view (or reprocess the saved original through the updated pipeline
   if the app supports reprocessing) as the immediate manual follow-up once merged.
4. Commit to `claude/results-review-plan-fd5l0k` per existing convention, with a commit
   message describing the defect (root-caused via real capture + pixel sampling) and
   the fix, consistent with the style of the prior `224f888` shader commit.

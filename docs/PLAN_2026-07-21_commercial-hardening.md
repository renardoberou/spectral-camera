# Plan: commercial hardening from real-device evidence

**Date:** 2026-07-21 · **Status:** executed this cycle (fixes below are shipped in this same commit)
**Trigger:** the user captured 10 real presets + 1 original photo on-device and asked for (1) experimental
presets removed - film emulation only, (2) a review of the real results, (3) a plan to the commercial goal,
(4) end-to-end execution with verifiable output in this run.

This plan supersedes nothing in `docs/PLAN_2026-07-17_film-look-families.md` - it is the next cycle, now
grounded in actual device output instead of code review alone.

---

## 1. What the real photos showed

Source: 11 photos of the same static scene (a potted plant against a cream stucco wall, window with a dusk
sky reflection), captured through 5 Monochrome IR presets, 5 Aerochrome presets, and 1 unprocessed original.

### 1.1 Aerochrome: a real, reproducible rendering bug ("neon wall")

All 5 Aerochrome shots rendered the cream stucco wall as a saturated neon/lime yellow, and the dusk window as
a flat, near-identical saturated navy block. This is **not** a scene-difficulty or tuning issue - it is a
common-mode defect in the shared `aerochrome()` colorimetry engine, independent of which of the 5 looks was
selected (confirmed: the defect magnitude was nearly identical across Classic/Soft/Dense/Gold/Faded).

**Root cause, isolated and numerically verified** (see §2): the neutral-surface protection (`greyC`, which is
supposed to render pale sunlit neutrals as film-cream rather than a hard colour) only widened its tolerance
for *cool*-cast neutrals (`coolCast`, keyed on `nb - nr`). A warm-cast neutral - exactly what a cream wall
under low warm sun produces - got **zero** widening, fell through to the generic warmth-driven base colour,
and then the reversal-film saturation headroom boost (`satCap`, up to 1.28) inflated that residual warmth
into a fully saturated yellow.

### 1.2 Aerochrome: family differentiation was invisible on this scene, for two different reasons

- **`skyDepthBoost` (0.70-1.25 across the 5 looks) had almost no visible effect** on the dusk window, because
  it scaled an already near-black colour with a plain multiply - multiplying 0.02-0.10 by 0.75-1.3 stays
  imperceptible after 8-bit quantisation. Numerically confirmed on the real photo (§2.2).
- **The dusk window in this photo isn't even classified as "sky" by the `skyMask` heuristic** (confirmed:
  mask ≈ 0 there) - it's too dark to trigger the `blueSky`/`flatSky` detectors, so its flat navy appearance
  actually comes from the `plainBlue`/`shadowCol` path, which currently has **no per-look dial at all**. This
  is a real architecture gap, not something a numeric tweak can fix blind - flagged as next-cycle work below.
- **Grain is a global user dial, default 0.** Every per-look `grainBias`/`grainClump` value in
  `FilmLookLibrary` is inert unless the user manually raises Grain above zero, which most users on a default
  install will never do. Since grain and halation-on-fine-detail are two of the strongest stock-personality
  cues in the master plan's own list, "stock personality is invisible by default" is a real product problem,
  not just a test-scene artefact.

### 1.3 Monochrome IR: no rendering bug found, but same differentiation ceiling

The 5 captured Monochrome IR shots (Rollei, HIE, SFX, and two others - the picker has 6 members;
only 5 were captured, one wasn't tested this round) showed a coherent, credible restrained-IR look: glowing
foliage, dark window, no artefacts. Differences between shots were present but subtle (contrast/highlight
ceiling), consistent with the fact that this scene has no sky, no water, and grain was off - three of the
family's differentiation axes were structurally unavailable on this test scene.

### 1.4 What was NOT a bug

The window rendering as a deep, saturated near-black navy is directionally correct EIR behaviour (real EIR
renders a dusk/dark sky deep blue) - the finding above is specifically that the *5 looks don't differ there*,
not that the colour itself is wrong.

---

## 2. Fixes executed this cycle (verified against the real photo, not just code review)

No Android SDK is available in this execution environment (confirmed: no `ANDROID_HOME`, no cached platform,
`services.gradle.org` returns 403 through the proxy). Rather than edit the shader and only claim it works,
every fix below was:

1. Reproduced numerically from the real uploaded photo (a Python/numpy port of the exact GLSL classification
   and colour math, at `core/gl/SpectralGlPipeline.kt`-equivalent precision, run against the actual pixel
   data - not synthetic swatches).
2. Isolated to the exact failing pixel/formula.
3. Fixed in the Python port and re-verified on the same real photo.
4. Only then ported into the real GLSL/Kotlin source, line-for-line identical to the verified Python fix.

### 2.1 Fix: warm-cast neutral protection ("neon wall")

`aerochrome()` in `core/gl/SpectralGlPipeline.kt`:

- Replaced the cool-only `coolCast` gate with a symmetric `greenNeutral` signal: a colour-temperature cast
  on an intrinsically neutral surface shifts red vs. blue while leaving the **green** share close to neutral;
  a genuinely saturated coloured object (paint, foliage) shifts green too. This is a more direct, physically
  grounded, and symmetric test than the old cool-only magnitude-limited gate.
- Widened the neutral-tolerance ceiling for cast-recognised pixels from 0.095 to 0.20 chromaticity distance.
- Excluded `vegAll` / `vividBlue` / `murky` explicitly from the neutral pull, so real (even weakly-saturated)
  foliage and water are never desaturated by the wider tolerance.
- Gated the reversal-saturation headroom boost (`satCap`) by the same neutral signal, so a pixel just pulled
  toward cream doesn't get re-inflated back toward saturation immediately afterward.

**Verified result** (isolated wall pixel, real photo, sampled RGB `(0.929, 0.810, 0.584)`):

| | R | G | B |
|---|---|---|---|
| Before | 0.942 | 0.862 | 0.434 |
| After | 0.919 | 0.866 | 0.652 |

R-B spread (the "how neon is it" measure) dropped from 0.51 to 0.27 - roughly halved. The wall now reads as a
believable warm pale gold/cream, consistent with authentic low-sun EIR rendering, rather than a cartoonish
neon yellow. Full-frame renders (before/after, all 5 Aerochrome looks, on the real photo) are attached to
this session as `aerochrome_before_after_grid.png`.

**Honest limitation:** this is a real, verified improvement, not a claim of full neutrality. A warm-lit pale
wall SHOULD read somewhat warm/gold in true EIR film - pushing all the way to grey would itself be wrong.
Further softening the underlying `warmth`-driven base formula (which is not chromaticity-proportional even
for a perfectly neutral input) is next-cycle work, flagged in §3.

### 2.2 Fix: sky-depth dynamic range on dark clear sky

`aerochrome()`'s sky ramp: replaced `deepCol *= (2.0 - skyDepthBoost)` (a plain multiply, nearly invisible
once `deepCol` is already near-black) with a power curve: `deepCol = pow(deepCol, gamma)` where
`gamma = clamp(1.0 + (skyDepthBoost - 1.0) * 1.6, 0.35, 3.0)`. At `skyDepthBoost = 1.0` (Classic, Gold) this
is an exact identity - those two already-tuned reference looks are provably unchanged. Verified on a
representative deep-sky triple: Soft's blue channel now separates from Classic by +0.10 (was +0.045) and
Dense by -0.11 (was -0.045) - roughly 2.4x more dynamic range in exactly the region where it was previously
imperceptible.

**Honest limitation:** this only helps pixels the `skyMask` heuristic actually classifies as sky. It does
**not** address the dark-window uniformity found in §1.2, because that pixel never entered the sky path in
the first place - see the shadow-depth gap in §3.

### 2.3 Removed the Experimental family

Per explicit instruction: Spectral Camera is now film-emulation only. Removed `RED_720_STYLE`,
`BLUE_CYAN_SPECTRAL`, `FAKE_THERMAL_PALETTE`, `NIGHT_SURVEILLANCE_IR` from `SpectralPreset`, the
`LookFamily.EXPERIMENTAL` enum value, the now-dead `thermal()`/`tone()` GLSL functions and their four
`presetColor()` branches, the corresponding `toShaderIndex()` entries, and the Experimental row from the
README and preset-picker UI. The app now has exactly 11 presets across exactly 2 families.

### 2.4 Fixed a real crash risk surfaced by the removal

`CameraSettingsRepository.settings` called `SpectralPreset.valueOf(...)` on the persisted preset name with no
fallback. A user who had one of the four now-removed presets selected would hit `IllegalArgumentException`
and crash on every app launch until they cleared app data. Wrapped in `runCatching { }.getOrNull()` with a
fallback to the default preset, matching the pattern already used in `MediaRepository.kt`.

---

## 3. Next cycle (grounded in this cycle's evidence, in priority order)

1. **Give `plainBlue`/`shadowCol` a per-look dial.** This is the actual reason all 5 Aerochrome looks
   produced a near-identical dark window in the test photo - that path currently has zero family
   parameterization. Extend it with the existing `skyDepthBoost` (reframed as a general "density" dial) or a
   new `AerochromeLook` field, and verify on the same real photo before shipping.
2. **Decide the default-grain product question.** Either (a) give each look a small always-on baseline grain
   independent of the user's Grain slider, so stock personality is visible without user action, or (b)
   explicitly message in-app that grain/halation differences require raising the Grain control, and design
   the preset picker to preview that. Do not silently leave this ambiguous - it directly undermines the
   family-differentiation goal on typical default-settings usage.
3. **Physical-device visual QA pass** against `docs/VALIDATION.md`'s full scene list (blue sky with clouds,
   water/pools/glass, skin, red painted objects, etc.) - this cycle only had one real scene to validate
   against; the other 7 Aerochrome and 8 Monochrome scenes are still unverified on real device output.
4. **Capture the missing 6th Monochrome IR preset** (only 5 of 6 were tested this round) and confirm it's
   visually distinct from its neighbours.
5. **Re-examine the `warmth`-driven Aerochrome base formula itself** (not just its neutral-detection gate) -
   it is not chromaticity-proportional even for a perfectly neutral input, which is the deeper reason §2.1's
   fix is a real improvement but not a complete one.

---

## 4. Definition of done for this cycle

- [x] Experimental presets fully removed (code, UI, README, no dangling references - grepped clean).
- [x] A genuine Aerochrome rendering bug found on real device output, root-caused to an exact formula, fixed,
      and the fix verified against the same real photo before touching production code.
- [x] A second real Aerochrome dynamic-range gap found, fixed, and verified the same way.
- [x] A crash risk introduced by the removal (persisted-preset `valueOf`) found and fixed.
- [x] A gap that was NOT blindly fixed (`plainBlue`/`shadowCol` has no per-look dial) is named honestly
      instead of papered over with an unverifiable guess.
- [x] Verifiable output produced this run: `aerochrome_before_after_grid.png` (original + before/after for all
      5 Aerochrome looks on the real captured photo).

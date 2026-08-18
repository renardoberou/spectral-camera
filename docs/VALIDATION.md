# Validation: named failure scenes

**Status:** living document. Every look change should be checked against this
list before it ships, not just against pretty demo frames. This is the Phase
6 validation framework from the master plan: it exists to stop tuning on
best-case shots only.

This file describes *expected behavior* and *unacceptable failure modes* per
scene, for both flagship families. It does not embed test images (the repo
has no physical-device image capture in CI); treat each entry as a manual QA
checklist item to run on a physical device before a release tag, and record
pass/fail notes inline (see the status column) as scenes are actually
re-shot and checked.

Engine references point at the current architecture: `monoLook()` /
`irLuminance()` / `irHDCurve()` and `aeroLook()` / `aerochrome()` in
`core/gl/SpectralGlPipeline.kt`, parameterized per stock by `core/FilmLook.kt`.

---

## Aerochrome / false-colour IR

| # | Scene | Expected behavior | Unacceptable failure modes | Engine hook | Status |
|---|---|---|---|---|---|
| A1 | Blue sky with clouds | Deep cyan-blue clear sky, clouds stay bright and structurally readable (not flattened to a single hue plateau); no banding in the gradient. | Washed-lavender sky; clouds losing texture; visible 8-bit contour bands. | `skyMask` + `skyCol` ramp keyed on smoothed luma; IGN dither | **2026-07-21c on-device (dawn cityscape with clear sky + warm horizon glow, no clouds present in this shot): PASS on all 5 Aerochrome looks.** Sky renders as deep cyan gradient, warm horizon glow preserved correctly, no lavender wash, no visible banding. Family differentiation clearly visible: measured sky_UR luma Dense 0.106 → Classic 0.159 → Gold 0.206 → Soft 0.220 → Faded 0.352 (3.3× range), Classic/Gold near-identical as designed. **Clouds themselves still need re-shoot; horizon-glow interaction with sky ramp added as watch item below.** |
| A2 | Pale hazy sky | Sky renders paler/less saturated than clear noon sky but still reads as sky, not blown white or a hard edge against the horizon. | Hard sky/horizon seam; sky clipping to pure white; sky going muddy grey. | `clearBlue` / `lift` gating in `aerochrome()` sky ramp | **2026-07-21c on-device (same dawn scene, the warm-glow horizon zone is the pale-sky test): PASS.** Horizon glow rendered as a soft pale warm-white transition into the deep sky above; no hard seam observed. Faded/Soft render this zone particularly gracefully. True hazy-overcast scene still needed. |
| A3 | Skylit concrete / neutral walls | Pale neutral (cool-lit) or pale warm gold/cream (warm-lit), not saturated blue/purple/yellow. | Shaded white facades rendering as solid blue/purple ("sticker" look); warm-lit cream walls rendering as neon/lime yellow ("neon wall" bug). | `greyC` neutral gate in `aerochrome()` (cool-cast branch: P3 fix, v1.13.1; warm-cast branch: `greenNeutral` fix, docs/PLAN_2026-07-21) | **2026-07-21: warm-cast neon-wall bug root-caused and fixed. 2026-07-21c on-device re-shoot (multi-building cityscape with warm dawn light): PASS on all 5 Aerochrome looks.** Measured wall_R output on the warm-lit facade: RGB (~0.40, ~0.35, ~0.32) — a pale warm cream, exactly the intended film response (compare to the pre-fix (~0.94, ~0.86, ~0.44) neon yellow from PLAN_2026-07-21 §2.1). Cool-shaded left-facade also reads correctly as pale/neutral. |
| A4 | Deep-shadow foliage | Foliage in shadow still classifies as vegetation (reads magenta/red, textured), not muddy brown. | Shadowed canopy falling through the veg classifier to a flat brown/olive patch. | Chromaticity-based `veg`/`oliveVeg` classification (exposure-invariant) | Reviewed; needs re-shoot |
| A5 | Skin | Waxy pale sallow-yellow, not red. | Skin misclassified as foliage (goes red/magenta). | Red-dominant gate suppresses `veg` on skin tones | Needs re-shoot |
| A6 | Red painted objects | Renders green/yellow-green (real EIR reversal), not left red. | Object stays native red (reads as an unprocessed "sticker"). | `manMade` dye-pull grade | Needs re-shoot |
| A7 | Water / pools / glass | Vivid indigo/blue on calm reflective water; no foliage-red bleeding into the water edge. | Purple pools (red leaking into blue water); water picking up vegetation hue at the shoreline. | `vividBlue`/`waterC` classification + water-sanctity gate on `vegAll` | Fixed (P1.4 water sanctity); needs re-shoot |
| A8 | Mixed urban greenery | Species/vigor variation visible (not one flat crimson slab); olive/yellow-green trees render distinctly from deep-green canopy. | All foliage collapsing to one uniform red regardless of species. | `species` continuum + `oliveVeg` branch | Reviewed; needs re-shoot. **2026-07-24: the deeper problem here wasn't species collapsing to one hue, it was the whole continuum landing short of pink - the `magenta` formula's constants capped the blend weight at 0.60 even on Dense's best-case pixel (deep healthy green), measured against realistic chromaticity values. Typical mid-green foliage landed around 0.4-0.5 on every existing preset - majority `folRed` (red/crimson), never reaching the characteristic pink/magenta regardless of scene or dial. Fixed (ceiling raised 0.30+0.30*species -> 0.45+0.45*species, re-verified in numpy: Classic now reaches 0.90 best-case/0.57 typical, was 0.60/0.38). Benefits all six Aerochrome grades' foliage hue. Needs re-shoot to confirm on-device.** |
| A9 | Foliage hue on Aerochrome Vivid (new preset, 2026-07-24) | Reaches the characteristic hot pink/magenta on ordinary mid-green foliage, not just best-case deep green - at Classic's neutral overall density, not Dense's darker/punchier read. | Still landing in red/crimson territory on typical foliage; or inheriting Dense's crushed shadows instead of being a hue-focused grade. | `magentaBoost=1.6` (verified in numpy: ~0.91 on typical mid-green, ~1.0 on deep healthy green) + `curveMix`/`satCap` held near Classic's | New; needs first device shoot |

**Family-coherence check:** Classic/Soft/Dense should read as the same
underlying scene at three contrast/saturation levels (verify `curveMix`,
`satCap`, `skyDepthBoost` deltas are visible but the classification never
flips foliage/sky/water assignment between grades). Gold should differ from
Classic only in warmth/sky-teal, not in classification. Faded should look
like an aged print of Classic, not a different scene.

**2026-07-21 real-device finding (docs/PLAN_2026-07-21_commercial-hardening.md):** on a dusk-window scene,
all 5 looks produced a nearly identical dark navy sky/glass despite `skyDepthBoost` ranging 0.70-1.25.
Root cause #1 (fixed): the boost was a plain multiply on already near-black colour, with almost no visible
effect - replaced with a power curve that has real effect in that range while leaving `skyDepthBoost = 1.0`
(Classic/Gold) unchanged. Root cause #2 (**fixed same day**, see
docs/PLAN_2026-07-21b_density-dial-and-baseline-grain.md): that specific window was not even classified as
"sky" by `skyMask` (mask ≈ 0) - its dark, flat appearance came from the `plainBlue`/`shadowCol` and
`vividBlue` paths, which had no per-look dial; `skyDepthBoost` now doubles as a density scale on those
paths (window-crop family luma spread more than doubled on the real photo, Classic/Gold provably
unchanged). A1/A2 above still need a fresh device re-shoot to confirm on scenes where the sky classifier
*does* fire.

**New watch item (2026-07-21b):** on Gold/Faded, dark foliage *reflected in window glass* renders green -
the veg classifier fires on the reflection and gold's `veg`-keyed green push amplifies it. Pre-existing
behaviour (visible in both before/after proof rows), not introduced this cycle. Candidate fix: gate the
gold green push by the surface-smoothness signal already used for `cyanC`.

**Grain note (2026-07-21b):** stocks now carry a small always-on baseline grain (`grainBase`), so grain
personality checks in the M-table apply at DEFAULT settings, not only with the Grain slider raised.

**2026-07-21c on-device confirmations (cycles a+b re-verified on a dawn cityscape):**
- **Neon-wall fix (cycle a, §2.1)**: PASS. Warm-lit facade renders pale cream on all 5 looks; measured RGB
  ~(0.40, 0.35, 0.32) — never neon yellow.
- **Sky-depth power-curve (cycle a, §2.2) + density dial on dark blue paths (cycle b)**: PASS.
  Measured sky_UR luma spans 0.106 (Dense) → 0.352 (Faded) — a 3.3× family spread on the same real
  pixels. Pool luma follows the same ordering (Dense 0.051 → Faded 0.127). Classic and Gold produce
  the intended reference-and-warm-variant pairing.
- **Baseline grain (cycle b)**: PASS on visual inspection; the previously identified "grain slider = 0
  makes stock personality invisible" state no longer holds. Rigorous grain-clump measurement would need
  a flat-field capture, out of scope for this scene.

**NEW HIGH-PRIORITY watch item (2026-07-21c) — mono "sky blob" artifact:** all 6 Monochrome IR presets
render a large, hard-edged asymmetric DARK VIGNETTE across the upper sky region on this scene, visually
reading as a "blob". Not a blur artifact — a classifier decision-boundary artifact. Measured sky
upper-left / upper-right luma ratio on the *original* is 1.23× (mild left-to-right gradient because
sun is at horizon-left); on the *processed* mono output it is 22.8× (Rollei), 17.9× (HIE), 7.6× (M_D),
3.2× (M_C), 1.85× (m_E and m_F). The asymmetry scales tightly with per-stock `skyStrength`. Root
cause: `skyChroma = smoothstep(0.03, 0.11, nb - max(nr, ng*0.97))` in `irLuminance()` fires hard on
saturated-blue upper-right sky (skyDown ≈ 1 → tone × 0.04), but fails on the warm-lit horizon-glow
zone (nb is not > max(nr,ng) there) — so `skyDown` snaps from ~0 to ~1 across the classifier's
decision boundary, imprinting that boundary as a hard tonal edge. Aerochrome doesn't show this: it has
a hue-locked pale-sky ramp for the pale zone, so the transition is smooth. Candidate fixes for next
cycle: (a) soften the `skyChroma` upper edge (0.11 → ~0.18) so classification is a smoother
proportional gradient rather than near-binary; (b) apply a wide spatial blur to `skyDown` before it
multiplies tone; (c) cap the maximum suppression so full-classified sky retains ~10-15% luma, similar
to Aerochrome's paleCol path. This is the highest-priority mono cycle item after this validation
round.

**FIXED 2026-07-23 (docs/PLAN_2026-07-23_mono-family-hardening.md):** the sky blob was confirmed on
a second real-device scene (all 6 mono presets, cloudy midday courtyard) together with two more mono
failures: dense shaded canopy crushing to posterized black masses (M3/A4) and the pool rendering as
a dead void (M2). All three fixed via candidate methods (a)+(c) plus a shadow-canopy classifier
branch and a classification-keyed water floor; verified numerically per-stock on the real photo.
Device re-shoot of both reference scenes (dawn + courtyard) is the remaining confirmation step.

---

## Monochrome IR

| # | Scene | Expected behavior | Unacceptable failure modes | Engine hook | Status |
|---|---|---|---|---|---|
| M1 | Noon sky with cloud detail | Sky suppressed toward Zone I-II; cloud structure stays visible, not crushed to a flat void. | Sky banding/seam; clouds disappearing into a flat dark plateau. | `skyDown` / `skyStr` in `irLuminance()`; IGN dither | **2026-07-23 on-device (overcast): sky-blob smudges confirmed on this scene too; fixed same day (proportional `skyChroma` ramp, decisive-blueness `skyHazy`, 0.86 suppression cap). Needs re-shoot to confirm; cloud-structure retention still to be judged on a blue-sky-with-clouds scene.** **2026-07-24 on-device (Rollei IR, balcony portrait, genuinely neutral grey/white overcast sky): FAILED - sky measured 0.903 luma in the output, essentially zero suppression. Root cause: both `skyChroma` and `skyHazy` require some blue bias; a truly neutral (non-blue-tinted) overcast sky satisfies neither. Fixed same day - added a third `skyOvercast` detector gated on brightness + LOW saturation + sky-ward position (see PLAN_2026-07-24c); needs re-shoot to confirm on the same or a similar scene.** |
| M2 | Reflective water | Dark but alive: Zone I-II with visible specular ripple, never a dead void. | Pure Zone-0 black water with no sheen ("void-black"). | Water floor+sheen keyed on source darkness OR classified suppression (2026-07-23 fix) | **2026-07-23 on-device: FAILED (bright blue pool rendered as void on all 6 presets - the v1.14 floor keyed on source darkness only). Fixed same day (suppression-keyed floor); needs re-shoot to confirm.** |
| M3 | Wooded shadow | Shadowed canopy keeps intra-canopy structure (tone-modulated Wood lift), not a fused white sheet. | Shadow canopy flattening to solid white or staying flat dark with no glow. | `toneMod` Wood lift + `shadowVegM` blue-cast-tolerant shadow-canopy branch (2026-07-23) | **2026-07-23 on-device: FAILED (blue-cast shaded canopy fell out of the veg classifier and crushed to posterized black on all 6 presets). Fixed same day; needs re-shoot to confirm.** |
| M4 | Leaf detail against sky | Individual leaf/branch silhouettes stay separable against a suppressed sky; no matte-line halo at the foliage/sky boundary. | Hard cutout edges; sky mask bleeding onto leaf silhouettes. | `skyMask` per-pixel gate + generous soft edge | Reviewed; needs re-shoot |
| M5 | Bark / masonry / stone | Even midtone texture retained; no classifier flicker ("leopard-spot" patchwork). | Blotchy black/white patches on a uniform material from per-pixel chroma noise. | Chroma-bilateral `srcC` denoise feeding classifiers | Fixed; needs re-shoot. **2026-07-24 on-device (Rollei IR, interior wall/window): a genuinely blocky, high-variance patch found in a dark window reflection (measured 68x higher block-to-block variance than the flat wall beside it), confirmed straight-from-app output, not a re-export compression artifact. NOT root-caused this cycle - candidate territory is this same classifier-flicker family, but needs a less-compressed export or a dedicated re-shoot of a similar reflective/high-contrast scene before diagnosing further.**
| M6 | Pale skin | Slight brightening (NIR penetration), smooth, not blown out. | Skin blowing to paper white; skin picking up the sky/water suppression. | `skin` lift branch in `irLuminance()` | Reviewed; needs re-shoot |
| M7 | Red clothing / bright man-made objects | Renders on the plain film tone response, no special-cased artifacts. | Colour-classifier false positives (misread as foliage/sky). | Chromaticity gates require genuine green/blue dominance, not just brightness | Reviewed; needs re-shoot |
| M8 | Haze / overcast field | Flat, low-contrast light captured faithfully - soft but not muddy; foliage still gets a mild glow. | Overcast scenes reading identically to full sun (no differentiation); flat light going muddy grey. | `skyHazy` absolute-brightness detector alongside chroma detector | Reviewed; needs re-shoot |

## Classic Film (uPreset 11-13, 2026-07-23c)

| # | Scene | Expected behavior | Unacceptable failure modes | Engine hook | Status |
|---|---|---|---|---|---|
| S1 | Ektar 100: saturated garden + skin | Vivid reds/blues, faithful skin, near-invisible grain, crisp | Neon skin shift; visible grain; muddy contrast | `standardFilm()` sat bias + fine grain | **2026-07-24 on-device (first real shoot): color/warmth reads strong (skin measured R0.78/G0.38/B0.20, notably warmer than a same-day CineStill frame), but the two shots weren't taken in matched light - can't yet separate "the preset does this" from "the light was different." Needs a same-scene, same-light comparison.** |
| S2 | CineStill 800T: lights at night / daylight street | RED halation around lights; cool teal-shadow daylight; lifted cine blacks | Neutral halos; warm daylight; crushed blacks | `uHaloTint` red + warmth/teal dials | **2026-07-24 on-device: FAILED (night-lamp shot) - measured zero red tint (R-B -0.07 to -0.11) on the dark side of a bright light's edge, exactly where the halo should show. Root-caused: `edgeGate` only fired on the bright source's own rim, never the dark surround. Fixed same day (`abs()` on the edge-difference test, see PLAN_2026-07-24c); needs re-shoot to confirm. Daylight teal-shadow visibility not yet separable from lighting-condition differences (see S1 note).** |
| S3 | Tri-X 400: street scene | Punchy pan B&W, textured blacks, honest gritty grain | Grey mush; silky grain; blocked shadows | `monoMix` pan + coarse grain dials | **2026-07-24 on-device (balcony portrait, standing in for street scene): PASS as ordinary panchromatic B&W - blown overcast sky and a backlit-dark leaf are both expected, unforced behavior for a non-IR stock (no Wood effect, no sky suppression expected here). No defects found on this shot.** |
| S4 | Portra 400 (new preset, 2026-07-24): portrait/skin + a detailed non-skin subject | Natural, restrained skin tones - warm but NOT oversaturated, no Ektar-style over-reddening. Visibly grainier than Ektar 100 on close inspection (real, sourced - not a bug). Gentle shadows, soft highlight rolloff. | Skin reading as saturated/vivid as Ektar (would mean saturation/redBias dials are off); grain invisible or matching Ektar's (would mean the Ektar-relative ratio isn't landing); crushed shadows or harsh highlight clipping (would contradict the wide-latitude research) | `saturation`, `redBias`, `contrast`, `toeLift` vs Ektar's values; `grainBase` ratio (verified in numpy: ~1.52x Ektar, target 1.55x from real PGI data) | New; needs first device shoot |

**Stock-personality check (2026-07-23b: parameter spread WIDENED - curve
shape, Wood lift, sky density, and baseline-grain separation all increased
along the documented axes, orderings machine-verified, Rollei the fixed
anchor; judge on the next device re-shoot):**
Rollei/HIE/SFX/Moderate/Fine-Grain/Soft-Vintage
must be visibly distinct on the *same* source frame - primarily via halation
spread (tight vs. wide ring balance), sky density (`skyStrength`), highlight
ceiling (`ceiling`), and grain clump scale. If two stocks look
interchangeable on a normal daylight scene, that pair needs more parameter
separation in `core/FilmLook.kt`, not a new shader branch.

---

## How to run this checklist

1. Shoot (or reprocess from gallery) each scene above with every preset in
   the relevant family.
2. Compare against the "Expected behavior" column; flag anything matching
   a listed failure mode.
3. Update the Status column with the release/commit checked and a one-line
   verdict.
4. File a plan doc (`docs/PLAN_<date>_<topic>.md`) for anything that fails,
   following this repo's existing convention.

This file intentionally has no automated image assertions: the rendering
pipeline is a live OpenGL shader driven by camera/gallery input, and this
repo has no camera-capable CI. Physical-device verification remains
mandatory, consistent with the "Test status" section of the README.

---

## Fujifilm-inspired integration status (2026-08-18)

| Gate | Status | Evidence / boundary |
|---|---|---|
| Baseline and fixture manifest | **PASS — source verified** | `docs/fujifilm-integration/baseline-2026-08-04.md` records the clean `7154f6b` baseline and hashes of preserved fixtures. |
| Pure working-space and tone math | **PASS — CI executed** | `core/color/WorkingSpaceMath.kt` and `ToneMath.kt` have JVM tests for finite handling, round trips, monotonicity, shoulder slope, and bounds; the exact pushed head passed GitHub Actions Android CI. |
| Shared shader stage and family ordering | **PASS — CI executed** | `SharedFilmShaderContractTest.kt` checks generic uniforms, post-front-end ordering, defined toe edges, preset indices, and reset markers; exact head `8648f9f` passed CI. |
| Visible-spectrum profiles | **PARTIAL — source implemented** | Archive Chrome, Cinematic Neutral, and Warm Negative are data-driven `STANDARD_FILM` profiles with original names and disclosure. Visual calibration is not complete. |
| Calibration harness | **PASS — Python smoke tests** | `tools/fujifilm_calibration/` keeps tone, grain, colour, and chart metrics separate; six deterministic tests and package import smoke tests passed with the system Python. `pytest` is not installed in the Hermes venv. |
| Hue-sector/color-density analytic stage | **PASS — source + Python checks** | Six data-driven hue sectors are consumed by the shared shader stage for the three visible profiles; Kotlin and NumPy contracts cover wraparound, neutral stability, midtone weighting, compression, finiteness, and bounds. No LUT residual is claimed. |
| Material-confidence analytic stage | **PASS — source + JVM test source** | `MaterialConfidenceMath.kt` provides bounded continuous fields and near-black reliability gating; the live shader uses smooth post-front-end confidence blending. Android execution remains CI-gated. |
| Aerochrome / mono-IR shared refinements | **NOT RUN** | Shared post-spectral tone/protection/density uniforms are now wired for both families, but no Android/GL/device render was executed. Existing physical validation rows remain unchanged. |
| Moto Edge 60 Fusion re-shoots | **NOT RUN** | No physical device connection was available from this shell. |

The implementation status and exact source/build/CI evidence are tracked in
`docs/fujifilm-integration/IMPLEMENTATION_STATUS.md`. A source or CI result
must not be promoted to a device `PASS` without the named capture flows.

---

## Grain quality upgrade (2026-07-23d, in progress)

**Baseline verification (step 1 of 4, done):** numpy port of `grainHash`/`valueNoise`/
`filmGrain` + the grain-application block, validated against a real captured photo
(wide luma range: near-black shadow tile through saturated mid-blue water to bright
sky reflection). **Confirmed numerically on real pixels:** mono-IR presets
(`uPreset<=5`) show a peaked, luma-dependent grain amplitude (tapers in both shadow
and highlight); every color/classic preset (Aerochrome ×5, Classic Film ×3, including
Tri-X routed through the classic branch) shows **perfectly flat** amplitude across
every luma bucket including the deepest shadow and brightest highlight — grain does
not respond to exposure at all outside the six original mono-IR stocks. This directly
explains the "dead-flat pool blacks" and "no highlight rolloff" observations from the
2026-07-23 capture batch review. See
`docs/PLAN_2026-07-23d_grain-quality-upgrade.md` and
`docs/assets/grain-baseline-2026-07-23/` (script, report, reference photo, per-look
renders) for full detail. Steps 2-4 (universal density curve, per-channel color grain,
clump irregularity) are planned but not started; each requires its own numpy
verification pass before touching `SpectralGlPipeline.kt`, and all require on-device
confirmation before shipping.

**Step 2 shipped (2026-07-23e):** the mono-only gate is removed; every preset now
uses the same validated Gaussian density curve. Mono presets verified byte-identical
before/after (numpy pre-check). Color/classic presets go from flat amplitude to the
same peaked/tapered curve — note this makes near-black amplitude *lower*, not higher
(the curve tapers at both ends by design, matching real print-grain visibility and
mono's existing behavior); if shadow grain still reads as too subtle after an
on-device check, that's a separate amplitude-scale question, not something this step
claims to fix. See `docs/PLAN_2026-07-23d_grain-quality-upgrade.md` §2 step 2 and
`docs/assets/grain-baseline-2026-07-23/step2/`. Needs on-device confirmation.

**Steps 3+4 shipped (2026-07-23f):** per-channel color grain (chromatic presets only,
gated by the same `monoMix` uniform Classic Film already uses, so Tri-X falls back to
scalar automatically) and a clump-irregularity amplitude multiplier (mean-preserving,
applied to every preset including mono — an intentional texture change, not claimed
regression-safe like steps 2-3). Found and fixed a real latent bug along the way:
`uStdTone3`/`monoMix` was missing from the cross-family uniform reset pattern already
used for `uAeroTone`/`uMonoCurve`, which would have made the new chroma gate silently
session-order-dependent (shoot Tri-X, switch to Aerochrome, chroma grain disables
itself). Reproduced in the numpy pre-check before the fix shipped. See
`docs/PLAN_2026-07-23d_grain-quality-upgrade.md` steps 3-4 and
`docs/assets/grain-baseline-2026-07-23/step3-4/`. Needs on-device confirmation.

**Step 5 — device questions resolved by measurement (2026-07-24):** the three parked
"needs on-device confirmation" questions were turned into measurements against the real
2026-07-23 capture batch and exact ports of the shipped math. This found **four real
defects**, now fixed (`docs/PLAN_2026-07-24_grain-step5-defect-fixes.md`):

1. **Chroma axis was not luma-neutral** — the green coefficient `-0.5*(nCr+nCb)`
   cancelled red but leaked −0.180 of the blue term into luma, injecting extra luma
   noise equal to 18% of the chroma amplitude. Fixed with the exact solved
   coefficients; leak now ~1e-8.
2. **Chroma grain was FINER than luma grain** (1.54x the frequency) — backwards
   physically and perceptually, and precisely what reads as colour fringing rather than
   film speckle. Now 0.52-0.94x (coarser).
3. **Deep-shadow grain quantised away** — measured 0.38-0.49 LSB amplitude below luma
   0.05, under the 8-bit step. Root cause was NOT black-point clamping (that hypothesis
   was tested and rejected). Fixed with a deep-shadow density floor that is
   bit-identical to the bare Gaussian for luma >= 0.34, so highlight protection is
   provably preserved.
4. **The post-grain IGN dither was louder than the grain** (grain/dither texture ratio
   0.39-0.97 on Rollei — the dither never weaker). The visible texture on fine stocks
   was substantially the dither's fixed screen-space pattern. Fixed with conservative
   grain-aware dither displacement; sky dither assist untouched; ramp banding test
   passes.

Measured on the real capture batch: 52% of the mono pool frame sat in the deepest tone
bucket with 3.8x weaker texture than midtone, and 31.8% of pixels at or below 2/255 —
the numeric form of "dead flat pool blacks". After the fixes, film grain is the dominant
shadow texture on every stock that has meaningful grain (share 0.73-1.09); Ektar and
Fine-Grain remain dither-dominated by deliberate product value, flagged rather than
silently changed. Proof strips and full reports in
`docs/assets/grain-verification-2026-07-24/`.

**Still requires the device:** the aesthetic judgement these measurements cannot make —
whether the corrected grain looks right on real scenes at real viewing sizes, and
whether the fine-stock (Ektar/Fine-Grain) amplitude wants a product decision.

**Real-emulsion amplitude calibration (2026-07-24, second pass):** the "product taste"
framing for Ektar/Fine-Grain/Rollei amplitude was wrong — fidelity to the real emulsion
is measurable, not a matter of taste. Researched official Kodak/Ilford/Rollei technical
data sheets (`docs/PLAN_2026-07-24b_real-emulsion-grain-calibration.md`):

- **Rollei IR 400** was ~3x too quiet vs Tri-X/HIE. Real diffuse RMS granularity (same
  convention, directly comparable): Rollei=11, Tri-X=17, HIE=18 — Rollei should be
  moderately finer, not dramatically so. `grainBase` 0.10 → 0.19, landing within a few
  percent of both real ratios.
- **Ektar 100** was rendering as functionally zero grain (peak 0.27 LSB, always under
  the dither) which contradicts Kodak's own Print Grain Index data: real Ektar crosses
  the PGI=25 visibility threshold at 8x10 print (PGI 38) and is clearly above it at
  16x20 (PGI 66) — subtly but genuinely visible, not literally grainless. `grainBase`
  0.02 → 0.05 (peak 0.27 → 0.67 LSB) — clears the dither for the first time while
  staying under half of corrected Rollei, the next-quietest stock. PGI and shader LSB
  aren't on a convertible scale, so this is a reasoned judgment call, not a derived
  number, unlike Rollei's precise ratio calculation.
- **CineStill 800T's** shadow floor was pointed the wrong direction for that specific
  stock: real Vision3 500T uses Dye Layering Technology specifically engineered to
  *suppress* shadow grain, opposite to the universal floor added in the prior fix. Added
  a per-look `shadowFloorScale` (repurposing the previously-unused `uStdTone3.z`),
  default 1.0 (bit-identical for every other stock), set to 0.35 for CineStill only.
  Sourced on direction, not magnitude.
- **Ilford SFX 200** left unchanged — confirmed no RMS granularity published in Ilford's
  own technical data sheet after a full fetch, a real absence rather than a research gap.
- **Fine-Grain IR / Moderate IR / Soft Vintage IR** left unchanged — generic composites
  with no single real emulsion to check fidelity against.

Full acceptance suite re-verified against corrected values: chroma/luma-neutrality and
clump-blotching checks unchanged-pass even at Rollei's near-doubled amplitude; shadow
grain share rose to 0.98-1.05 (Rollei) and 0.36-0.39 (Ektar); CineStill's deepest-shadow
grain share fell from 0.95/0.90 to 0.51/0.58, confirming the reduced floor takes effect
as sourced. Highlight protection re-confirmed bit-identical by construction (shadowLift
itself is exactly 0 above luma 0.34, independent of the scale multiplier). Reports and
recalibrated proof strip in `docs/assets/grain-verification-2026-07-24/real-emulsion-calibration/`.

**Correction (2026-07-24, third pass):** a real device photo of a detailed subject (camo-
pattern bag, straps) showed Rollei reading noisy - the ratio fix above correctly matched
real RMS granularity data, but doubled Rollei's absolute peak amplitude (2.24->4.26 LSB)
by anchoring it to Tri-X/HIE's absolute values, which were only ever tuned by eye and
never independently re-verified against a real busy subject. Dialed back to
`grainBase=0.14` (peak ~3.14 LSB) - a deliberate partial correction, still meaningfully
closer to the real ratio than the original under-tuned 0.10 was, without fully
committing to a target whose absolute scale depends on Tri-X/HIE's unverified baseline.
**Tri-X 400, Kodak HIE, and Soft Vintage IR's absolute grain levels (peak 6.71/6.46/5.61
LSB respectively, unchanged since the original step-1-5 grain work) are flagged as open**
- none of them have been stress-tested on a real busy/detailed subject the way this
correction just was for Rollei, and the same "ratio-correct but never absolute-verified"
concern applies to all three.

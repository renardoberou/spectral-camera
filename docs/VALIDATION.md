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
| A8 | Mixed urban greenery | Species/vigor variation visible (not one flat crimson slab); olive/yellow-green trees render distinctly from deep-green canopy. | All foliage collapsing to one uniform red regardless of species. | `species` continuum + `oliveVeg` branch | Reviewed; needs re-shoot |

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
| M1 | Noon sky with cloud detail | Sky suppressed toward Zone I-II; cloud structure stays visible, not crushed to a flat void. | Sky banding/seam; clouds disappearing into a flat dark plateau. | `skyDown` / `skyStr` in `irLuminance()`; IGN dither | **2026-07-23 on-device (overcast): sky-blob smudges confirmed on this scene too; fixed same day (proportional `skyChroma` ramp, decisive-blueness `skyHazy`, 0.86 suppression cap). Needs re-shoot to confirm; cloud-structure retention still to be judged on a blue-sky-with-clouds scene.** |
| M2 | Reflective water | Dark but alive: Zone I-II with visible specular ripple, never a dead void. | Pure Zone-0 black water with no sheen ("void-black"). | Water floor+sheen keyed on source darkness OR classified suppression (2026-07-23 fix) | **2026-07-23 on-device: FAILED (bright blue pool rendered as void on all 6 presets - the v1.14 floor keyed on source darkness only). Fixed same day (suppression-keyed floor); needs re-shoot to confirm.** |
| M3 | Wooded shadow | Shadowed canopy keeps intra-canopy structure (tone-modulated Wood lift), not a fused white sheet. | Shadow canopy flattening to solid white or staying flat dark with no glow. | `toneMod` Wood lift + `shadowVegM` blue-cast-tolerant shadow-canopy branch (2026-07-23) | **2026-07-23 on-device: FAILED (blue-cast shaded canopy fell out of the veg classifier and crushed to posterized black on all 6 presets). Fixed same day; needs re-shoot to confirm.** |
| M4 | Leaf detail against sky | Individual leaf/branch silhouettes stay separable against a suppressed sky; no matte-line halo at the foliage/sky boundary. | Hard cutout edges; sky mask bleeding onto leaf silhouettes. | `skyMask` per-pixel gate + generous soft edge | Reviewed; needs re-shoot |
| M5 | Bark / masonry / stone | Even midtone texture retained; no classifier flicker ("leopard-spot" patchwork). | Blotchy black/white patches on a uniform material from per-pixel chroma noise. | Chroma-bilateral `srcC` denoise feeding classifiers | Fixed; needs re-shoot |
| M6 | Pale skin | Slight brightening (NIR penetration), smooth, not blown out. | Skin blowing to paper white; skin picking up the sky/water suppression. | `skin` lift branch in `irLuminance()` | Reviewed; needs re-shoot |
| M7 | Red clothing / bright man-made objects | Renders on the plain film tone response, no special-cased artifacts. | Colour-classifier false positives (misread as foliage/sky). | Chromaticity gates require genuine green/blue dominance, not just brightness | Reviewed; needs re-shoot |
| M8 | Haze / overcast field | Flat, low-contrast light captured faithfully - soft but not muddy; foliage still gets a mild glow. | Overcast scenes reading identically to full sun (no differentiation); flat light going muddy grey. | `skyHazy` absolute-brightness detector alongside chroma detector | Reviewed; needs re-shoot |

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

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
| A1 | Blue sky with clouds | Deep cyan-blue clear sky, clouds stay bright and structurally readable (not flattened to a single hue plateau); no banding in the gradient. | Washed-lavender sky; clouds losing texture; visible 8-bit contour bands. | `skyMask` + `skyCol` ramp keyed on smoothed luma; IGN dither | Reviewed in P1/P3 passes (docs/PLAN_2026-07-16); needs fresh device re-shoot per release |
| A2 | Pale hazy sky | Sky renders paler/less saturated than clear noon sky but still reads as sky, not blown white or a hard edge against the horizon. | Hard sky/horizon seam; sky clipping to pure white; sky going muddy grey. | `clearBlue` / `lift` gating in `aerochrome()` sky ramp | Needs re-shoot |
| A3 | Skylit concrete / neutral walls | Cool pale neutral, not saturated blue or lavender. | Shaded white facades rendering as solid blue/purple ("sticker" look). | `greyC` cool-cast branch (P3 fix, shipped v1.13.1) | Fixed; spot-check on new scenes each release |
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

---

## Monochrome IR

| # | Scene | Expected behavior | Unacceptable failure modes | Engine hook | Status |
|---|---|---|---|---|---|
| M1 | Noon sky with cloud detail | Sky suppressed toward Zone I-II; cloud structure stays visible, not crushed to a flat void. | Sky banding/seam; clouds disappearing into a flat dark plateau. | `skyDown` / `skyStr` in `irLuminance()`; IGN dither | Fixed (P1 grain/dither pass); needs re-shoot |
| M2 | Reflective water | Dark but alive: Zone I-II with visible specular ripple, never a dead void. | Pure Zone-0 black water with no sheen ("void-black"). | `monoWaterLife()` floor+detail (P4 fix) | Fixed (v1.14); needs re-shoot |
| M3 | Wooded shadow | Shadowed canopy keeps intra-canopy structure (tone-modulated Wood lift), not a fused white sheet. | Shadow canopy flattening to solid white or staying flat dark with no glow. | `toneMod` in `irLuminance()` Wood-effect lift | Reviewed; needs re-shoot |
| M4 | Leaf detail against sky | Individual leaf/branch silhouettes stay separable against a suppressed sky; no matte-line halo at the foliage/sky boundary. | Hard cutout edges; sky mask bleeding onto leaf silhouettes. | `skyMask` per-pixel gate + generous soft edge | Reviewed; needs re-shoot |
| M5 | Bark / masonry / stone | Even midtone texture retained; no classifier flicker ("leopard-spot" patchwork). | Blotchy black/white patches on a uniform material from per-pixel chroma noise. | Chroma-bilateral `srcC` denoise feeding classifiers | Fixed; needs re-shoot |
| M6 | Pale skin | Slight brightening (NIR penetration), smooth, not blown out. | Skin blowing to paper white; skin picking up the sky/water suppression. | `skin` lift branch in `irLuminance()` | Reviewed; needs re-shoot |
| M7 | Red clothing / bright man-made objects | Renders on the plain film tone response, no special-cased artifacts. | Colour-classifier false positives (misread as foliage/sky). | Chromaticity gates require genuine green/blue dominance, not just brightness | Reviewed; needs re-shoot |
| M8 | Haze / overcast field | Flat, low-contrast light captured faithfully - soft but not muddy; foliage still gets a mild glow. | Overcast scenes reading identically to full sun (no differentiation); flat light going muddy grey. | `skyHazy` absolute-brightness detector alongside chroma detector | Reviewed; needs re-shoot |

**Stock-personality check:** Rollei/HIE/SFX/Moderate/Fine-Grain/Soft-Vintage
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

# Validation: named failure scenes

**Status:** living document. Every look or output-pipeline change should be checked against this list before it ships, not just against attractive demo frames. Physical-device checks remain mandatory where noted.

This file describes expected behavior and unacceptable failure modes for both flagship film families and the pro output pipeline. It does not embed camera test images because the repository has no camera-capable CI; record release-specific pass/fail notes here after device testing.

Engine references point at `monoLook()` / `irLuminance()` / `irHDCurve()` and `aeroLook()` / `aerochrome()` in `core/gl/SpectralGlPipeline.kt`, parameterized per stock by `core/FilmLook.kt`.

---

## Aerochrome / false-colour IR

| # | Scene | Expected behavior | Unacceptable failure modes | Engine hook | Status |
|---|---|---|---|---|---|
| A1 | Blue sky with clouds | Deep cyan-blue clear sky; clouds stay bright and structurally readable; no visible gradient banding. | Washed-lavender sky; cloud texture loss; contour bands. | `skyMask` + `skyCol`; IGN dither | Reviewed in P1/P3; fresh device re-shoot required per release. |
| A2 | Pale hazy sky | Paler and less saturated than clear sky, but still reads as sky without a hard horizon edge. | Hard seam; pure-white clip; muddy grey. | `clearBlue` / `lift` gates | Needs re-shoot. |
| A3 | Skylit concrete / neutral walls | Pale neutral under cool light or pale warm cream under warm light. | Solid blue/purple shaded walls; neon/lime warm walls. | `greyC` neutral gate | Warm-cast bug fixed and checked on the real source; cool case needs re-shoot. |
| A4 | Deep-shadow foliage | Shadow foliage remains red/magenta and textured. | Muddy brown fall-through; flat patch. | Chromaticity `veg` / `oliveVeg` | Reviewed; needs re-shoot. |
| A5 | Skin | Waxy pale/sallow response, never foliage-red. | Skin goes red/magenta. | Red-dominant vegetation exclusion | Needs re-shoot. |
| A6 | Red painted objects | Green/yellow-green EIR reversal response rather than native red. | Unprocessed red “sticker.” | `manMade` dye pull | Needs re-shoot. |
| A7 | Water / pools / glass | Vivid indigo where appropriate; no foliage-red shoreline contamination. | Purple pool; red bleed; false vegetation. | `vividBlue` / `waterC`; water sanctity | Water-sanctity fix shipped; needs re-shoot. |
| A8 | Mixed urban greenery | Species/vigor variation remains visible. | Every plant collapses to one crimson slab. | `species` continuum + `oliveVeg` | Reviewed; needs re-shoot. |

**Family-coherence check:** Classic/Soft/Dense must preserve the same material classification while visibly changing contrast, density, and saturation. Gold changes warmth/teal balance without changing scene assignment. Faded should resemble an aged print of Classic.

The 2026-07-21 real-device dusk-window test exposed two common-mode failures: warm stucco became neon yellow and the five dark-blue paths were nearly identical. The neutral gate and dark-path density control were fixed and numerically checked against that real image. Clear-sky A1/A2 still require new captures where the sky classifier actually fires.

**Watch item:** Gold/Faded can render dark foliage reflected in glass green because the vegetation classifier fires on the reflection and the gold vegetation push amplifies it. Candidate fix: gate that push with the existing surface-smoothness signal.

---

## Monochrome IR

| # | Scene | Expected behavior | Unacceptable failure modes | Engine hook | Status |
|---|---|---|---|---|---|
| M1 | Noon sky with cloud detail | Sky sits near Zone I–II while cloud structure remains visible. | Flat black plateau; seam; banding. | `skyDown` / `skyStr`; IGN dither | Fix shipped; needs re-shoot. |
| M2 | Reflective water | Dark but alive, with Zone-I tone and specular ripple. | Void-black water with no sheen. | mono water floor + detail | Fix shipped; needs re-shoot. |
| M3 | Wooded shadow | Intra-canopy structure survives the Wood lift. | Fused white foliage or dead dark canopy. | `toneMod` | Reviewed; needs re-shoot. |
| M4 | Leaf detail against sky | Branch and leaf silhouettes stay separated without matte edges. | Hard cutout; sky mask bleed. | `skyMask` edge gate | Reviewed; needs re-shoot. |
| M5 | Bark / masonry / stone | Midtone texture stays even and stable. | Chroma-classifier leopard spots. | bilateral `srcC` | Fix shipped; needs re-shoot. |
| M6 | Pale skin | Mild smooth lift, not paper-white. | Blown skin or sky suppression on skin. | skin branch | Reviewed; needs re-shoot. |
| M7 | Red clothing / bright objects | Plain film-tone response without classifier artifacts. | False vegetation/sky response. | chromaticity gates | Reviewed; needs re-shoot. |
| M8 | Haze / overcast field | Soft, faithful low-contrast response with mild foliage glow. | Muddy grey or identical response to hard sun. | `skyHazy` | Reviewed; needs re-shoot. |

**Stock-personality check:** Rollei/HIE/SFX/Moderate/Fine-Grain/Soft-Vintage must remain visibly distinct on the same source through sky density, highlight ceiling, halation spread, acutance, and grain clump scale. If two stocks are interchangeable, separate their `core/FilmLook.kt` parameters rather than adding a bespoke shader branch.

---

## Pro output pipeline

Pure geometry is covered by `OutputGeometryTest`; stream negotiation, GPU output, and DNG persistence require a physical camera.

| # | Test | Expected behavior | Unacceptable failure modes | Implementation hook | Status |
|---|---|---|---|---|---|
| O1 | Full Resolution | Uses the highest practical 4:3 JPEG source, quality 100, and preserves the largest GPU-supported processed dimensions. | Silent 1080 cap; quality-95 source; stretched output. | `CameraController.buildImageCapture()` + `OutputMode.FULL_RESOLUTION` | Code complete; device verification required. |
| O2 | HQ 1080 | High-resolution source is center-cropped to 16:9, fully rendered, then exported at exact 1920×1080 or 1080×1920. | Render after premature low-res scaling; wrong orientation; soft one-step resize artifacts. | `OutputPipeline.prepareForRender()` / `progressiveDownsampleToFullHd()` | Geometry unit-tested; visual/device verification required. |
| O3 | Fast 1080 | Requests a low-latency 16:9 stream near Full HD and exports exact Full HD. | Still uses full-resolution latency; wrong aspect; non-exact dimensions. | `OutputMode.FAST_1080` capture selector | Code complete; device verification required. |
| O4 | Mod-16 fallback | A 1920×1088 source becomes a centered 1920×1080 result, removing four rows from each side of the long dimension. | Stretching; off-center crop; 1920×1088 final file. | `OutputGeometry.centerCrop()` | JVM test passes when CI is green. |
| O5 | Portrait output | Portrait source becomes exact 1080×1920 with a centered 9:16 crop. | Rotated result; 1920×1080 landscape file; asymmetric crop. | orientation-aware `fullHdSize()` | JVM test passes when CI is green. |
| O6 | Preview/export intent | Material assignment and stock personality match preview; still-only auto-levels/HQ finishing may refine tone and detail. | Different preset appearance; foliage/sky/water classification flips between preview and export. | shared shader; still output wrapper | Needs paired device captures. |
| O7 | RAW supported | Pro output screen enables DNG; one shutter saves processed JPEG plus a non-empty, externally readable DNG. | Empty/corrupt DNG; two shutter events; missing processed JPEG. | CameraX `OUTPUT_FORMAT_RAW_JPEG` dual-file capture | Code complete; supported-device verification required. |
| O8 | RAW unsupported | DNG control is disabled; capture continues as JPEG without failure. | Camera refuses to bind; capture button fails; misleading RAW claim. | capability query + JPEG fallback | Code complete; unsupported-device verification required. |
| O9 | RAW stream fallback | If RAW+JPEG is advertised but cannot bind beside preview, the controller rebuilds a JPEG-only session and reports RAW unavailable. | Black preview; crash loop; stale enabled capability. | `bindUseCases()` fallback | Code complete; hard to reproduce, device matrix required. |
| O10 | RAW + Fast 1080 | DNG request keeps a high-quality sensor source while processed export remains exact Full HD. | Low-resolution DNG request; processed size drifts; UI implies fast latency. | RAW override in `buildImageCapture()` + Pro output copy | Code complete; device verification required. |
| O11 | File identity | Names and metadata distinguish `proc`, `orig`, and `dng`, and include output mode. Legacy `raw` JPEG names still load as originals. | DNG called JPEG; original JPEG called RAW; old gallery disappears. | `MediaRepository` naming/parser | Code review complete; MediaStore verification required. |
| O12 | Family regression | All 11 film presets preserve their established rendering in Full Resolution, HQ 1080, and Fast 1080 apart from expected scale/detail differences. | New output mode changes hue, material classification, halation, or stock order. | shared `SpectralGlPipeline` unchanged | Needs validation grid on device. |

### How to run the output checklist

1. Use the same static scene and exposure for all three output modes.
2. Record source and processed pixel dimensions, file size, capture latency, and active camera.
3. Compare a matched 100% crop from Full Resolution and HQ 1080; HQ should be cleaner at delivery size, not differently graded.
4. Test landscape and portrait orientation, including a device that exposes 1920×1088 or another aligned size if available.
5. Toggle DNG on a RAW-capable rear camera, a non-RAW front camera, and after switching lenses.
6. Open DNG output in at least one independent RAW developer and verify metadata/data integrity.
7. Reprocess one gallery image through every output mode to confirm the same geometry rules apply to imports.
8. Update the Status column with device, Android version, release/commit, and a one-line verdict.

---

## General release procedure

1. Shoot or reprocess every relevant scene with each preset in the family.
2. Compare against Expected behavior; flag anything matching an unacceptable failure mode.
3. Run all output-pipeline checks for any capture/export change.
4. Update Status with the release/commit checked and a concise verdict.
5. Create a dated plan document for failures, following the existing `docs/PLAN_<date>_<topic>.md` convention.

Automated image assertions are still absent because the renderer is a live OpenGL pipeline driven by camera/gallery input. Physical-device verification remains mandatory.

# Validation: named failure scenes

**Status:** living document. Every look, capture, HDR, or output change must be checked here before release. Camera, motion, memory, gain-map encoding, and HDR-display checks require physical devices.

The film engine remains in `core/gl/SpectralGlPipeline.kt`, parameterized by `core/FilmLook.kt`. HDR capture and source preparation live in `core/camera/CameraController.kt` and `core/hdr/`; output geometry and storage live in `core/export/` and `core/media/`.

---

## Aerochrome / false-colour IR

| # | Scene | Expected behavior | Unacceptable failure modes | Engine hook | Status |
|---|---|---|---|---|---|
| A1 | Blue sky with clouds | Deep cyan-blue clear sky; clouds bright and structurally readable; no contour bands. | Lavender sky; flattened clouds; visible banding. | `skyMask`, `skyCol`, IGN dither | Fresh release re-shoot required. |
| A2 | Pale hazy sky | Paler and less saturated than clear sky, with a soft horizon transition. | Hard seam; pure-white clip; muddy grey. | `clearBlue`, `lift` | Needs re-shoot. |
| A3 | Skylit concrete / neutral walls | Pale cool neutral or warm cream according to illumination. | Saturated blue/purple walls; neon/lime warm wall. | neutral gate | Warm-cast fix checked on real source; cool case needs re-shoot. |
| A4 | Deep-shadow foliage | Shadow foliage remains red/magenta and textured. | Muddy-brown fall-through or flat patch. | chromaticity `veg` / `oliveVeg` | Needs re-shoot. |
| A5 | Skin | Pale/sallow EIR response, never foliage-red. | Red/magenta skin. | red-dominant vegetation exclusion | Needs re-shoot. |
| A6 | Red painted objects | Green/yellow-green reversal response rather than native red. | Unprocessed red “sticker.” | man-made dye pull | Needs re-shoot. |
| A7 | Water / pools / glass | Indigo where appropriate; no foliage-red shoreline contamination. | Purple water; red bleed; false vegetation. | water classification/sanctity | Fix shipped; needs re-shoot. |
| A8 | Mixed urban greenery | Visible species/vigor variation. | Every plant collapses to one crimson slab. | species continuum, `oliveVeg` | Needs re-shoot. |

**Family coherence:** Classic/Soft/Dense must preserve material assignment while changing contrast, density and saturation. Gold changes warmth/teal balance without changing classification. Faded should read as an aged Classic print.

**Watch item:** Gold/Faded may render dark foliage reflected in glass green. Candidate fix remains a surface-smoothness gate on the gold vegetation push.

---

## Monochrome IR

| # | Scene | Expected behavior | Unacceptable failure modes | Engine hook | Status |
|---|---|---|---|---|---|
| M1 | Noon sky with cloud detail | Sky near Zone I–II while cloud structure survives. | Flat black plateau; seam; banding. | `skyDown`, `skyStr`, dither | Needs re-shoot. |
| M2 | Reflective water | Dark but alive, with Zone-I tone and specular ripple. | Void-black water. | water floor + detail | Needs re-shoot. |
| M3 | Wooded shadow | Intra-canopy structure survives the Wood lift. | Fused white or dead dark canopy. | `toneMod` | Needs re-shoot. |
| M4 | Leaves against sky | Fine silhouettes remain separate without matte edges. | Hard cutout or sky-mask bleed. | sky edge gate | Needs re-shoot. |
| M5 | Bark / masonry / stone | Stable midtone texture. | Classifier leopard spots. | bilateral classification colour | Needs re-shoot. |
| M6 | Pale skin | Mild smooth lift, not paper white. | Blown skin or sky suppression. | skin branch | Needs re-shoot. |
| M7 | Red clothing / bright objects | Ordinary film-tone response without classifier artifacts. | False foliage or sky response. | chromaticity gates | Needs re-shoot. |
| M8 | Haze / overcast field | Soft low-contrast response with mild foliage glow. | Muddy grey or same response as hard sun. | hazy-sky detector | Needs re-shoot. |

**Stock personality:** Rollei/HIE/SFX/Moderate/Fine-Grain/Soft-Vintage must remain visibly distinct on one source through sky density, ceiling, halation, acutance and grain.

---

## Computational HDR pipeline

Pure bracket, transfer, tone-map, deghost-weight and translation math is covered by `HdrMathTest`. Real exposure timing, alignment, motion and memory require camera testing.

| # | Test | Expected behavior | Unacceptable failure modes | Implementation hook | Status |
|---|---|---|---|---|---|
| H1 | Auto bracket timing | Each CameraX compensation future completes before its JPEG is captured; reference exposure is restored after the bracket. | Three identical exposures; stale EV; preview left dark/bright. | `captureHdrBracket()`, `setExposureCompensationIndex().await()` | Code complete; device test required. |
| H2 | Manual shutter bracket | ISO remains fixed; shutter produces distinct under/reference/over frames; base shutter is restored. | AE re-enabled; ISO changes; duplicate frames; stuck shutter. | Camera2 request options + `await()` | Code complete; MANUAL_SENSOR device required. |
| H3 | Limited EV range | Planner uses three distinct supported values where possible; otherwise capture truthfully falls back to Standard. | Duplicate exposures presented as HDR; capture failure. | `HdrBracketPlanner` | JVM covered; lens matrix required. |
| H4 | Static tripod scene | Merge recovers highlights and shadow separation without changing object position or adding halos. | Double edges; flat conventional-HDR look; local halos. | translation alignment + global tone map | Device/reference test required. |
| H5 | Handheld translation | Small camera movement aligns to a common crop with no exposed borders. | Black edge, wrap, stretched frame, gross softness. | log-radiance translation estimator + common crop | Math covered; handheld test required. |
| H6 | Moving person / leaves | Disagreement biases toward the normal exposure. Motion may lose HDR range but should not become multiple ghosts. | Three silhouettes; colour fringes; checkerboard ghost. | radiance deghost weight | Weight JVM covered; motion test required. |
| H7 | Water / waves | Moving texture stays coherent through reference fallback; film water rendering remains dark/credible. | Repeated ripples; glowing water; red EIR contamination. | deghost + final-luma gain gate | Device test required. |
| H8 | Natural tone map | Better cloud/highlight information and modest shadow recovery while retaining photographic contrast. | Grey shadows; flat midtones; clipped highlights. | extended Reinhard luminance map | Monotonic JVM test; image test required. |
| H9 | Filmic tone map | Deeper toe and long shoulder feed the stock curve without double-crushing shadows. | Black clipping; muddy mids; abrupt shoulder. | ACES-style global luminance map | Monotonic JVM test; image test required. |
| H10 | Low Contrast tone map | Severe backlight fits in range for later manual grading. | Local halo; colour shift; posterization. | logarithmic global map | Monotonic JVM test; image test required. |
| H11 | Classifier stability | HDR improves clipped foliage/sky inputs but does not make one material change class merely because a tone map changed. | Tone-map-dependent foliage/water/skin classification flips. | normalized HDR bitmap feeds unchanged film shader | Full family grid required. |
| H12 | Memory pressure | HDR source remains within the configured practical stream; no OOM during capture, merge, render or save. | Process death; frozen preview; corrupt file. | HDR resolution policy + row-wise merge | Device heap/latency profiling required. |

### HDR scene protocol

1. Lock framing and focus; shoot Standard plus all three HDR tone maps.
2. Record each source EV/shutter, source dimensions, merge time, render time, peak memory and final dimensions.
3. Include a clipped-cloud/backlit scene, deep foliage, a static interior/window, handheld translation, a walking subject, wind-blown leaves and moving water.
4. Compare material classification, not only dynamic range.
5. Verify exposure/manual state is restored after success, cancellation and error.

---

## Ultra HDR export and display

| # | Test | Expected behavior | Unacceptable failure modes | Implementation hook | Status |
|---|---|---|---|---|---|
| U1 | Android 14+ encode | `uhdr` file is a valid JPEG, decodes normally, and `Bitmap.hasGainmap()` is true after round trip. | SDR-only file mislabeled Ultra HDR; corrupt JPEG; lost gain map. | `UltraHdrExporter`, Bitmap JPEG encode | Device round-trip required. |
| U2 | SDR fallback viewer | Non-HDR/legacy viewer shows the processed SDR base with the intended film look. | Washed or dark fallback; unsupported-file error. | JPEG/R base image | Independent viewers required. |
| U3 | HDR display viewer | Detail viewer switches window to HDR only for an actual decoded gain map, and restores the prior mode on close. | Whole app stuck HDR; mode enabled for SDR; no highlight increase. | `Bitmap.hasGainmap()`, dynamic window color mode | Android 14+ HDR display required. |
| U4 | Post-film gain validity | Bright clouds/speculars may gain headroom; intentionally dark EIR sky, IR water and dense shadows remain dark. | Dark blue sky glows; black water becomes luminous; colour wash. | pre-film headroom × post-film luminance gate | Aero/mono grids required. |
| U5 | Gain-map smoothness | Gain transition is low-frequency and visually smooth. | Gain-map block grid, halo, pumping or banding. | quarter-scale/bounded gain map | 100% and HDR-display inspection required. |
| U6 | Platform fallback | Android <14 disables Ultra HDR control and saves normal SDR JPEG. | Crash on older API; misleading enabled control. | API guard | API 26/33 tests required. |
| U7 | Share/export | Shared file retains gain-map capability in compatible destination and remains readable elsewhere. | Re-encoding strips gain silently in app share; unreadable destination. | direct URI share | App matrix required. |

---

## Pro output and RAW

| # | Test | Expected behavior | Unacceptable failure modes | Status |
|---|---|---|---|---|
| O1 | Standard Full Resolution | Highest practical source, quality 100, largest GPU-supported processed dimensions. | Silent 1080 cap; quality-95 source; stretch. | Device test required. |
| O2 | HQ 1080 | High-resolution render then exact `1920×1080` / `1080×1920` progressive reduction. | Premature low-res render; wrong orientation; non-exact size. | Geometry JVM covered; visual test required. |
| O3 | Fast 1080 | Low-latency 16:9 source and exact Full HD result. | Full-res latency; 1920×1088 final; stretch. | Device test required. |
| O4 | Mod-16 fallback | `1920×1088` center-crops to `1920×1080`. | Stretch or off-centre crop. | JVM covered. |
| O5 | RAW supported | Standard capture saves processed JPEG plus externally readable DNG. | Empty/corrupt DNG; missing processed JPEG. | Device test required. |
| O6 | RAW unsupported/fallback | JPEG remains usable and UI reports RAW unavailable. | Bind failure, crash loop, stale enabled state. | Device matrix required. |
| O7 | RAW versus HDR policy | Enabling HDR disables RAW; enabling RAW returns to Standard and disables Ultra HDR. | Hidden triple-DNG capture; contradictory UI/metadata. | Settings setters + session bind | UI/device test required. |
| O8 | File identity | `proc`, `uhdr`, `orig`, `dng`, output mode and `HDR3`/`SDR1` are truthful; older names still load. | DNG called JPEG; Standard mislabeled HDR; old gallery disappears. | Parser review complete; MediaStore test required. |
| O9 | Family regression | All 11 presets retain established hue/material/stock identity across Standard/HDR and Full/HQ/Fast. | Output path changes film identity. | Full validation grid required. |

---

## General release procedure

1. Run every relevant Aerochrome and monochrome scene in Standard and Computational HDR.
2. Run all output, RAW and Ultra HDR checks for capture/export changes.
3. Verify dimensions, latency, memory, exposure restoration, file metadata and independent decode.
4. Record device, Android version, lens, commit/release and verdict in the Status column.
5. File a dated plan document for every failure rather than tuning from one attractive frame.

There is no camera-capable CI or automated HDR display. Green JVM/build CI is necessary but not sufficient for release.

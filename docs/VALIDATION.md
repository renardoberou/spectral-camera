# Validation: named failure scenes

**Status:** living document. Every film, capture, focus, HDR, RAW, or output change must be checked here before release. Camera motion, autofocus, memory, gain-map encoding, and HDR-display checks require physical devices.

The film engine remains in `core/gl/SpectralGlPipeline.kt`, parameterized by `core/FilmLook.kt`. Camera/focus capture lives in `core/camera/CameraController.kt`; HDR source preparation lives in `core/hdr/`; output geometry and storage live in `core/export/` and `core/media/`.

---

## Aerochrome / false-colour IR

| # | Scene | Expected behaviour | Unacceptable failure modes | Engine hook | Status |
|---|---|---|---|---|---|
| A1 | Blue sky with clouds | Deep cyan-blue clear sky; clouds bright and structurally readable; no contour bands. | Lavender sky; flattened clouds; visible banding. | `skyMask`, `skyCol`, IGN dither | Release re-shoot required. |
| A2 | Pale hazy sky | Paler and less saturated than clear sky, with a soft horizon transition. | Hard seam; pure-white clip; muddy grey. | `clearBlue`, `lift` | Release re-shoot required. |
| A3 | Skylit concrete / neutral walls | Pale cool neutral or warm cream according to illumination. | Saturated blue/purple walls; neon/lime warm wall. | neutral gate | Release re-shoot required. |
| A4 | Deep-shadow foliage | Shadow foliage remains red/magenta and textured. | Muddy-brown fall-through or flat patch. | chromaticity `veg` / `oliveVeg` | Release re-shoot required. |
| A5 | Skin | Pale/sallow EIR response, never foliage-red. | Red/magenta skin. | red-dominant vegetation exclusion | Release re-shoot required. |
| A6 | Red painted objects | Green/yellow-green reversal response rather than native red. | Unprocessed red “sticker.” | man-made dye pull | Release re-shoot required. |
| A7 | Water / pools / glass | Indigo where appropriate; no foliage-red shoreline contamination. | Purple water; red bleed; false vegetation. | water classification/sanctity | Release re-shoot required. |
| A8 | Mixed urban greenery | Visible species/vigour variation. | Every plant collapses to one crimson slab. | species continuum, `oliveVeg` | Release re-shoot required. |

**Family coherence:** Classic/Soft/Dense must preserve material assignment while changing contrast, density, and saturation. Gold changes warmth/teal balance without changing classification. Faded should read as an aged Classic print.

---

## Monochrome IR

| # | Scene | Expected behaviour | Unacceptable failure modes | Engine hook | Status |
|---|---|---|---|---|---|
| M1 | Noon sky with cloud detail | Sky near Zone I–II while cloud structure survives. | Flat black plateau; seam; banding. | `skyDown`, `skyStr`, dither | Release re-shoot required. |
| M2 | Reflective water | Dark but alive, with Zone-I tone and specular ripple. | Void-black water. | water floor + detail | Release re-shoot required. |
| M3 | Wooded shadow | Intra-canopy structure survives the Wood lift. | Fused white or dead dark canopy. | `toneMod` | Release re-shoot required. |
| M4 | Leaves against sky | Fine silhouettes remain separate without matte edges. | Hard cutout or sky-mask bleed. | sky edge gate | Release re-shoot required. |
| M5 | Bark / masonry / stone | Stable midtone texture. | Classifier leopard spots. | bilateral classification colour | Release re-shoot required. |
| M6 | Pale skin | Mild smooth lift, not paper white. | Blown skin or sky suppression. | skin branch | Release re-shoot required. |
| M7 | Red clothing / bright objects | Ordinary film-tone response without classifier artefacts. | False foliage or sky response. | chromaticity gates | Release re-shoot required. |
| M8 | Haze / overcast field | Soft low-contrast response with mild foliage glow. | Muddy grey or same response as hard sun. | hazy-sky detector | Release re-shoot required. |

**Stock personality:** Rollei/HIE/SFX/Moderate/Fine-Grain/Soft-Vintage must remain visibly distinct on one source through sky density, ceiling, halation, acutance, and grain.

---

## Computational HDR and True RAW HDR

Pure bracket, transfer, tone-map, deghost-weight, Bayer, and translation math is covered by JVM tests. Real exposure timing, autofocus stability, motion, memory, and RAW metadata require camera testing.

| # | Test | Expected behaviour | Unacceptable failure modes | Status |
|---|---|---|---|---|
| H1 | JPEG auto bracket timing | Each exposure request becomes active before its JPEG is captured; the original exposure state returns afterward. | Identical bracket frames; stale EV; preview left dark/bright. | Device test required. |
| H2 | Manual shutter bracket | ISO remains fixed; shutter produces distinct under/reference/over frames; base shutter returns. | AE re-enabled; ISO changes; duplicate frames; stuck shutter. | MANUAL_SENSOR device required. |
| H3 | Limited exposure range | Uses every distinct bracket value available. If a real bracket cannot be formed, capture reports the limitation rather than inventing HDR frames. | Duplicate frames presented as a valid bracket. | Lens matrix required. |
| H4 | Static tripod scene | Recovers highlights and shadow separation without moving object geometry or adding halos. | Double edges; local halos; translucent second scene. | Device/reference test required. |
| H5 | Handheld translation | Small movement aligns to a common crop with no exposed borders. | Black edge, wrap, stretch, gross softness. | Device test required. |
| H6 | Moving person / leaves | Moving detail stays anchored to the reference; saved identity remains HDR/RAW HDR and may report motion protection. | Multiple silhouettes; capture failure; silent relabel to Standard. | Device test required. |
| H7 | Water / waves | Moving texture stays coherent; shoreline geometry remains single. | Repeated ripples; glowing water; false material assignment. | Device test required. |
| H8 | Natural tone map | Better cloud/highlight information and modest shadow recovery while retaining photographic contrast. | Grey shadows; flat midtones; clipped highlights. | Image test required. |
| H9 | Filmic tone map | Deeper toe and long shoulder feed the stock curve without double-crushing shadows. | Black clipping; muddy mids; abrupt shoulder. | Image test required. |
| H10 | Low Contrast tone map | Severe backlight fits in range for later grading. | Local halo; colour shift; posterization. | Image test required. |
| H11 | Classifier stability | HDR improves source information without changing a material class only because the tone map changed. | Tone-map-dependent foliage/water/skin flips. | Full family grid required. |
| H12 | Memory pressure | Capture, merge, film render, and save finish without process death. | OOM, frozen preview, corrupt file. | Heap/latency profiling required. |
| H13 | RAW DNG integrity | Saved bracket DNGs open independently and expose the expected shutter/ISO sequence. | Empty/corrupt DNG; mismatched capture metadata. | Independent RAW developer required. |
| H14 | RAW motion safety | Unsafe auxiliary RAW frames are excluded; the result remains True RAW HDR • motion protected. | Translucent secondary scene; alignment error reaching user. | Device test required. |
| H15 | Focus-plane stability | All JPEG/RAW bracket members use one optical focus plane where direct lens-distance hold is supported. | Focus breathing; refocus pulse; edge scale change between bracket frames. | Device test required. |

### HDR scene protocol

1. Lock framing where possible; shoot Standard plus all three HDR tone maps.
2. Record source EV/shutter, source dimensions, merge time, render time, peak memory, final dimensions, and focus mode.
3. Include a clipped-cloud/backlit scene, deep foliage, static interior/window, handheld translation, walking subject, wind-blown leaves, and moving water.
4. Compare material classification, not only dynamic range.
5. Verify exposure, white balance, and focus state restore after success, cancellation, and error.

---

## Ultra HDR export and display

| # | Test | Expected behaviour | Unacceptable failure modes | Status |
|---|---|---|---|---|
| U1 | Android 14+ encode | `uhdr` file is a valid JPEG, decodes normally, and `Bitmap.hasGainmap()` is true after round trip. | SDR-only file mislabeled Ultra HDR; corrupt JPEG; lost gain map. | Device round-trip required. |
| U2 | SDR fallback viewer | Legacy viewer shows the processed SDR base with the intended film look. | Washed/dark fallback; unsupported-file error. | Independent viewers required. |
| U3 | HDR display viewer | Detail viewer enables HDR only for a decoded gain map and restores normal mode on close. | Whole app stuck HDR; HDR enabled for SDR. | Android 14+ HDR display required. |
| U4 | Post-film gain validity | Bright clouds/speculars may gain headroom; intentionally dark EIR sky, IR water, and dense shadows remain dark. | Dark sky glows; black water becomes luminous; colour wash. | Aero/mono grids required. |
| U5 | RAW geometry | Gain map receives the same rotation, mirror, and 16:9 crop as the developed RAW image. | Vertical rectangle; misplaced scene brightness; repeated structures. | Portrait/landscape test required. |
| U6 | Gain-map mismatch safety | Spatially incompatible gain field saves as correct SDR rather than damaged Ultra HDR. | Corrupt or spatially shifted HDR file. | Fault/fallback test required. |
| U7 | Platform fallback | Android <14 disables Ultra HDR and saves ordinary SDR JPEG. | Crash; misleading enabled control. | API 26/33 test required. |
| U8 | Share/export | Shared file remains readable; compatible destinations retain gain-map capability. | Unreadable file or silent app-side re-encode. | App matrix required. |

---

## Pro output, source files, and Double Exposure

| # | Test | Expected behaviour | Unacceptable failure modes | Status |
|---|---|---|---|---|
| O1 | Standard Full Resolution | Highest practical source, quality 100, largest completed GPU-safe processed dimensions. | Silent 1080 cap; quality-95 source; stretch. | Device test required. |
| O2 | HQ 1080 | High-resolution render then exact `1920×1080` / `1080×1920` progressive reduction. | Premature low-res render; wrong orientation; non-exact size. | Geometry JVM covered; visual test required. |
| O3 | Fast 1080 | Low-latency 16:9 source and exact Full HD result. | 1920×1088 final; stretch. | Device test required. |
| O4 | Mod-16 fallback | `1920×1088` center-crops to `1920×1080`. | Stretch or off-centre crop. | JVM covered. |
| O5 | Standard RAW supported | Standard capture saves processed JPEG plus externally readable DNG. | Empty/corrupt DNG; missing processed JPEG. | Device test required. |
| O6 | RAW unsupported/fallback | JPEG remains usable and UI reports RAW unavailable. | Bind failure, crash loop, stale enabled state. | Device matrix required. |
| O7 | Capture-policy consistency | Standard RAW sidecar, JPEG HDR, True RAW HDR, Ultra HDR, and Double Exposure controls remain mutually truthful. | Contradictory UI/metadata or hidden source type. | UI/device test required. |
| O8 | File identity | `proc`, `uhdr`, `orig`, `src01/02`, `dng`, output mode, and capture token are truthful; older names still load. | DNG called JPEG; HDR called Standard; old gallery disappears. | Parser/MediaStore test required. |
| O9 | Double Exposure workflow | First press stores source and guide; second press saves one processed composite; cancel saves nothing. | Accidental single-frame save; stale first frame; doubled film grain/halation. | Device test required. |
| O10 | Double Exposure source saving | `src01` and `src02` match the two shutter frames when enabled. | Missing, swapped, or processed-as-source images. | Device test required. |
| O11 | Family regression | All 11 presets retain material and stock identity across Standard/HDR/RAW HDR/output modes. | Output path changes film identity. | Full validation grid required. |

---

## Focus modes

`FocusMathTest` covers normalized-to-diopter mapping and calibrated/uncalibrated labels. Actual lens motion, autofocus timing, and HDR focus hold require physical devices.

| # | Test | Expected behaviour | Unacceptable failure modes | Status |
|---|---|---|---|---|
| F1 | Continuous AF near/far | Focus follows repeated near/far subject changes. | Permanently stuck plane; hunting never settles. | Device test required. |
| F2 | Continuous AF tap | Tap prioritizes the subject, meters exposure, then continuous AF resumes after roughly three seconds. | Permanent lock; no focus response; exposure remains unintentionally locked. | Device test required. |
| F3 | Tap & Lock | Tapped focus plane remains until another tap, Unlock, or mode change. | Silent auto-unlock; exposure locked with focus. | Device test required. |
| F4 | Macro AF | Appears only when Camera2 reports Macro and can lock a close textured subject. | Fake macro chip; no physical effect; endless scan. | Lens matrix required. |
| F5 | Manual slider | Focus plane moves monotonically from infinity/far toward nearest position. | Reversed control; jumps; AF overrides slider. | Device test required. |
| F6 | Manual distance label | Calibrated/approximate lens may show physical distance; uncalibrated lens shows only percentage. | False metre/cm precision. | Metadata matrix required. |
| F7 | Infinity | Lens holds farthest position and does not hunt. | Near focus selected; AF restarts unexpectedly. | Device test required. |
| F8 | Fixed Focus | Only truthful fixed-focus fallback is available on a non-moving lens. | UI claims successful refocus. | Lens matrix required. |
| F9 | Lens switch fallback | Unsupported stored mode changes to the best supported mode and the header updates. | Unsupported request persists; camera session breaks. | All lenses required. |
| F10 | Auto exposure + manual focus | Exposure continues automatically while focus position stays manual. | Applying focus disables AE. | Device test required. |
| F11 | Manual exposure + manual focus | ISO/shutter changes do not reset focus; focus changes do not reset ISO/shutter. | One control clears the other. | Device test required. |
| F12 | Tap in Manual/Infinity | Auto exposure may meter without moving focus; with manual exposure it is explicitly ignored. | Tap changes lens position; misleading success message. | Device test required. |
| F13 | JPEG HDR hold | Selected/current distance remains constant across all JPEG bracket members. | Refocus pulse or breathing between exposure steps. | Device test required. |
| F14 | True RAW HDR hold | Selected/current distance remains constant across all RAW bracket members. | Refocus pulse; RAW edge-size mismatch. | Device test required. |
| F15 | Restore after capture | Selected mode and position return after Standard/HDR/RAW success, failure, or cancellation. | Camera left in AF-off or wrong distance. | Device test required. |
| F16 | Double Exposure | Photographer may deliberately choose different focus planes for source 1 and source 2. | First-frame session lost merely because focus changes. | Device test required. |
| F17 | Persistence | Mode and manual position survive app restart or safely fall back on active lens. | Stale unsupported mode or reset without explanation. | Device test required. |
| F18 | Metadata | Newly saved MediaStore description names the selected focus mode. | Missing/wrong focus identity. | MediaStore inspection required. |

### Focus test procedure

1. Test the main rear lens, every auxiliary rear lens, and the front camera.
2. Use high-contrast textured targets at near, middle, and distant positions.
3. Check both live behaviour and saved still sharpness.
4. Repeat Manual Focus with auto exposure and manual exposure.
5. Repeat Tap & Lock, Manual, and Infinity through Computational HDR and True RAW HDR.
6. Inspect bracket edges for focus breathing, not only simple blur.
7. Confirm unsupported focus modes are disabled rather than merely ineffective.

---

## General release procedure

1. Run every relevant Aerochrome and monochrome scene in Standard, Computational HDR, and True RAW HDR where supported.
2. Run all output, RAW, Ultra HDR, Double Exposure, and focus checks affected by the change.
3. Verify dimensions, latency, memory, focus/exposure restoration, file metadata, and independent decode.
4. Record device, Android version, physical lens, commit/release, and verdict in the Status column.
5. File a dated plan document for every failure rather than tuning from one attractive frame.

There is no camera-capable CI, automated autofocus rig, or automated HDR display. Green JVM/build CI is necessary but not sufficient for release.

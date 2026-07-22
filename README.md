# Spectral Camera

An Android camera app for simulated infrared and Aerochrome-style photography. The live viewfinder and saved image use the same OpenGL ES film engine, while the still pipeline can optionally collect additional scene information before the film model runs.

**Current development version:** 1.19.0 (versionCode 47) · **Status:** active development. The pro-output, JPEG HDR, True RAW HDR, movement-protection, Ultra HDR, and double-exposure paths have been exercised on a Motorola Edge 60 Fusion running Android 16. Focus modes are implemented and build-tested, but still require physical validation on each available lens.

**Latest signed stable release:** [`v1.8.2`](https://github.com/renardoberou/spectral-camera/releases/tag/v1.8.2). Stable releases are signed only by the tag-triggered release workflow using private GitHub Actions secrets.

## Honesty note

**The internal phone camera does not capture a dedicated infrared channel.** Every built-in IR/Aerochrome look remains a visible-RGB simulation using material classification, a synthesized vegetation/NIR proxy, and film-characteristic-curve modelling. HDR can preserve more visible source information for that simulation; it does not make the phone a true infrared camera.

“True RAW HDR” refers to the dynamic-range source pipeline: multiple minimally processed `RAW_SENSOR` Bayer frames are merged before demosaic and colour conversion. It does not mean the phone sensor acquires true infrared wavelengths.

External IR and thermal modes remain UI/framework placeholders. No external sensor path is currently wired or tested.

## Complete still pipeline

```text
Camera + selected focus mode
  ↓
Standard JPEG, bracketed JPEG, or bracketed RAW_SENSOR Bayer
  ↓
Optional alignment + movement-safe reference anchoring
  ↓
Exposure-normalized radiance merge
  ↓
RAW path: demosaic + captured WB gains + captured colour transform
  ↓
Global normalization + selected HDR tone map
  ↓
Synthetic NIR / material classification
  ↓
Rollei / HIE / SFX / Aerochrome film engine
  ↓
Framebuffer-safe Full Resolution / HQ 1080 / Fast 1080 finishing
  ↓
Optional processed-image Ultra HDR gain map
```

## Capture modes

### Standard

One JPEG-derived render source. This is the fastest and most reliable mode for action. On supported cameras it can also save one untouched DNG sidecar.

### Computational HDR

- Captures bracketed JPEG frames around the current metering point, nominally near −2, 0, and +2 EV where the active lens permits.
- In manual exposure mode, holds ISO and brackets shutter time.
- Awaits each CameraX/Camera2 exposure request before triggering the corresponding frame.
- Aligns exposure-invariant luminance thumbnails.
- Converts encoded sRGB into a linear-light estimate.
- Protects moving or uncertain regions with the normal exposure rather than cancelling the HDR capture.
- Normalizes and tone-maps before synthetic-NIR estimation.

This mode is available on more devices than True RAW HDR, but the phone ISP has already applied white balance, denoising, colour conversion, sharpening, compression, and possibly local processing to each source frame.

### True RAW HDR

When the active lens exposes the required Camera2/CameraX capabilities, Spectral Camera can process a real RAW bracket:

- configures `ImageCapture` for in-memory `RAW_SENSOR` output;
- obtains the current metered shutter/ISO from Camera2 metadata when available;
- fixes ISO and brackets shutter time;
- locks AWB during the bracket where supported;
- matches each RAW image to its `TotalCaptureResult` by sensor timestamp;
- copies the packed Bayer plane while respecting row and pixel stride;
- subtracts static or dynamic black levels;
- normalizes against static or dynamic sensor white level;
- aligns in whole 2×2 CFA cells so red, green, and blue mosaic sites never cross;
- removes spatially unsafe auxiliary bracket members;
- merges exposure-normalized radiance in the Bayer mosaic **before demosaic**;
- performs a first-generation bilinear demosaic;
- applies captured white-balance gains and the captured colour transform into linear sRGB;
- normalizes and tone-maps before the unchanged spectral-film engine.

The mode is capability-gated. It requires RAW output, `MANUAL_SENSOR`, a supported Bayer CFA, black/white-level metadata, exposure metadata, white-balance gains, and a colour transform. Unsupported lenses do not expose it as available.

“Save RAW bracket DNGs” optionally writes every captured RAW member using `DngCreator` and the matching capture result. Disabling that option still runs the in-memory RAW merge without retaining the DNG files.

### Double Exposure

Double Exposure is a two-shutter Standard workflow rather than an HDR bracket:

1. Capture frame 1.
2. Use the transparent frame-1 guide to recompose.
3. Capture frame 2.
4. The two balanced half-exposures are combined in linear light.
5. Synthetic NIR, Aerochrome/IR rendering, grain, halation, and export are applied once to the composite.

The pending first frame can be cancelled. Changing lens or output mode clears it. Optional source saving writes both contributing JPEGs separately.

## Focus modes

The Live screen now has a dedicated Focus panel. Modes are exposed only when the active camera reports the required hardware support.

| Mode | Behaviour |
|---|---|
| Continuous AF | Continuously follows focus. Tapping briefly prioritizes one point, then continuous focusing resumes. |
| Tap & Lock | Tap a subject and hold that focus until another tap, Unlock, or a mode change. |
| Macro AF | Runs the camera’s close-range AF mode and holds it. Available only when the lens reports a real macro AF mode. |
| Manual Focus | Direct lens-position control from infinity toward the nearest reported focus position. |
| Infinity | Holds the lens at its farthest focus position for distant landscapes, skies, and architecture. |
| Fixed Focus | Read-only fallback for cameras whose lens cannot move. |

Manual focus uses a non-linear slider with more precision near infinity. When Android reports calibrated or approximate focus-distance metadata, the UI may show an approximate distance in metres or centimetres. On uncalibrated lenses it reports only the normalized position rather than claiming an inaccurate physical distance.

Tap behaviour remains explicit:

- Continuous AF taps focus and meter, then automatically resume continuous AF.
- Tap & Lock and Macro taps lock focus without silently holding the exposure setting.
- Manual, Infinity, and Fixed Focus taps can meter exposure when auto exposure is active, but do not change lens position.
- Manual exposure plus Manual/Infinity focus ignores tap metering because both exposure and focus are already user-controlled.

### Focus during HDR

A multi-frame HDR bracket must not refocus between its source exposures. Before JPEG HDR or True RAW HDR begins, Spectral Camera reads or derives the active focus distance and holds it through the complete bracket when the lens supports direct distance control. The selected user focus/exposure settings are restored afterward.

This reduces focus breathing, edge displacement, and false alignment failures between bracket members. Focus mode itself does not change the Aerochrome or monochrome-IR film rendering.

## HDR tone maps

The Pro imaging screen exposes three global, halo-free mappings before the film engine:

- **Natural** — restrained shadow lift and highlight recovery.
- **Filmic** — deeper toe and a longer shoulder before the selected emulsion curve.
- **Low Contrast** — stronger logarithmic compression for severe backlight or later manual grading.

No local-contrast HDR operator is used. This avoids conventional HDR halos and plastic microcontrast before the film emulation.

## Pro output modes

- **Full Resolution** — uses the largest practical source and produces the largest offscreen film render the active GPU completes safely.
- **HQ 1080** — preserves a high-resolution source through merge and film rendering, then progressively reduces to exact `1920×1080` or `1080×1920`.
- **Fast 1080** — requests a lower-latency 16:9 source near Full HD and normalizes aligned dimensions such as `1920×1088` to exact Full HD.

High-quality JPEG source capture requests quality 100. Fast 1080 intentionally uses the lower-latency CameraX policy and quality 95. Processed JPEG output uses quality 100.

Large still renders use an adaptive offscreen-allocation policy. If a requested full-size framebuffer cannot be created, the app retries the already-captured source at descending aspect-preserving sizes rather than discarding the photograph or recapturing the HDR bracket.

## Ultra HDR display/export

On Android 14 and later, either HDR capture mode can request a backward-compatible Ultra HDR JPEG:

- the processed film image is the SDR base;
- a new gain map is generated **after** the Aerochrome or monochrome transform;
- recovered headroom is gated by final processed luminance so film-intent dark sky, water, and shadow do not become glowing display regions;
- RAW gain-map geometry follows the image rotation, mirroring, and 16:9 crop;
- an incompatible gain map is rejected so the app saves a correct SDR JPEG rather than a damaged Ultra HDR file;
- SDR viewers show the base JPEG;
- compatible HDR displays can show additional highlight headroom;
- the in-app viewer enables HDR window mode only when the decoded bitmap actually contains a gain map.

## Film-look families

Every preset belongs to one structured family. `core/FilmLook.kt` stores stock parameters; `core/gl/SpectralGlPipeline.kt` supplies the shared family renderers.

### Monochrome IR

| Preset | Character |
|---|---|
| Rollei Infrared 400 | Restrained reference IR: fine grain, textured luminous foliage, dense gradated sky, controlled halation. |
| Kodak HIE | Deepest toe, strongest Wood effect and bloom, near-black skies. |
| Ilford SFX 200 | Gentler extended-red response, smoother tonality, minimal halation. |
| Moderate IR (Konica-style) | Balanced middle ground for general use. |
| Fine-Grain Infrared | Clean, print-oriented, mild Wood effect and tight halation. |
| Soft Vintage IR | Lifted blacks, milky highlights, wider glow and coarser grain. |

### Aerochrome / false-colour IR

| Preset | Character |
|---|---|
| Aerochrome Classic | Reference EIR balance: magenta-red foliage and deep cyan-blue sky. |
| Aerochrome Soft | Pastel foliage, gentler contrast, paler sky and minimal glow. |
| Aerochrome Dense | Deeper density, greater saturation headroom and stronger halation. |
| Aerochrome Gold | Orange-filter interpretation with warmer foliage and teal sky. |
| Aerochrome Faded / Vintage | Lifted, desaturated and warm aged-print character. |

No novelty/experimental category is exposed.

## Files and gallery

Files save under `DCIM/SpectralCamera` through MediaStore. Names identify:

- `proc` — processed SDR JPEG;
- `uhdr` — processed Ultra HDR JPEG;
- `orig` — Standard source or JPEG-HDR reference exposure;
- `src01`, `src02` — optional Double Exposure sources;
- `dng`, `dng01`, `dng02`, `dng03` — Standard sidecar or RAW-HDR bracket members;
- `SDR1` — Standard;
- `JHDR2`/`JHDR3` — JPEG Computational HDR;
- `RHDR2`/`RHDR3` — True RAW HDR;
- `DEXP2` — Double Exposure;
- sensor mode, preset, and output mode.

The MediaStore description also records the selected focus mode. DNGs remain outside the in-app JPEG gallery but are available to system and RAW-development applications. The parser remains compatible with previous pro-output and oldest `spectral_raw_...jpg` conventions.

## Validation

- `docs/VALIDATION.md` is the release checklist for film failure scenes, output geometry, HDR motion, RAW metadata, DNG integrity, Ultra HDR, memory, focus modes, and unsupported-lens fallback.
- `docs/PLAN_2026-07-22_focus-modes.md` records the focus architecture, UI rules, HDR interaction, and physical test matrix.
- The other dated plan documents record the output, HDR, RAW, framebuffer, ghosting, movement, and gain-map corrections that led to the current pipeline.

## Known limitations

- RGB-to-NIR estimation remains approximate even when HDR preserves more visible detail.
- JPEG HDR is not sensor-linear because the ISP has already processed its sources.
- True RAW HDR is currently Bayer-only and uses a first-generation bilinear demosaic.
- HDR alignment is global translation rather than dense optical flow. Movement-safe areas may recover less dynamic range.
- Camera vendors may omit or vary required RAW, focus, white-balance, or colour metadata.
- Macro, manual, and infinity focus are hardware-dependent and may be unavailable on auxiliary or front cameras.
- A manual-focus distance is only physically meaningful when the lens reports calibrated or approximate distance metadata.
- Full Resolution may adaptively reduce render dimensions when one large RGBA framebuffer cannot be allocated.
- Gallery import is single-frame bitmap processing and cannot reconstruct a missing bracket.
- Ultra HDR is reconstructed display intent after a false-colour transform, not an untouched physical scene-radiance field.

## Test status

GitHub Actions runs:

```bash
./gradlew testDebugUnitTest assembleDebug assembleRelease
```

Focused JVM coverage includes:

- gallery permission policy;
- exact Full HD crop/orientation geometry;
- automatic and manual HDR bracket planning;
- sRGB/linear transfer functions;
- tone-map bounds, monotonicity, and middle-grey behaviour;
- deghost weighting and translation recovery;
- all four Bayer CFA layouts;
- RAW black/white normalization and exposure-product behaviour;
- RAW gain-field rotation/mirroring/cropping;
- Double Exposure blend math;
- normalized manual-focus position to Camera2 diopters;
- infinity and nearest focus endpoints;
- calibrated versus uncalibrated focus-distance labels.

There is no camera-capable CI, automated DNG integrity suite, autofocus-performance test, or automated HDR-display verification. A green build is necessary but not sufficient for release.

## Requirements

- Android 8.0+ (`minSdk 26`), compiled against SDK 36 and targeting SDK 35;
- Camera permission;
- active-lens RAW and manual-sensor capability for True RAW HDR;
- active-lens focus capability for autofocus/manual focus modes;
- Android 14+ for gain-map Ultra HDR export/display;
- Android media permissions appropriate to the OS version;
- no internet permission.

## Build and signing

Without release-signing environment variables, `assembleRelease` intentionally produces an unsigned APK. Partial signing configuration is rejected. Stable release publication runs only from the tag-triggered workflow described in `docs/RELEASE.md`.

The app transmits no images or telemetry. Capture preparation, RAW/JPEG merge, focus control, film rendering, and export run on-device.

## Architecture

- `core/FilmLook.kt` — monochrome-IR and Aerochrome stock parameter tables.
- `core/gl/SpectralGlPipeline.kt` — live and still synthetic-NIR/film renderer.
- `core/focus/FocusMath.kt` — normalized focus-position mapping and conservative distance labels.
- `core/hdr/HdrMath.kt` / `HdrPipeline.kt` — JPEG bracket planning, alignment, merge, and tone mapping.
- `core/hdr/RawHdrMath.kt` / `RawHdrPipeline.kt` — CFA-safe sensor-linear RAW merge before demosaic.
- `core/hdr/UltraHdrExporter.kt` — post-film gain-map generation.
- `core/export/OutputPipeline.kt` — Full/HQ/Fast geometry and finishing.
- `core/capture/DoubleExposurePipeline.kt` — balanced two-frame source composite.
- `core/camera/CameraController.kt` — CameraX/Camera2 sessions, focus modes, exposure brackets, RAW_SENSOR and DNG capture.
- `core/media/MediaRepository.kt` — typed SDR/Ultra-HDR/DNG storage and backward-compatible parsing.
- `core/state/SpectralViewModel.kt` — persisted controls, capture orchestration, and adaptive GPU rendering.
- `ui/screens/LiveCameraScreen.kt` — live capture, exposure, focus, preset, and manual-adjustment controls.
- `ui/screens/ProOutputScreen.kt` — capture, tone-map, source-file, and output controls.
- `ui/screens/GalleryScreen.kt` — SDR-compatible gallery and dynamic gain-map HDR display.

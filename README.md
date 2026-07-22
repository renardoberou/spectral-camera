# Spectral Camera

An Android camera app for simulated infrared and Aerochrome-style photography. The live viewfinder and saved file use the same OpenGL ES film engine, while still capture can optionally collect more scene information before the film model runs.

**Current development version:** 1.17.0 (versionCode 45) · **Status:** active development. The film engine has been manually exercised on a Motorola Edge 60 Fusion running Android 16. The new framebuffer fallback, True RAW HDR, Ultra HDR JPEG round trip, lens capability gates, latency, memory, and device integration still require the physical validation listed below.

**Latest signed stable release:** [`v1.8.2`](https://github.com/renardoberou/spectral-camera/releases/tag/v1.8.2). Stable releases are signed only by the tag-triggered release workflow using private GitHub Actions secrets.

## Honesty note

**The internal phone camera does not capture a dedicated infrared channel.** Every built-in IR/Aerochrome look remains a simulation using visible colour, material classification, a synthesized vegetation/NIR proxy, and film-characteristic-curve modelling. HDR can preserve more visible source information for that simulation; it does not make the phone a true infrared camera.

“True RAW HDR” refers to the dynamic-range source pipeline: multiple minimally processed `RAW_SENSOR` Bayer frames are merged before demosaic and colour conversion. It does not mean the phone sensor acquires true infrared wavelengths.

External IR and thermal modes remain UI/framework placeholders. No external sensor path is currently wired or tested.

## Complete still pipeline

```text
Camera
  ↓
Standard JPEG, bracketed JPEG, or bracketed RAW_SENSOR Bayer
  ↓
Optional translation alignment + reference-biased deghosting
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

One JPEG-derived render source. This is the fastest and most reliable mode for movement. On supported cameras it can also save one untouched DNG sidecar.

### Computational HDR

- Captures bracketed JPEG frames around the current metering point, nominally near −2, 0, and +2 EV where the active lens permits.
- In manual mode, holds ISO and brackets shutter time.
- Awaits each CameraX/Camera2 exposure request before triggering the corresponding frame.
- Aligns exposure-normalized log-luminance thumbnails.
- Converts encoded sRGB into a linear-light estimate.
- Rejects clipped exposures and biases moving/disagreeing regions toward the reference JPEG.
- Normalizes and tone-maps before synthetic-NIR estimation.

This mode is available on more devices than True RAW HDR, but the phone ISP has already applied white balance, denoising, colour conversion, sharpening, compression, and possibly local processing to each source frame.

### True RAW HDR

When the active lens exposes the required Camera2/CameraX capabilities, Spectral Camera can process a real RAW bracket:

- configures `ImageCapture` for in-memory `RAW_SENSOR` output;
- obtains the current metered shutter/ISO from Camera2 metadata when available;
- fixes ISO and brackets shutter time;
- locks AWB during the bracket where supported;
- matches each RAW image to its `TotalCaptureResult` by sensor timestamp;
- copies the packed 16-bit Bayer plane while respecting row and pixel stride;
- subtracts static or dynamic black levels;
- normalizes against static or dynamic sensor white level;
- aligns in whole 2×2 CFA cells so red, green, and blue mosaic sites never cross;
- merges exposure-normalized radiance in the Bayer mosaic **before demosaic**;
- performs a transparent first-generation bilinear demosaic;
- applies the captured `RggbChannelVector` gains and `COLOR_CORRECTION_TRANSFORM` into linear sRGB;
- normalizes and tone-maps before the unchanged spectral-film engine.

The mode is capability-gated. It requires RAW output, `MANUAL_SENSOR`, a supported Bayer CFA, black/white-level metadata, exposure metadata, WB gains, and a colour transform. Unsupported lenses must not expose it as available.

“Save RAW bracket DNGs” optionally writes every captured RAW member using `DngCreator` and the matching capture result. Disabling that option still runs the in-memory RAW merge without keeping three sidecars.

## HDR tone maps

The Pro imaging screen exposes three global, halo-free mappings before the film engine:

- **Natural** — restrained shadow lift and highlight recovery.
- **Filmic** — deeper toe and a longer shoulder before the selected emulsion curve.
- **Low Contrast** — stronger logarithmic compression for severe backlight or later manual grading.

No local-contrast HDR operator is used. This avoids conventional HDR halos and plastic microcontrast before the film emulation.

## The Full/HQ capture failure and its fix

Large HDR captures previously completed their camera bracket but could then fail with:

```text
Capture framebuffer incomplete: 0
```

The failure was in the offscreen film renderer, not in exposure capture. A large still requires a source RGBA texture, target RGBA texture/framebuffer, direct readback buffer, and output bitmap at the same time. `GL_MAX_TEXTURE_SIZE` only proves a dimension is legal; it does not prove that the combined allocation fits mobile GPU and process memory.

The still pipeline now retries only framebuffer/texture/out-of-memory allocation failures at descending aspect-preserving render sizes. It does not recapture the bracket. Mode-specific safety tiers preserve the largest result the active GPU can actually complete:

- Full Resolution: up to 4096, then 3456, 3072, 2560, 2304, 2048, or 1920 long edge as necessary;
- HQ 1080: high-resolution merge/render with 3072 → 1920 fallback, followed by exact Full HD finishing;
- Fast 1080: 1920 → 1280 safety range.

Non-allocation camera or shader errors are still surfaced rather than silently hidden. A future tiled renderer can preserve still larger exports without one full-frame RGBA framebuffer.

## Pro output modes

- **Full Resolution** — uses the largest practical source and produces the largest offscreen film render the active GPU completes safely.
- **HQ 1080** — preserves a high-resolution source through merge and film rendering, then progressively reduces to exact `1920×1080` or `1080×1920`.
- **Fast 1080** — requests a lower-latency 16:9 source near Full HD and normalizes aligned dimensions such as `1920×1088` to exact Full HD.

High-quality JPEG source capture requests quality 100. Fast 1080 intentionally uses the lower-latency CameraX policy and quality 95. Processed JPEG output uses quality 100.

## Ultra HDR display/export

On Android 14 and later, either HDR capture mode can request a backward-compatible Ultra HDR JPEG:

- the processed film image is the SDR base;
- a new gain map is generated **after** the Aerochrome or monochrome transform;
- recovered headroom is gated by final processed luminance so film-intent dark sky, water, and shadow do not become glowing display regions;
- SDR viewers show the base JPEG;
- compatible HDR displays can show additional highlight headroom;
- the in-app viewer enables HDR window mode only when the decoded bitmap actually contains a gain map.

Ultra HDR encoding, portrait/landscape orientation, independent decoding, and HDR-panel behavior remain physical-device release gates.

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
- `dng`, `dng01`, `dng02`, `dng03` — Standard sidecar or RAW-HDR bracket members;
- `SDR1` — Standard;
- `JHDR2`/`JHDR3` — JPEG Computational HDR;
- `RHDR2`/`RHDR3` — True RAW HDR;
- sensor mode, preset, and output mode.

DNGs remain outside the in-app JPEG gallery but are available to system and RAW-development applications. The gallery parser remains compatible with previous pro-output and oldest `spectral_raw_...jpg` conventions.

## Validation

- `docs/VALIDATION.md` contains the release checklist for film failure scenes, output geometry, bracket timing, exposure restoration, alignment, deghosting, RAW metadata, DNG integrity, Ultra HDR, memory, and unsupported-lens fallback.
- `docs/PLAN_2026-07-22_true-raw-hdr-and-framebuffer-fix.md` records the observed bug, the solved root cause, the True RAW HDR architecture, implementation status, and release gates.

## Known limitations

- RGB-to-NIR estimation remains approximate even when HDR preserves more visible detail.
- JPEG HDR is not sensor-linear because the ISP has already processed its sources.
- True RAW HDR is currently Bayer-only and uses a first-generation bilinear demosaic.
- RAW alignment is translation-only and constrained to CFA-cell shifts. Rotation, perspective, parallax, leaves, people, and moving water can reduce recovered range or fail validation.
- Camera vendors may omit or vary required WB/colour metadata.
- Computational HDR and True RAW HDR are slower than Standard and should not be the action default.
- Full Resolution may adaptively reduce render dimensions when one large RGBA framebuffer cannot be allocated.
- Gallery import is single-frame bitmap processing and cannot reconstruct a missing bracket.
- Ultra HDR is reconstructed display intent after a false-colour transform, not an untouched physical scene-radiance field.
- All new camera and display behavior still requires physical-device validation.

## Test status

GitHub Actions runs:

```bash
./gradlew testDebugUnitTest assembleDebug assembleRelease
```

Focused JVM coverage includes:

- gallery permission policy;
- exact Full HD crop/orientation geometry;
- automatic and manual bracket planning;
- sRGB/linear transfer functions;
- tone-map bounds, monotonicity, and middle-grey behavior;
- deghost weighting and translation recovery;
- all four Bayer CFA layouts;
- RAW black/white normalization;
- RAW clipping weights;
- RAW exposure-product behavior;
- sensor RGB colour-transform math.

There is no camera-capable CI, automated DNG integrity suite, or automated HDR-display verification. A green build is necessary but not sufficient for release.

## Requirements

- Android 8.0+ (`minSdk 26`), compiled against SDK 36 and targeting SDK 35;
- Camera permission;
- active-lens RAW and manual-sensor capability for True RAW HDR;
- Android 14+ for gain-map Ultra HDR export/display;
- Android media permissions appropriate to the OS version;
- no internet permission.

## Build and signing

Without release-signing environment variables, `assembleRelease` intentionally produces an unsigned APK. Partial signing configuration is rejected. Stable release publication runs only from the tag-triggered workflow described in `docs/RELEASE.md`.

The app transmits no images or telemetry. Capture preparation, RAW/JPEG merge, film rendering, and export run on-device.

## Architecture

- `core/FilmLook.kt` — monochrome-IR and Aerochrome stock parameter tables.
- `core/gl/SpectralGlPipeline.kt` — live and still synthetic-NIR/film renderer.
- `core/hdr/HdrMath.kt` / `HdrPipeline.kt` — JPEG bracket planning, alignment, merge, and tone mapping.
- `core/hdr/RawHdrMath.kt` / `RawHdrPipeline.kt` — CFA-safe sensor-linear RAW merge before demosaic.
- `core/hdr/UltraHdrExporter.kt` — post-film gain-map generation.
- `core/export/OutputPipeline.kt` — Full/HQ/Fast geometry and finishing.
- `core/camera/CameraController.kt` — CameraX/Camera2 sessions, exposure brackets, RAW_SENSOR and DNG capture.
- `core/media/MediaRepository.kt` — typed SDR/Ultra-HDR/DNG storage and backward-compatible parsing.
- `core/state/SpectralViewModel.kt` — end-to-end capture orchestration and adaptive GPU render fallback.
- `ui/screens/ProOutputScreen.kt` — capture, tone-map, source-file, and output controls.
- `ui/screens/GalleryScreen.kt` — SDR-compatible gallery and dynamic gain-map HDR display.

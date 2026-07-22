# Spectral Camera

An Android camera app for simulated infrared and Aerochrome-style photography. The live viewfinder and saved file use the same OpenGL ES film engine, while the still pipeline can optionally collect and merge additional scene information before that engine runs.

**Current development version:** 1.17.0 (versionCode 45) · **Status:** active development. The film engine has been manually exercised on a Motorola Edge 60 Fusion running Android 16. Computational HDR, Ultra HDR JPEG encoding/display, RAW capture, stream negotiation, and device integration still require the physical-device validation listed below.

**Latest signed stable release:** [`v1.8.2`](https://github.com/renardoberou/spectral-camera/releases/tag/v1.8.2). Stable releases are signed only by the tag-triggered release workflow using private GitHub Actions secrets.

## Honesty note

**The internal phone camera does not capture a dedicated infrared channel.** Every built-in look is a visible-RGB simulation using chromaticity analysis, a synthesized vegetation/NIR proxy, and film-characteristic-curve modelling. Computational HDR improves the visible source information available to that simulation; it does not turn the phone into a true infrared camera.

External IR and thermal modes remain UI/framework placeholders. No external sensor capture path is wired or tested.

## Imaging pipeline

The optional still pipeline is:

```text
Camera
  ↓
Optional three-frame exposure bracket
  ↓
Translation alignment + reference-biased deghosting
  ↓
Exposure-normalized linear-light radiance merge
  ↓
Global normalization + selected HDR tone map
  ↓
Synthetic NIR / material classification
  ↓
Rollei / HIE / SFX / Aerochrome film engine
  ↓
Full Resolution / HQ 1080 / Fast 1080 finishing
  ↓
Optional processed-image Ultra HDR gain map
```

### Standard and Computational HDR capture

- **Standard** records one JPEG-derived render source. It is fastest and remains the preferred mode for moving subjects.
- **Computational HDR** records up to three real exposures around the current metering point, nominally near −2, 0, and +2 EV where the active camera range permits.
- In manual mode, supported devices bracket shutter time while holding ISO fixed.
- CameraX exposure futures are awaited before each shutter event, so the requested compensation or shutter value is active before capture.
- Frames are aligned using exposure-normalized logarithmic luminance thumbnails and a translation estimate.
- The merger converts sRGB to linear light, normalizes each exposure to radiance, rejects clipped extremes, and biases disagreement/motion toward the normal exposure.
- The result is normalized and tone-mapped before synthetic-NIR estimation. HDR is therefore source preparation, not a post-film “HDR effect.”

Computational HDR uses a practical high-quality binned stream rather than requesting three 50 MP bitmaps. This limits memory pressure while retaining more than enough detail for HQ 1080 and typical mobile output. The exact stream remains device-dependent.

### HDR tone maps

The Pro imaging screen exposes three global, halo-free tone maps for the merged source:

- **Natural** — restrained shadow lift and highlight recovery.
- **Filmic** — deeper toe and a longer shoulder before the selected emulsion curve.
- **Low Contrast** — stronger logarithmic compression for severe backlight or later manual grading.

These are global luminance mappings. The app deliberately does not use local contrast HDR operators that create halos or plastic microcontrast before the film simulation.

### Ultra HDR display and export

On Android 14 and later, a successful computational-HDR capture can be exported as a backward-compatible Ultra HDR JPEG:

- the processed film image is the SDR base image;
- Spectral Camera builds a **new** gain map after the Aerochrome/IR transform rather than reusing invalid pre-film HDR metadata;
- gain is gated by final processed luminance so deliberately dark EIR skies, IR water, and dense film shadows do not become luminous HDR patches;
- SDR-only software displays the normal JPEG base;
- gain-map-aware HDR displays can render additional highlight headroom;
- the in-app detail viewer changes the window to HDR mode only when the decoded bitmap actually reports a gain map.

Ultra HDR encoding and display must still be verified on physical Android 14+ devices and independent JPEG/R-aware viewers before release.

## Pro output modes

- **Full Resolution** — Standard capture requests the camera's largest practical JPEG source. Computational HDR uses a memory-bounded high-quality bracket stream.
- **HQ 1080** — preserves the high-resolution source through HDR merge and film rendering, then progressively reduces to exact `1920×1080` or `1080×1920`.
- **Fast 1080** — requests a lower-latency 16:9 source near Full HD and normalizes aligned dimensions such as `1920×1088` to exact Full HD before film rendering.

High-quality JPEG source capture requests quality 100. Fast 1080 intentionally uses the lower-latency CameraX policy and quality 95. Processed JPEG output uses quality 100.

## RAW sidecars

On cameras that report simultaneous RAW+JPEG support, Standard capture can save an untouched DNG beside the processed JPEG. The companion JPEG still feeds the current film renderer.

RAW sidecar and three-frame Computational HDR are mutually exclusive in this development cycle. The app does **not** yet demosaic, profile, merge, or render a RAW burst internally. A future scene-linear RAW pipeline would require three DNG captures plus camera-specific black-level, white-balance, color-matrix, demosaic, alignment, and merge handling.

## Film-look families

Every preset belongs to one of two structured families. `core/FilmLook.kt` stores the stock parameters; `core/gl/SpectralGlPipeline.kt` supplies one shared renderer per family.

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

No experimental or novelty preset category is exposed.

## Manual and capture controls

- hardware exposure compensation in photographic stops;
- manual ISO and shutter where Camera2 `MANUAL_SENSOR` is supported;
- tap focus, torch and front/rear switching;
- digital exposure, Blacks, Whites, Contrast, Saturation, Hue, Grain, Bloom, Sharpness, channel controls, sky suppression and foliage lift;
- stock-specific always-on baseline grain, with the Grain control adding to it;
- output mode, HDR capture, HDR tone map, Ultra HDR export, original/reference JPEG and RAW-sidecar controls in the separate Pro imaging screen.

The live viewfinder remains single-frame for responsiveness. Computational HDR is a still-capture operation, so its recovered range is visible after capture rather than as a real-time fused preview.

## File identity and gallery

Files save under `DCIM/SpectralCamera` through MediaStore. Names identify:

- `proc` — processed SDR JPEG;
- `uhdr` — processed Ultra HDR JPEG/R;
- `orig` — untouched Standard source or the reference exposure from an HDR bracket;
- `dng` — untouched RAW sidecar;
- sensor mode, film preset, output mode and `HDR3`/`SDR1` capture strategy.

The parser remains compatible with the previous pro-output naming convention and the oldest `spectral_raw_...jpg` original-JPEG convention. DNG sidecars stay out of the in-app JPEG gallery but remain available to system and RAW-development applications.

## Validation

`docs/VALIDATION.md` is the release checklist for:

- Aerochrome and monochrome-IR failure scenes;
- Full/HQ/Fast geometry and visual parity;
- RAW capability and fallback;
- exposure-bracket timing and restoration;
- static alignment, handheld translation and moving-subject deghosting;
- tone-map behavior before both film families;
- Android 14+ Ultra HDR encode/decode/display and SDR fallback.

## Known limitations

- RGB-to-NIR estimation remains approximate even when HDR improves source range.
- HDR currently merges bracketed display-referred JPEGs, not sensor-linear RAW frames.
- Alignment is translation-only; rotation, perspective change, parallax, leaves in wind, people and water motion can force local fallback toward the reference exposure.
- Computational HDR is slower than Standard capture and should not be treated as the default for action.
- Ultra HDR gain is reconstructed from pre-film scene headroom and post-film luminance. It is a conservative display enhancement, not a retained physical radiance field after arbitrary false-colour transformation.
- Gallery import is single-frame bitmap processing; it cannot synthesize a computational bracket.
- Full-resolution output is constrained by the camera HAL and `GL_MAX_TEXTURE_SIZE`.
- Camera orientation, bracket timing, memory pressure, RAW/DNG integrity, Ultra HDR JPEG round-trip and HDR display all require physical-device verification.

## Test status

GitHub Actions runs:

```bash
./gradlew testDebugUnitTest assembleDebug assembleRelease
```

Focused JVM coverage includes:

- gallery permission policy;
- exact Full HD center-crop/orientation geometry;
- automatic and manual HDR bracket planning;
- sRGB/linear transfer functions;
- tone-map bounds and monotonicity;
- reference-biased deghost weights;
- translation-alignment recovery.

There is no camera-capable CI, instrumentation image suite, or automated HDR-display verification. Physical-device checks remain mandatory.

## Requirements

- Android 8.0+ (`minSdk 26`), compiled against SDK 36 and targeting SDK 35;
- Camera permission;
- Android 14+ for gain-map Ultra HDR export/display;
- `READ_MEDIA_IMAGES` on Android 13+ for full library access;
- `READ_MEDIA_VISUAL_USER_SELECTED` handling on Android 14+;
- `READ_EXTERNAL_STORAGE` through Android 12L where legacy full-library access is requested;
- no internet permission.

## Build and signing

Without release-signing environment variables, `assembleRelease` intentionally produces `app-release-unsigned.apk`. Supplying only part of the signing environment is a configuration error. Stable release publication runs only from the tag-triggered release workflow described in `docs/RELEASE.md`.

The app transmits no images or telemetry. All capture preparation, HDR merge, film rendering and export run on-device.

## Architecture

- `core/FilmLook.kt` — structured monochrome-IR and Aerochrome stock parameters.
- `core/gl/SpectralGlPipeline.kt` — live and still synthetic-NIR/film renderer.
- `core/hdr/HdrMath.kt` — bracket planning, transfer functions, tone maps and translation estimator.
- `core/hdr/HdrPipeline.kt` — alignment, linear-light merge, normalization, deghosting and headroom field.
- `core/hdr/UltraHdrExporter.kt` — post-film gain-map generation and attachment.
- `core/export/OutputPipeline.kt` — Full/HQ/Fast geometry and finishing.
- `core/camera/CameraController.kt` — CameraX session, real exposure brackets, JPEG and RAW+JPEG capture.
- `core/media/MediaRepository.kt` — typed SDR/Ultra-HDR/DNG storage and backward-compatible gallery parsing.
- `core/state/SpectralViewModel.kt` — end-to-end capture orchestration.
- `ui/screens/ProOutputScreen.kt` — capture, tone-map and output controls.
- `ui/screens/GalleryScreen.kt` — SDR-compatible gallery plus dynamic gain-map HDR display.

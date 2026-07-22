# Spectral Camera

An Android camera app that emulates infrared and false-colour spectral film looks in real time. The live preview and saved photo use the same OpenGL ES film engine, so framing and look intent remain aligned from viewfinder to file.

**Current development version:** 1.16.0 (versionCode 44) · **Status:** actively developed and manually verified on a Motorola Edge 60 Fusion running Android 16. Focused JVM tests cover gallery permission policy and Full HD output geometry; camera output, RAW capture, and device integration still require physical-device testing.

**Latest signed stable release:** [`v1.8.2`](https://github.com/renardoberou/spectral-camera/releases/tag/v1.8.2), with APK, AAB, and SHA-256 checksums. Stable releases are signed only by the tag-triggered release workflow using private GitHub Actions secrets.

## Honesty note — read this first

**The phone's camera sensor has no infrared sensitivity.** Every filter in this app is a *simulated* spectral/IR look, computed from the visible-light RGB image using colour-science heuristics (chromaticity analysis, a synthesized vegetation/NIR proxy, and film-characteristic-curve modelling). It is not, and cannot be, true infrared photography without external hardware.

External IR/thermal hardware integration exists only as a UI framework (`SensorMode.EXTERNAL_IR`, `SensorMode.THERMAL` in `core/Models.kt`) — **no external sensor is actually wired up or tested.** If you connect real IR/thermal hardware, expect to write the capture path yourself; nothing beyond the mode enum and its label exists today. The Hardware Test screen tests for the colour signature of a near-IR LED visible through the phone camera, not true thermal/IR capture.

## What's implemented

### Rendering pipeline

- Full OpenGL ES 2.0 fragment-shader pipeline (`core/gl/SpectralGlPipeline.kt`): live preview renders at camera-preview resolution with zero per-frame CPU allocation; still captures run the same film model over an offscreen framebuffer.
- Capture requests the sensor's highest available JPEG resolution for Full Resolution and HQ 1080, up to 8160×6144 where the camera HAL exposes it. CameraX falls back to the nearest supported stream.
- The new **Pro output** screen exposes three explicit policies:
  - **Full Resolution** — maximum-quality JPEG source and the largest processed result allowed by the active camera stream and GPU texture limit.
  - **HQ 1080** — high-resolution source, centered 16:9 crop, full film render, then progressive high-quality reduction to exact 1920×1080 or 1080×1920.
  - **Fast 1080** — lower-latency 16:9 source near Full HD, normalized to exact output dimensions even when the HAL exposes a mod-16 size such as 1920×1088.
- Full Resolution and HQ 1080 request JPEG quality 100; Fast 1080 requests quality 95 for lower latency. Processed JPEG exports are written at quality 100.
- Optional **RAW DNG sidecar** capture is capability-gated through CameraX. On supported cameras the app captures DNG+JPEG simultaneously, saves the untouched DNG, and uses the companion JPEG for the current film render.
- RAW support in this version is a sidecar workflow, **not an in-app RAW developer**. The processed Aerochrome/IR JPEG is still derived from a display-referred JPEG bitmap so preview and processed output retain the same look intent.

### Spectral presets: two structured film-look families

Spectral Camera is a dedicated film-emulation tool: every preset belongs to one of two flagship families. Each family shares one physically motivated rendering engine (`monoLook()` / `aeroLook()` in `SpectralGlPipeline.kt`); every family member is a data-table entry in `core/FilmLook.kt` (`FilmLookLibrary`) that reparameterizes tone curve, synthetic-NIR/Wood-effect strength, sky response, water floor, halation, grain, and acutance. Adding a stock is a table entry, not another shader implementation. See `docs/VALIDATION.md` for named failure scenes.

**Monochrome IR family** (`monoLook()`, driven by `MonoIRLook`):

| Preset | Character |
|---|---|
| Rollei Infrared 400 | Reference restrained IR: textured glowing foliage, dense gradated skies, fine grain, controlled anti-halation glow. |
| Kodak HIE | No anti-halation backing: deepest toe, hardest drama, near-black skies, strongest bloom. |
| Ilford SFX 200 | Gentler extended-red response, finer tonality, minimal halation. |
| Moderate IR (Konica-style) | Balanced middle ground between restrained and dramatic; broadly usable default. |
| Fine-Grain Infrared | Neutral, print-oriented: finest grain, mildest Wood effect, tightest halation. |
| Soft Vintage IR | Romantic low-contrast print look: milky highlights, dreamy wide halation, coarser grain, lifted blacks. |

**Aerochrome family** (`aeroLook()`, driven by `AerochromeLook`, all sharing the same EIR colour model):

| Preset | Character |
|---|---|
| Aerochrome Classic | Reference EIR grade: magenta-red foliage, deep cyan sky, filmic false-colour balance. |
| Aerochrome Soft | Gentler contrast, pastel foliage magenta, paler sky, minimal glow. |
| Aerochrome Dense | Punchier contrast, deeper cyan sky, more saturation headroom, dramatic halation. |
| Aerochrome Gold (orange filter) | EIR with orange filter: golden foliage, teal sky. |
| Aerochrome Faded / Vintage | Desaturated, lifted blacks, warm cast, hazy pale sky — an aged-print character. |

There is no experimental or novelty category; every preset is a calibrated member of one of these two film families.

### Manual and output controls

The live screen uses stepped photographic controls rather than unbounded numeric sliders:

- exposure compensation in real stops, converted through the camera's reported EV step;
- full-manual ISO and shutter where the device exposes Camera2 `MANUAL_SENSOR` support;
- current aperture and exposure state where available;
- digital exposure, Blacks/Whites, Contrast, Saturation, Hue, Grain, Bloom, Sharpness, channel weighting/swap, sky suppression, and foliage lift;
- each stock carries subtle always-on baseline grain matched to its personality; the Grain control adds to that baseline;
- output mode, original-JPEG saving, and RAW-DNG sidecar controls live on the separate Pro output screen;
- reset to calibrated film defaults.

### Capture, gallery, and hardware test

- Tap-to-focus, torch, and front/rear camera switching.
- Optional untouched original JPEG saved alongside the processed result.
- Optional untouched DNG sidecar where simultaneous RAW+JPEG is supported.
- Filenames distinguish processed JPEG (`proc`), original JPEG (`orig`), DNG (`dng`), preset, sensor mode, and output mode.
- Captures save to `DCIM/SpectralCamera` through MediaStore.
- The in-app gallery intentionally lists processed/original JPEGs only; DNG sidecars remain available to system gallery/file and RAW-development applications.
- Gallery distinguishes full, selected-photo, current-install-only, and denied library access.
- Android 14+ selected-photo access is rechecked whenever the gallery resumes.
- Historical captures using the older filename convention remain readable.
- Hardware Test looks for the colour signature of a near-IR LED visible through the phone camera.

## Validation

Look tuning is judged against a named set of failure scenes rather than attractive foliage demos alone. `docs/VALIDATION.md` covers skies, haze, neutral walls, shadow foliage, skin, red objects, water/glass, masonry, and stock separation. It now also includes output-pipeline checks for exact 16:9 geometry, Full Resolution/HQ/Fast parity, RAW fallback, and preview/export intent.

## Known limitations

- RGB-to-NIR estimation is fundamentally approximate. Materials whose visible colour does not predict their actual NIR reflectance can render incorrectly.
- RAW DNG is currently an untouched sidecar only. The processed result does not yet use sensor-linear RAW data.
- Gallery import uses Android `ImageDecoder`/bitmap decoding; it is not a dedicated DNG demosaic and camera-profile pipeline.
- Full-resolution processing is limited by both the camera HAL stream and `GL_MAX_TEXTURE_SIZE`; very large sources may be reduced before the shader render.
- Front-camera resolution is usually lower than rear-camera resolution.
- Full-manual ISO and shutter require Camera2 `MANUAL_SENSOR` support.
- Simultaneous RAW+JPEG support varies by active camera and may be unavailable even on a phone whose primary rear camera supports RAW.
- Print-quality claims are based on file dimensions and JPEG settings, not ICC-profiled print calibration.
- Camera orientation, stream negotiation, RAW capture, MediaStore DNG behavior, and shader output still require physical-device verification.

## Test status

GitHub Actions runs `testDebugUnitTest`, builds an ephemeral debug APK, and builds an unsigned release APK on pull requests and pushes to `main`.

Current focused JVM coverage includes:

- Android-version-specific gallery permission decisions;
- exact Full HD orientation and center-crop geometry, including 1920×1088 → 1920×1080 normalization.

There is not yet a full Robolectric, instrumentation, or screenshot-test suite. Physical-device checks remain mandatory for:

- front/rear preview and saved-image orientation;
- Full Resolution, HQ 1080, and Fast 1080 stream negotiation;
- RAW capability reporting, dual DNG+JPEG capture, and JPEG fallback;
- CameraX manual controls;
- Android 14+ system photo-selection UI;
- real MediaStore migration behavior after reinstalling the application.

## Requirements

- Android 8.0+ (`minSdk 26`), compiled against SDK 35.
- Camera permission.
- `READ_MEDIA_IMAGES` on Android 13+ for full image-library access.
- `READ_MEDIA_VISUAL_USER_SELECTED` handling on Android 14+ for selected-photo access.
- `READ_EXTERNAL_STORAGE` through Android 12L where full legacy library access is requested.
- No internet permission is requested or used by the app itself.

## Build

From the project root:

```bash
./gradlew testDebugUnitTest assembleDebug assembleRelease
```

Without release-signing environment variables, `assembleRelease` intentionally produces `app-release-unsigned.apk`. Supplying only some signing variables is a configuration error; all four are required together:

- `KEYSTORE_FILE`
- `KEYSTORE_PASS`
- `KEY_ALIAS`
- `KEY_PASS`

Ordinary GitHub Actions CI never receives the stable release key. Its debug APK uses the runner's ephemeral debug identity and must not be presented as an update-compatible public release.

Stable APK/AAB publication happens only through `.github/workflows/release.yml` after a `vX.Y.Z` tag is pushed. The workflow verifies the configured signing certificate, package ID, APK signature, and checksums before publishing. See `docs/RELEASE.md`.

If building in Termux, keep any `android.aapt2FromMavenOverride` setting in the device's `~/.gradle/gradle.properties`; do not commit it to the repository.

## Signing migration note

A public throwaway keystore was briefly committed and used by development build 1.8.6. That key is retired and must never be used for stable distribution.

- Users running the official `v1.8.2` stable APK should be able to update in place only to another APK signed with the same private stable key.
- Users who installed a CI or public-test APK signed by another key may need to uninstall that APK once before installing the next stable release.
- Uninstalling the app does not delete images stored in `DCIM/SpectralCamera`; grant photo access in the repaired app to display captures from previous installations.

## Privacy

- The app does not request network access and transmits no images or telemetry.
- Media permissions are used only to display saved captures in the in-app gallery.
- All spectral processing runs on-device through the GPU shader.

## Architecture

- `core/FilmLook.kt` — structured `MonoIRLook` and `AerochromeLook` parameter tables.
- `core/gl/SpectralGlPipeline.kt` — live and still OpenGL rendering.
- `core/export/OutputPipeline.kt` — unit-tested 16:9 crop, Fast/HQ Full HD preparation, and export finishing.
- `core/camera/CameraController.kt` — CameraX session, JPEG or RAW+JPEG capture, focus, and manual exposure.
- `core/camera/CapturedFrame.kt` — JPEG render source plus optional temporary DNG sidecar.
- `core/data/CameraSettingsRepository.kt` — persisted look and output preferences.
- `core/media/MediaRepository.kt` — typed JPEG/DNG MediaStore save and backward-compatible gallery query.
- `core/state/SpectralViewModel.kt` — capture, import, output orchestration, and gallery UI state.
- `core/media/GalleryPermissionPolicy.kt` — testable Android-version permission rules.
- `core/hardware/HardwareTestAnalyzer.kt` — near-IR LED signature detector.
- `ui/screens/ProOutputScreen.kt` — output mode and source-file controls.
- `ui/` — remaining Jetpack Compose screens and navigation.

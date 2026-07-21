# Spectral Camera

An Android camera app that emulates infrared and false-colour spectral film looks in real time. The live preview and the saved photo are rendered by the *same* OpenGL ES fragment shader, so what you see through the viewfinder is what you get in the file.

**Current development version:** 1.15.0 (versionCode 43) · **Status:** actively developed and manually verified on a Motorola Edge 60 Fusion running Android 16. Focused JVM tests cover gallery permission policy; camera output and device integration still require physical-device testing.

**Latest signed stable release:** [`v1.8.2`](https://github.com/renardoberou/spectral-camera/releases/tag/v1.8.2), with APK, AAB, and SHA-256 checksums. Stable releases are signed only by the tag-triggered release workflow using private GitHub Actions secrets.

## Honesty note — read this first

**The phone's camera sensor has no infrared sensitivity.** Every filter in this app is a *simulated* spectral/IR look, computed from the visible-light RGB image using colour-science heuristics (chromaticity analysis, a synthesized vegetation/NIR proxy, and film-characteristic-curve modelling). It is not, and cannot be, true infrared photography without external hardware.

External IR/thermal hardware integration exists only as a UI framework (`SensorMode.EXTERNAL_IR`, `SensorMode.THERMAL` in `core/Models.kt`) — **no external sensor is actually wired up or tested.** If you connect real IR/thermal hardware, expect to write the capture path yourself; nothing beyond the mode enum and its label exists today. The Hardware Test screen tests for a phone-visible near-IR LED leak (for example, a TV remote), not true thermal/IR capture.

## What's implemented

### Rendering pipeline

- Full OpenGL ES 2.0 fragment-shader pipeline (`core/gl/SpectralGlPipeline.kt`): the live preview renders at full camera-preview resolution with zero per-frame CPU allocation; still captures run the same shader over the full-resolution sensor image via an offscreen FBO and `glReadPixels`.
- Capture targets the sensor's highest available resolution, requesting up to 8160×6144 on capable hardware. CameraX falls back to the next supported resolution where the camera HAL does not expose an unbinned mode.
- Saved JPEGs use compression quality 100.

### Spectral presets: two structured film-look families

Spectral Camera is a dedicated film-emulation tool: every preset belongs to
one of two flagship families, and nothing else. Each family shares a single
physically-motivated rendering engine (`monoLook()` / `aeroLook()` in
`SpectralGlPipeline.kt`); every family member is a *data table entry* in
`core/FilmLook.kt` (`FilmLookLibrary`) that reparameterizes the shared
engine's tone curve, synthetic-NIR/Wood-effect strength, sky response, water
floor, halation, grain, and acutance. Adding a new stock is a table entry,
not new shader code. See `core/FilmLook.kt` for the full parameter set and
`docs/VALIDATION.md` for how each family behaves on named failure scenes.

**Monochrome IR family** (`core/gl/SpectralGlPipeline.kt`'s `monoLook()`, driven by `MonoIRLook`):

| Preset | Character |
|---|---|
| Rollei Infrared 400 | Reference restrained IR: textured glowing foliage, dense gradated skies, fine grain, controlled anti-halation glow. |
| Kodak HIE | No anti-halation backing: deepest toe, hardest drama, near-black skies, strongest bloom. |
| Ilford SFX 200 | Gentler extended-red response, finer tonality, minimal halation. |
| Moderate IR (Konica-style) | Balanced middle ground between restrained and dramatic; broadly usable default. |
| Fine-Grain Infrared | Neutral, print-oriented: finest grain, mildest Wood effect, tightest halation - clean rather than moody. |
| Soft Vintage IR | Romantic low-contrast print look: milky highlights, dreamy wide halation, coarser grain, lifted blacks. |

**Aerochrome family** (`aeroLook()`, driven by `AerochromeLook`, all sharing the physically-grounded `aerochrome()` EIR colorimetry):

| Preset | Character |
|---|---|
| Aerochrome Classic | The reference EIR grade: magenta-red foliage, deep cyan sky, filmic false-colour balance. |
| Aerochrome Soft | Gentler contrast, pastel foliage magenta, paler sky, minimal glow. |
| Aerochrome Dense | Punchier contrast, deeper cyan sky, more saturation headroom, dramatic halation. |
| Aerochrome Gold (orange filter) | EIR with orange filter: golden foliage, teal sky. |
| Aerochrome Faded / Vintage | Desaturated, lifted blacks, warm cast, hazy pale sky - an aged-print character. |

All presets are shader functions in `SpectralGlPipeline.kt`; there is no separate native implementation per preset. There is no "experimental" or novelty category - every preset is a calibrated member of one of the two film families above.

### Manual controls

The live screen includes stepped photographic controls rather than free numeric sliders:

- exposure compensation in real stops, converted through the camera's reported EV step;
- full-manual ISO and shutter where the device exposes Camera2 `MANUAL_SENSOR` support;
- current aperture and exposure state where available;
- digital exposure, Blacks/Whites, Contrast, Saturation, Hue, Grain, Bloom, Sharpness, channel weighting/swap, sky suppression, and foliage lift;
- each film stock carries a small always-on baseline grain matched to the emulsion (film is never grainless); the Grain control adds on top of that baseline;
- reset to calibrated film defaults.

### Capture, gallery, and hardware test

- Tap-to-focus, torch, and front/rear camera switching.
- Optional original image saved alongside the processed result.
- Captures saved to `DCIM/SpectralCamera` through MediaStore.
- Gallery distinguishes full, selected-photo, current-install-only, and denied library access.
- Android 14+ selected-photo access is rechecked whenever the gallery resumes.
- Historical captures from previous installations can be recovered after the user grants photo-library access.
- Hardware Test looks for the colour signature of a near-IR LED visible through the phone camera.

## Validation

Look tuning is judged against a named set of failure scenes (blue sky with
clouds, deep-shadow foliage, skin, red painted objects, water/glass, wooded
shadow, bark/masonry, haze) rather than pretty demos alone. See
[`docs/VALIDATION.md`](docs/VALIDATION.md) for the full scene list, expected
behavior, and unacceptable failure modes for both families.

## Known limitations

- RGB-to-NIR estimation is fundamentally approximate. Materials whose visible colour does not predict their real NIR reflectance can be rendered incorrectly.
- Front-camera resolution is usually lower than rear-camera resolution.
- Full-manual ISO and shutter require Camera2 `MANUAL_SENSOR` support.
- Maximum capture resolution depends on the streams exposed by the device camera HAL.
- Print-quality claims are based on file resolution and JPEG quality, not ICC-profiled print calibration.
- Camera orientation, CameraX integration, and shader output still require physical-device verification; they are not fully covered by JVM tests.

## Test status

The project now includes focused JVM tests for Android-version-specific gallery permission decisions. GitHub Actions runs `testDebugUnitTest`, builds an ephemeral debug APK, and builds an unsigned release APK on pull requests and pushes to `main`.

There is not yet a full Robolectric, instrumentation, or screenshot-test suite. Physical-device checks remain mandatory for:

- front/rear preview and saved-image orientation;
- CameraX resolution and manual controls;
- Android 14+ system photo-selection UI;
- real MediaStore migration behaviour after reinstalling the application.

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

Without release-signing environment variables, `assembleRelease` intentionally produces `app-release-unsigned.apk`. Supplying only some signing variables is treated as a configuration error; all four are required together:

- `KEYSTORE_FILE`
- `KEYSTORE_PASS`
- `KEY_ALIAS`
- `KEY_PASS`

Ordinary GitHub Actions CI never receives the stable release key. Its debug APK is signed with the runner's normal ephemeral debug identity and must not be presented as an update-compatible public release.

Stable APK/AAB publication happens only through `.github/workflows/release.yml` after a `vX.Y.Z` tag is pushed. The workflow verifies the configured signing certificate, APK package ID, APK signature, and checksums before publishing. See [`docs/RELEASE.md`](docs/RELEASE.md).

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

- `core/FilmLook.kt` — structured film-look parameter tables (`MonoIRLook`, `AerochromeLook`) for the two flagship families; the only place per-stock numbers live.
- `core/gl/SpectralGlPipeline.kt` — live and still OpenGL rendering.
- `core/camera/CameraController.kt` — CameraX session, capture, focus, and manual exposure integration.
- `core/data/CameraSettingsRepository.kt` — DataStore settings persistence.
- `core/media/MediaRepository.kt` — MediaStore save and gallery query logic.
- `core/media/GalleryPermissionPolicy.kt` — testable Android-version permission rules.
- `core/state/SpectralViewModel.kt` — settings, capture orchestration, and gallery UI state.
- `core/hardware/HardwareTestAnalyzer.kt` — near-IR LED signature detector.
- `ui/` — Jetpack Compose screens.

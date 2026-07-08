# Spectral Camera

An Android camera app that emulates infrared and false-colour spectral film looks in real time. The live preview and the saved photo are rendered by the *same* OpenGL ES fragment shader, so what you see through the viewfinder is what you get in the file.

**Current version:** 1.8.1 (versionCode 23) · **Status:** actively developed, manually verified on a physical device (Motorola Edge 60 Fusion, Android 16) after every change. No automated test suite yet — see [Test status](#test-status).

## Honesty note — read this first

**The phone's camera sensor has no infrared sensitivity.** Every filter in this app is a *simulated* spectral/IR look, computed from the visible-light RGB image using colour-science heuristics (chromaticity analysis, a synthesized vegetation/NIR proxy, and film-characteristic-curve modelling). It is not, and cannot be, true infrared photography without external hardware.

External IR/thermal hardware integration exists only as a UI framework (`SensorMode.EXTERNAL_IR`, `SensorMode.THERMAL` in `core/Models.kt`) — **no external sensor is actually wired up or tested.** If you connect real IR/thermal hardware, expect to write the capture path yourself; nothing beyond the mode enum and its label exists today. The Hardware Test screen tests for a phone-visible near-IR LED leak (e.g. a TV remote), not true thermal/IR capture.

## What's implemented

### Rendering pipeline
- Full OpenGL ES 2.0 fragment-shader pipeline (`core/gl/SpectralGlPipeline.kt`): the live preview renders at full camera-preview resolution with zero per-frame CPU allocation; still captures run the *same* shader over the full-resolution sensor image via an offscreen FBO + `glReadPixels`.
- Capture targets the sensor's highest available resolution (requests up to 8160×6144 / ~50MP-class on capable hardware such as the Edge 60 Fusion's main sensor; CameraX falls back to the device's next-best supported resolution where the camera HAL doesn't expose the unbinned mode — actual output resolution depends on the device and has not been verified across the whole Android device landscape).
- Saved JPEGs are written at maximum compression quality (100).

### Spectral presets (`core/Models.kt` → `SpectralPreset`)
| Preset | What it does |
|---|---|
| Rollei Infrared 400 | Reference monochrome IR emulation: synthesized IR luminance pushed through a characteristic (H&D) tone curve calibrated against published Agfa Aviphot Pan 200 / Rollei IR 400 densitometry — soft toe, steep midsection, gently rolled shoulder so sunlit foliage renders bright but textured (never clipped to paper white). Density-dependent film grain (strongest in midtones), subtle halation (Rollei has an anti-halation layer, so glow is minimal). |
| Kodak HIE style | Same pipeline, different curve/grade: deeper toe, denser skies, and noticeably stronger halation bloom (HIE famously lacks an anti-halation backing). |
| Ilford SFX 200 style | Gentler grade throughout: milder Wood effect, finer tonal separation, minimal glow. |
| Aerochrome-Style False Colour | Digital colour-infrared (EIR) emulation: synthesized NIR proxy remapped through the film's channel logic (NIR→R, R→G, G→B) with a yellow-filter blue kill. Vegetation and blue/water classification are exposure-invariant (chromaticity-based), so the same leaf or same pool reads the same colour in sun or shade. |
| Aerochrome Gold | Same model, orange-filter variant — warmer foliage, cooler/teal sky. |
| Red 720nm-Style | Simple warm red-infrared channel remap. |
| Blue/Cyan Spectral | Cool cyan-blue channel shift. |
| Fake Thermal Palette | Heat-map style false colour, clearly framed as a stylised palette, not a thermal sensor reading. |
| Night Surveillance IR | Green-tinted monochrome utility look. |

All presets are shader functions in `SpectralGlPipeline.kt`; there is no per-preset native code path.

### Manual controls (`ui/screens/LiveCameraScreen.kt`)
Reworked from numeric sliders to stepped, photographically-labelled switches:
- **Exposure compensation**: chip row in true photographic stops (−2 … +2 in ½-stop steps), converted to the camera's native EV index using the *actual* EV step reported by the device (read via CameraX `exposureCompensationStep`, not assumed).
- **Full-manual exposure** (only shown if the device declares the Camera2 `MANUAL_SENSOR` capability): an Auto/Manual switch. In Manual, auto-exposure is turned off via Camera2 interop (`CONTROL_AE_MODE_OFF`) and ISO / shutter speed are set directly on the capture request (`SENSOR_SENSITIVITY`, `SENSOR_EXPOSURE_TIME`), applied to the *live* session so the preview shows the real metered result. ISO and shutter chip ranges are clamped to what the specific device's sensor reports.
- A live spec line under the exposure control shows the camera's actual aperture (fixed — phones have no iris) and ISO/AE state, read via Camera2 interop where available.
- Digital exposure (post-capture shader gain), Blacks/Whites, Contrast, Saturation, Hue rotation, Grain, Bloom, Sharpness, channel weighting/swap, and sky-suppression/foliage-lift are all stepped named controls (e.g. Off/Low/Medium/High) rather than free sliders.
- **Reset to film defaults** button to return all manual adjustments to the calibrated preset baseline in one tap.

### Capture, gallery, and hardware test
- Tap-to-focus, torch toggle, front/back camera switch.
- Optional "save original" alongside the processed image.
- Gallery screen lists saved captures from the app's MediaStore-backed folder.
- Hardware Test screen: points the camera at a device (e.g. a TV remote) and looks for the characteristic colour signature of a near-IR LED leaking through the phone's sensor (a brightness+chromaticity check, not a true IR sensor reading), to demonstrate the small amount of near-IR a stock camera sensor can actually see.

## Known limitations

- **RGB→NIR estimation is fundamentally an approximation.** There is no physical infrared signal in the source image; vegetation/sky/water classification is done via colour chromaticity heuristics. It fails on materials whose visible colour doesn't predict their real NIR reflectance (e.g. some synthetic fabrics or paints) — a known limitation shared by every RGB-to-NIR method, including trained neural approaches.
- **Front camera resolution is typically lower than the rear camera** on most phones, including the reference device — expect noticeably lower detail on selfies versus rear-camera shots at the same preset.
- **No true manual ISO+shutter without `MANUAL_SENSOR` support.** On devices that don't declare this Camera2 capability, only auto-exposure with EV compensation is available; the Manual toggle simply won't appear.
- **Real full-resolution capture depends on what the device's camera HAL exposes to CameraX** — the app requests up to ~50MP-class resolution, but many devices only expose a pixel-binned stream to third-party camera apps regardless of the sensor's marketed megapixel count.
- **Print-quality claims are based on file resolution and JPEG quality only** (verified: ~12MP rear-camera captures at ~297dpi for a 35×25cm print); no colorimetric print-calibration or ICC profile work has been done.
- **No automated tests** (see below) — correctness has been validated by manual visual/densitometric review against real captured photos during development, not by a CI-enforced test suite.

## Test status

There is currently **no automated unit or instrumentation test suite** in this repository (no `app/src/test` or `app/src/androidTest` sources exist). All functional verification to date has been:
1. Manual builds via the GitHub Actions workflow (`.github/workflows/android.yml`), which compiles both debug and release APKs on every push and fails the run (auto-filing a GitHub issue with the compiler log) if the code doesn't build.
2. Manual visual review of real photos taken with the installed APK on a physical Motorola Edge 60 Fusion (Android 16), including targeted numerical validation of specific shader outputs (colour-swatch checks and tonal-zone measurements) against photographic film references during development.

If you want CI-enforced correctness, adding Robolectric/JVM unit tests around the pure-Kotlin logic (`Models.kt`, `irHDCurve`/`irLuminance` ported to a testable form) and/or Espresso instrumentation tests would be the natural next step — none exist today.

## Requirements

- Android 8.0+ (minSdk 26), compiled against SDK 35.
- Camera permission; `READ_MEDIA_IMAGES` on Android 13+, `READ_EXTERNAL_STORAGE` (maxSdk 32) on older versions.
- No internet permission is requested or used by the app itself.

## Build

From the project root:

```
./gradlew assembleDebug assembleRelease
```

GitHub Actions builds both APKs on every push to `main` and uploads them as workflow artifacts (`spectral-camera-debug`, `spectral-camera-release`) — this is the actual build verification path used during development, since this project has been built entirely from an Android phone (GitHub web UI + Actions), with no local desktop toolchain. If a push breaks the build, the workflow automatically files a GitHub issue containing the compiler error log.

The release APK is signed with the debug keystore so it installs directly for testing; **replace the signing config with a real keystore before any store/production release.**

If you build locally inside Termux, put any `android.aapt2FromMavenOverride` override in `~/.gradle/gradle.properties` on the device — it must not live in the repo, or it breaks the GitHub Actions runners (this was a real regression fixed early in the project's history).

## Privacy

- The app does not request network/internet permission and does not transmit images or telemetry anywhere.
- Camera permission is required to function at all; media-read permission is used only to display your own saved captures in the in-app gallery.
- All processing (the spectral/IR simulation) happens on-device via the GPU shader; no cloud processing is involved.

## Architecture

- `core/gl/SpectralGlPipeline.kt` — the fragment-shader filter chain (all presets), the live-preview `GLSurfaceView` renderer, and the still-capture processor (FBO + `glReadPixels`).
- `core/camera/CameraController.kt` — CameraX session management, including the Camera2-interop full-manual exposure path; full-resolution `ImageCapture`; a small RGBA analysis stream feeding the hardware test.
- `core/data/CameraSettingsRepository.kt` — DataStore-backed persistence of all camera/manual settings across launches.
- `core/state/SpectralViewModel.kt` — settings state, gallery loading, capture orchestration.
- `core/hardware/HardwareTestAnalyzer.kt` — the near-IR LED colour-signature detector used by the Hardware Test screen.
- `ui/` — Jetpack Compose screens: live camera + manual panel, gallery, hardware test.

## Contributing / continuing this project

This repository has been developed entirely through the GitHub web UI and GitHub Actions from an Android phone (no local desktop build environment). Commit messages document the reasoning behind each change in detail, including several rounds of colour-science research and recalibration for the Aerochrome and monochrome-IR presets — read the git log for the full design history before making further changes to `SpectralGlPipeline.kt`.

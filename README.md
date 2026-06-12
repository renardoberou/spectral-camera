# Spectral Camera

An Android camera app for infrared and spectral-style photography looks. Version 1.0 renders everything on the GPU: the live preview is a full-resolution OpenGL ES shader pipeline, and captures run the exact same shader over the full-size still — what you see is what you save.

## Highlights

- GPU-rendered live preview (OpenGL ES 2.0) at full preview resolution — smooth and allocation-free
- Full-resolution still capture (up to the sensor's 4:3 maximum) processed by the same shader as the preview
- Spectral presets:
  - B&W Infrared
  - High Contrast IR
  - White Foliage / Dark Sky
  - Aerochrome-Style False Colour — Kodak EIR emulation with synthetic NIR, channel shift (NIR→R, R→G, G→B), sky suppression and a slide-film tone curve
  - Aerochrome Gold (orange filter) — warmer foliage, teal skies
  - Red 720nm-Style
  - Blue/Cyan Spectral
  - Fake Thermal Palette
  - Night Surveillance IR
- Two exposure controls: hardware exposure compensation (main screen) and digital shader gain (manual panel)
- Manual tuning: contrast, blacks/whites, bloom, grain, sharpness, channel weights and swapping, hue rotation, saturation
- Tap-to-focus, torch, front/back camera
- Gallery view for saved captures, with optional saving of the unprocessed original
- Hardware test screen for near-IR hotspot detection (TV-remote test)

## Honesty note

The internal phone camera cannot capture true infrared — every preset is a simulated spectral look and the app labels output accordingly. External IR/thermal hardware support exists only as a framework.

## Requirements

- Android 8.0+ (minSdk 26) with a camera
- Camera permission; media read permission on supported Android versions

## Build

From the project root:

```
./gradlew assembleDebug assembleRelease
```

GitHub Actions builds both APKs on every push to `main` and uploads them as workflow artifacts (`spectral-camera-debug`, `spectral-camera-release`). The release APK is signed with the debug keystore so it installs directly; replace the signing config before any store upload.

If you build locally inside Termux, put the `android.aapt2FromMavenOverride` line in `~/.gradle/gradle.properties` (it must not live in the repo, or CI runners break).

## Architecture

- `core/gl/SpectralGlPipeline.kt` — fragment-shader filter chain, preview renderer, and still-image processor (FBO + readPixels)
- `core/camera/CameraController.kt` — CameraX session feeding a SurfaceTexture into the GL view; full-res ImageCapture; tiny RGBA analysis stream for the hardware test
- `core/state/SpectralViewModel.kt` — settings, gallery, capture orchestration
- `ui/` — Compose screens (live camera, gallery, hardware test)

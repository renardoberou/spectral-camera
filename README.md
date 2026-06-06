# Spectral Camera

An Android camera app for experimenting with infrared and spectral-style looks using live preview, manual adjustments, gallery browsing, and a hardware test screen.

## Highlights

- Live camera preview with front/back camera support
- Spectral presets for common looks:
  - B&W Infrared
  - High Contrast IR
  - White Foliage / Dark Sky
  - Aerochrome-Style False Colour
  - Red 720nm-Style
  - Blue/Cyan Spectral
  - Fake Thermal Palette
  - Night Surveillance IR
- Manual tuning controls:
  - contrast
  - exposure compensation
  - blacks / whites
  - bloom
  - grain
  - sharpness
  - channel weights and channel swapping
  - hue rotation and saturation
- Gallery view for saved captures
- Hardware test screen for near-IR hotspot detection
- Optional saving of the original capture alongside the processed image

## Requirements

- Android device with a camera
- Android Studio or Gradle build environment
- Camera permission
- Media read permission on supported Android versions

## Build and run

From the project root:

```bash
./gradlew assembleDebug
```

Or open the project in Android Studio and run the `app` module on a device or emulator.

## Permissions

The app requests:

- `android.permission.CAMERA`
- `android.permission.READ_MEDIA_IMAGES`
- `android.permission.READ_EXTERNAL_STORAGE` on older Android versions

## Project structure

- `app/src/main/java/com/renardoberou/spectralcamera/core` — camera settings, presets, processing, storage, and hardware analysis
- `app/src/main/java/com/renardoberou/spectralcamera/ui` — Compose UI and navigation
- `app/src/main/java/com/renardoberou/spectralcamera/ui/screens` — live camera, gallery, and hardware test screens

## Notes

This repo is set up as the canonical GitHub source for the app and currently tracks `main` on GitHub.

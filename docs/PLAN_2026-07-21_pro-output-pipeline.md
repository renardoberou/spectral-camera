# Plan: first pro output pipeline

**Date:** 2026-07-21  
**Branch:** `agent/pro-output-pipeline`  
**Status:** implemented in code; CI and physical-device verification required

## 1. Trigger

The film-look engine now has structured Aerochrome and Monochrome IR families, but capture remained a single decoded JPEG bitmap with a hard-coded CameraX JPEG quality of 95. That made capture/export architecture the next commercial-quality ceiling.

This cycle deliberately does not add presets or rewrite the film shader. It builds the first explicit output system around the existing renderer.

## 2. Current-state diagnosis

Before this cycle:

- `CameraController.capture()` returned one upright JPEG-derived `Bitmap`.
- `ImageCapture` always requested a high-resolution 4:3 stream, maximize-quality mode, but then overrode JPEG quality to 95.
- capture and gallery import both entered `SpectralGlPipeline` as `ARGB_8888` bitmaps;
- processed files were JPEG quality 100, which could not restore information already discarded by the quality-95 source;
- there was no explicit Full Resolution/HQ/Fast policy;
- the optional “raw” filename actually referred to an original JPEG;
- CameraX RAW capability was neither queried nor surfaced.

## 3. Scope implemented

### 3.1 Output-mode model

Added persisted `OutputMode` values:

- **Full Resolution** — maximum-quality source and largest practical processed file;
- **HQ 1080** — high-resolution 16:9 render followed by progressive exact-Full-HD downsample;
- **Fast 1080** — lower-latency 16:9 source, normalized to exact Full HD before film rendering.

`OutputGeometry` provides pure, unit-tested orientation and center-crop rules. A 1920×1088 aligned source becomes a centered 1920×1080 result instead of being stretched or exported with stray rows.

### 3.2 Capture fidelity

- Full Resolution and HQ 1080 now request JPEG quality 100.
- Fast 1080 intentionally uses CameraX minimize-latency mode and JPEG quality 95.
- High-quality modes preserve the previous high-resolution 4:3 source request.
- Fast 1080 requests a 16:9 source near 1920×1080.

### 3.3 Preview/export split

The film model remains shared. The split happens around it:

- preview remains the existing zero-allocation OES path;
- HQ 1080 center-crops the high-resolution source, runs the full still shader, then progressively downsamples the processed result;
- Fast 1080 crops/downsamples before the still shader;
- Full Resolution keeps the existing still-render intent.

No Aerochrome or Monochrome IR colorimetry was changed in this cycle.

### 3.4 RAW readiness

Upgraded CameraX to 1.6.1 and added:

- capability query through `ImageCapture.getImageCaptureCapabilities()`;
- simultaneous `OUTPUT_FORMAT_RAW_JPEG` capture where supported;
- an untouched temporary DNG plus companion JPEG from one shutter event;
- JPEG companion decoding for the current film render;
- durable DNG copy to MediaStore;
- JPEG fallback if RAW is unsupported or the advertised RAW stream cannot bind beside preview.

This is intentionally **RAW sidecar support**, not a RAW-developed processed image. The DNG is for external development and future in-app work.

### 3.5 Media identity

New filenames distinguish:

- `proc` — processed JPEG;
- `orig` — untouched original JPEG;
- `dng` — untouched RAW sidecar;
- sensor mode, film preset, and output mode.

The parser remains backward-compatible with historical `spectral_raw_...jpg` files, which were original JPEGs rather than sensor RAW.

### 3.6 Product UI

Added a dedicated **Pro output** navigation screen instead of crowding the live viewfinder. It exposes output mode, original-JPEG saving, RAW capability, and an honest explanation of the current DNG sidecar limitation.

## 4. Files changed

- `core/Models.kt`
- `core/export/OutputPipeline.kt`
- `core/camera/CapturedFrame.kt`
- `core/camera/CameraController.kt`
- `core/data/CameraSettingsRepository.kt`
- `core/media/MediaRepository.kt`
- `core/state/SpectralViewModel.kt`
- `ui/SpectralCameraApp.kt`
- `ui/screens/ProOutputScreen.kt`
- `app/build.gradle.kts`
- `README.md`
- `docs/VALIDATION.md`
- `app/src/test/.../OutputGeometryTest.kt`

## 5. Verification available in this environment

- Output geometry is isolated from Android camera hardware and covered by JVM tests.
- Existing CI is expected to compile, run unit tests, and build debug plus unsigned release APKs.
- Repo review verifies the film shader itself is unchanged.

## 6. Verification that still requires hardware

- actual Full/HQ/Fast CameraX stream negotiation;
- capture latency and memory pressure on representative devices;
- RAW+JPEG support on rear vs front cameras;
- dual callback/file integrity and external DNG development;
- MediaStore DNG visibility across Android versions;
- preview/export visual intent and orientation;
- fallback when a camera advertises RAW+JPEG but cannot bind it with preview;
- all named Aerochrome/Monochrome validation scenes across output modes.

## 7. Honest limitations

- The processed result remains JPEG/bitmap-derived.
- DNG data is not demosaiced, camera-profiled, or sent through the film shader.
- HQ 1080 uses platform bitmap resampling, albeit progressively, rather than a custom Lanczos/GPU resampler.
- Full Resolution remains constrained by the camera HAL and `GL_MAX_TEXTURE_SIZE`.
- A rebind is required when output mode or RAW sidecar changes because those settings alter the CameraX use-case configuration.

## 8. Next best cycle

1. Validate this branch on a RAW-capable rear camera and non-RAW front camera.
2. Record latency, dimensions, DNG integrity, and visual parity in `docs/VALIDATION.md`.
3. Add a scene-referred DNG import/develop prototype with explicit camera color matrices and demosaic strategy.
4. Compare JPEG-derived vs RAW-developed film renders on foliage, sky, neutral walls, water, and highlight recovery.
5. Only after that evidence, decide whether the RAW-developed path should replace or complement the current WYSIWYG JPEG path.

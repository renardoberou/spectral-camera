# Plan: computational HDR and Ultra HDR imaging pipeline

**Date:** 2026-07-22  
**Branch:** `agent/hdr-imaging-pipeline`  
**Draft PR:** #25  
**Status:** code implemented; build CI and physical-device verification required

## 1. Objective

Add dynamic-range capture and display capability without turning Spectral Camera into a conventional halo-heavy HDR filter.

The intended order is:

```text
Camera
→ optional multi-frame capture
→ scene-linear HDR merge
→ normalization / tone map
→ synthetic NIR estimation
→ Rollei / HIE / SFX / Aerochrome film simulation
→ Full Resolution / HQ 1080 / Fast 1080
→ optional Ultra HDR JPEG gain map
```

The existing film shaders remain the authority for spectral classification and stock personality. HDR supplies a better source before those decisions.

## 2. Architectural decisions

### 2.1 HDR is optional capture, not the default look

Standard remains the fast, motion-safe mode. Computational HDR is a photographer-selected three-frame still mode for static or slow scenes.

Reason: a bracket cannot be made fully motion-free on arbitrary phone input, and forcing it globally would damage immediacy, action capture and preview/save expectations.

### 2.2 Real exposure bracket

Automatic exposure uses the active camera's compensation range and step. The planner requests approximately −2, 0 and +2 EV where possible, then adapts to range boundaries without duplicating values.

Manual mode holds ISO and brackets exposure time where `MANUAL_SENSOR` is available.

CameraX futures are awaited before every shutter event. This avoids the common failure where the application requests three EV values but captures multiple frames before the repeating request has adopted them.

### 2.3 JPEG HDR in this cycle

The current merge uses decoded JPEG brackets. RAW sidecar and HDR are mutually exclusive.

A true RAW bracket would require three DNGs plus black level, white level, CFA/demosaic, lens shading, white balance and camera color-matrix handling before alignment and fusion. Pretending the existing DNG sidecar toggle provides that pipeline would be misleading.

### 2.4 Memory-bounded source

Three unbinned 50 MP ARGB bitmaps are unsafe on normal application heaps. Computational HDR requests a high-quality binned/approximately 12 MP-class stream; Fast 1080 requests a Full-HD-class stream.

The merger reads source rows rather than expanding every source into a second full-frame float buffer. Device memory profiling remains required before release.

### 2.5 Global tone mapping only

Natural, Filmic and Low Contrast tone maps are global luminance mappings. They do not apply local contrast or edge-aware shadow lifting.

Reason: local HDR operators can create halos, grey shadows and plastic texture before the film renderer, directly conflicting with the Aerochrome and monochrome-IR goals.

### 2.6 Reconstruct Ultra HDR after film rendering

The gain map is generated after the false-colour or monochrome transform. A camera/pre-film gain map is not semantically valid after channels and tonal relationships have been radically changed.

Scene headroom from the merge is combined with final processed luminance. This deliberately suppresses gain in dark EIR skies, IR water and dense film shadows.

## 3. Implemented components

### Capture

- `HdrCaptureMode.OFF` / `THREE_FRAME`
- automatic compensation bracket planner
- manual shutter bracket planner
- asynchronous CameraX/Camera2 request completion
- exposure restoration after success/failure
- truthful fallback to one-frame Standard metadata where no bracket can be formed
- RAW/HDR mutual exclusion

### Alignment and merge

- exposure-normalized logarithmic-luminance thumbnails
- translation search and common valid crop
- sRGB-to-linear conversion
- exposure-scale normalization
- clipping-aware well-exposed weighting
- reference-biased deghost weighting
- sampled black/median/high percentiles
- global normalization
- three selectable tone maps
- low-resolution headroom/gain field

### Film and output

- merged SDR working rendition enters the unchanged film shader
- Full Resolution, HQ 1080 and Fast 1080 remain available
- HDR high-quality source stays bounded for memory
- optional API-34 gain map attached to a copy of the processed image
- typed `uhdr` filename and metadata

### Display

- gallery identifies Ultra HDR files
- detail viewer checks the decoded bitmap's actual gain-map state
- activity window switches to HDR only while a gain-map image is visible
- prior window color mode is restored when the dialog closes

### Tests and validation

- bracket planner tests
- sRGB/linear round trip
- tone-map monotonicity and bounds
- deghost weight behavior
- known-shift translation recovery
- expanded `docs/VALIDATION.md` with capture, motion, memory, JPEG/R and film-regression cases

## 4. Files added or changed

- `core/Models.kt`
- `core/camera/CapturedFrame.kt`
- `core/camera/CameraController.kt`
- `core/hdr/HdrMath.kt`
- `core/hdr/HdrPipeline.kt`
- `core/hdr/UltraHdrExporter.kt`
- `core/data/CameraSettingsRepository.kt`
- `core/state/SpectralViewModel.kt`
- `core/media/MediaRepository.kt`
- `ui/SpectralCameraApp.kt`
- `ui/screens/ProOutputScreen.kt`
- `ui/screens/GalleryScreen.kt`
- `app/build.gradle.kts`
- `app/src/test/.../HdrMathTest.kt`
- `README.md`
- `docs/VALIDATION.md`

The branch also contains the pro-output and RAW-sidecar foundation inherited from the same comprehensive PR.

## 5. What is verifiable without a camera

- Kotlin/Android compilation
- unit-test behavior of bracket planning and HDR math
- debug and unsigned release builds
- output geometry
- source-level preservation of the existing film shader
- API guards for Ultra HDR

## 6. What requires hardware

- actual EV and shutter differentiation in JPEG output
- exposure restoration and preview stability
- handheld alignment and deghosting
- capture and merge latency
- peak heap use on 4/6/8/12 GB devices
- rear/front/lens-specific bracket support
- Android 14+ Bitmap gain-map JPEG round trip
- HDR panel luminance behavior
- SDR compatibility in independent viewers and sharing destinations
- Aerochrome/monochrome validation scenes in Standard versus HDR

## 7. Known limitations

- JPEG brackets are not sensor-linear RAW.
- Translation cannot correct rotation, projective motion or parallax.
- Reference-biased deghosting may deliberately sacrifice recovered range in moving areas.
- The live viewfinder is not a fused HDR preview.
- Ultra HDR gain after a false-colour transform is reconstructed display intent, not preserved original scene radiance.
- Gallery reprocessing is single-frame and does not recreate a bracket.
- No local HDR tone mapping is provided by design.

## 8. Release gates

Do not merge as a release-ready feature until:

1. CI is green.
2. Auto and manual brackets produce distinct exposures on device.
3. Exposure state restores reliably.
4. Memory and latency are recorded for Full/HQ/Fast HDR.
5. At least one static, handheld and moving-subject test passes.
6. JPEG/R round-trip returns `Bitmap.hasGainmap() == true` on Android 14+.
7. SDR viewers show the intended base.
8. Dark Aerochrome sky and monochrome water do not glow in HDR display.
9. All 11 film looks retain their material and stock identity.

## 9. Next cycle

After device hardening, the next quality frontier is a RAW-burst prototype:

- capture three DNG frames where the stream combination permits;
- decode metadata and sensor mosaic into scene-linear camera RGB;
- align before demosaic where practical or use a high-quality demosaic first;
- merge and transform through camera-specific color matrices;
- compare against JPEG HDR for highlight recovery, foliage classification and color stability;
- retain Standard JPEG WYSIWYG as a fast workflow rather than replacing it blindly.

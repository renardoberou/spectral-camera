# Plan: complete HDR capture safely, then add true RAW HDR

**Date:** 2026-07-22  
**Branch:** `agent/hdr-imaging-pipeline`  
**Draft PR:** #25  
**Trigger:** real-device Full Resolution and HQ 1080 HDR captures recorded visibly different bracket exposures, then failed after the bracket with `Capture framebuffer incomplete: 0`.

## 1. Solve the observed failure before expanding scope

### 1.1 Reproduction evidence

The live viewfinder visibly stepped through the HDR bracket, proving that CameraX capture and exposure changes completed. Failure occurred later, when the merged source entered the offscreen OpenGL still renderer.

### 1.2 Root cause

`SpectralRenderer.processBitmap()` allocates all of the following at the requested render size:

1. source RGBA texture;
2. target RGBA texture;
3. framebuffer attachment;
4. `width × height × 4` direct readback buffer;
5. output ARGB bitmap.

The old guard checked only `GL_MAX_TEXTURE_SIZE`. A dimension can be legal while the simultaneous source/target/readback allocation is not. On the tested phone, Full/HQ HDR reached the renderer with a large merged bitmap; target texture allocation failed and `glCheckFramebufferStatus()` returned `0`.

This was not an HDR-bracket timeout. The bracket finished. The render target did not.

### 1.3 Implemented completion guarantee

The capture orchestrator now uses an adaptive offscreen-render policy:

- first attempt the requested resolution;
- retry only errors identified as framebuffer/texture/out-of-memory allocation failures;
- preserve aspect ratio;
- descend through mode-specific safe long-edge tiers;
- keep HQ 1080's final exact 1920×1080/1080×1920 finishing;
- never repeat the camera bracket merely because the GPU target was too large;
- propagate non-allocation shader/camera errors unchanged.

Current retry tiers:

- Full Resolution: 4096 → 3456 → 3072 → 2560 → 2304 → 2048 → 1920;
- HQ 1080: 3072 → 2560 → 2304 → 2048 → 1920;
- Fast 1080: 1920 → 1600 → 1280.

The commercial behavior is now “largest completed result the active GPU can allocate,” not “request an optimistic size and lose the photograph.” A future tiled renderer can restore arbitrarily large final dimensions without a single full-size RGBA FBO.

## 2. True RAW HDR design

### 2.1 Definition

True RAW HDR in Spectral Camera means:

```text
RAW_SENSOR Bayer frames
→ static/dynamic black subtraction
→ white-level normalization
→ exposure normalization using SENSOR_EXPOSURE_TIME × SENSOR_SENSITIVITY
→ CFA-preserving alignment
→ radiance fusion in the Bayer mosaic
→ demosaic
→ captured white-balance gains
→ captured sensor-to-linear-sRGB transform
→ scene normalization / HDR tone map
→ synthetic NIR and material classification
→ film simulation
→ output finishing
→ optional post-film Ultra HDR gain map
```

It does **not** mean saving a DNG beside a JPEG and still processing the JPEG. The processed film image must originate from RAW_SENSOR samples.

### 2.2 Hardware and metadata gates

The mode is enabled only when the active lens/session provides:

- CameraX RAW in-memory output;
- Camera2 `RAW` capability;
- `MANUAL_SENSOR` control;
- a supported four-channel Bayer CFA (`RGGB`, `GRBG`, `GBRG`, or `BGGR`);
- black-level pattern;
- white level;
- exposure time and sensitivity results;
- white-balance gains;
- colour-correction transform.

A lens without those requirements must not present True RAW HDR as available.

### 2.3 Capture strategy

- Obtain the current metered exposure from the latest Camera2 result when the user is not in manual mode.
- Hold ISO fixed.
- Bracket shutter time around the current exposure, nominally −2 / 0 / +2 EV and clamped to the reported range.
- Lock AWB during the bracket where supported.
- Await each Camera2 request update before triggering the RAW frame.
- Match each `RAW_SENSOR` image to its exact `TotalCaptureResult` by sensor timestamp.
- Copy the packed Bayer plane while respecting row and pixel stride.
- Optionally write every still-open RAW image to DNG with `DngCreator` and the matching capture result.
- Restore the user's prior auto/manual exposure state after success, cancellation, or failure.

### 2.4 CFA-preserving alignment

A one-pixel shift swaps Bayer colour identity. RAW alignment therefore operates on 2×2 CFA-cell luminance thumbnails and converts the estimated displacement back to even raw-pixel shifts. Fusion never averages red samples with green or blue samples.

The initial implementation is translation-only. Rotation, projective motion, parallax and independently moving scene regions remain release-test concerns.

### 2.5 Bayer-domain fusion

For each mosaic location:

- subtract the parity/channel black level;
- divide by white-minus-black range;
- divide by the frame exposure product relative to the reference;
- prefer samples away from sensor black and saturation;
- reduce non-reference contribution when radiance disagrees with the reference;
- merge in linear sensor radiance.

This is performed before demosaic. The current demosaic is bilinear and intentionally readable; a later edge-aware or frequency-domain demosaic can improve detail after correctness is established.

### 2.6 Colour and normalization

The merged sensor RGB uses:

- the captured `RggbChannelVector` gains;
- the captured `COLOR_CORRECTION_TRANSFORM` into linear sRGB;
- percentile-based scene keying and white point;
- Natural, Filmic or Low Contrast global tone mapping.

The resulting display-referred working bitmap then enters the existing synthetic-NIR and film shader. No separate RAW-only film look is introduced.

### 2.7 RAW files

When “Save RAW bracket DNGs” is enabled, all valid bracket DNGs are copied to MediaStore with distinct numbered names. When disabled, the in-memory RAW merge still runs and temporary RAW files are not retained.

File identity distinguishes:

- `SDR1` — Standard capture;
- `JHDR2`/`JHDR3` — JPEG Computational HDR;
- `RHDR2`/`RHDR3` — True RAW HDR;
- `proc` — processed SDR JPEG;
- `uhdr` — processed Ultra HDR JPEG;
- `orig` — JPEG source/reference where one exists;
- `dng01`… — RAW bracket members.

## 3. Implementation status

Implemented in this branch:

- adaptive GPU framebuffer fallback for Full and HQ capture;
- `RAW_THREE_FRAME` capture mode and capability state;
- in-memory CameraX `RAW_SENSOR` acquisition;
- Camera2 capture-result timestamp matching;
- fixed-ISO shutter brackets;
- optional per-frame DNG creation;
- packed Bayer copy with stride handling;
- black/white-level, WB-gain and colour-transform capture;
- CFA-cell alignment;
- Bayer-domain radiance fusion;
- bilinear demosaic;
- RAW-derived tone-mapped film input;
- existing film shader and output-mode integration;
- optional Ultra HDR path using merged scene headroom;
- complete DNG bracket MediaStore naming;
- pure JVM tests for Bayer layout, RAW normalization, clipping weights and exposure products.

## 4. Honest limitations

- Physical-device correctness is not established by compilation alone.
- Camera vendors may omit or inconsistently report dynamic gains/colour transforms.
- The first demosaic is bilinear.
- Alignment is translation-only.
- Moving content can lose HDR range through reference-biased deghosting.
- Full Resolution can adaptively reduce render dimensions when the GPU cannot allocate a large offscreen target.
- Ultra HDR gain-map orientation and saved-file round trip require portrait/landscape device tests.
- No RAW HDR live preview is attempted.

## 5. Release gates

Do not mark True RAW HDR release-ready until all of the following pass on the target phone:

1. Full and HQ JPEG HDR complete without the old framebuffer error.
2. The final dimensions and any adaptive fallback are recorded.
3. Three RAW frames contain distinct exposure times at fixed ISO.
4. Exposure and preview brightness restore after success and cancellation.
5. Saved bracket DNGs open independently and report matching exposure metadata.
6. RAW-derived neutral colour is plausible before the spectral look.
7. Static alignment has no CFA colour zippering or double edges.
8. Handheld translation, foliage motion, people and water are tested.
9. Rollei, HIE, SFX and all Aerochrome looks preserve material classification.
10. Android 14+ Ultra HDR round trips with a gain map and retains correct orientation.
11. Peak heap, capture latency, merge latency and thermal behavior are measured.
12. Unsupported lenses disable True RAW HDR and fall back without a broken camera session.

## 6. Next engineering cycle after device validation

- Replace bilinear demosaic with a higher-quality edge-aware implementation.
- Add lens shading and hot-pixel handling where metadata permits.
- Estimate camera response/local-ISP inconsistency for JPEG HDR separately from RAW HDR.
- Add tiled/striped OpenGL export so Full Resolution does not require a single full-size RGBA framebuffer.
- Build a repeatable RAW/JPEG comparison corpus for foliage, sky, water, skin and neutral architecture.

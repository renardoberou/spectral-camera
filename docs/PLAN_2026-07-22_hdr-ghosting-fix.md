# Plan: eliminate real-device HDR multi-image ghosting

**Date:** 2026-07-22  
**Branch:** `agent/hdr-imaging-pipeline`  
**Trigger:** a real Motorola Edge 60 Fusion capture completed in every output mode, but Computational HDR produced a translucent three-image overlay across the entire scene. Standard capture remained clean.

## 1. What the uploaded result proves

The exposure bracket and final save both completed. The failure is therefore not the earlier framebuffer problem and not a camera timeout.

The output contains repeated cabinet, air-conditioner, wall, table, cable, and window edges across ordinary midtones. That pattern means non-reference exposures were allowed to contribute broadly after an unreliable global translation estimate. The previous per-pixel deghost rule retained a non-zero contribution floor and the reference exposure had only a small minimum weight, so a bad alignment could remain visibly present throughout the frame.

## 2. Required behavior

HDR must obey the following commercial rule:

> A failed or uncertain bracket may lose HDR recovery, but it must never replace a clean photograph with a multi-image overlay.

The normal exposure is the visual source of truth. Other exposures are permitted to help only where they contain information the reference genuinely lacks.

## 3. Fix implemented

### 3.1 Exposure-invariant alignment

The old direct log-luminance error search was replaced with a median-threshold alignment strategy designed for exposure brackets:

- each exposure uses its own median;
- pixels close to the median are excluded because their binary state is unstable;
- alignment compares only confidently dark-versus-bright structure;
- estimates that are ambiguous, hit the search boundary, cover too little useful structure, or do not materially improve the zero-shift result are rejected;
- excessive full-resolution displacement is rejected;
- rejected frames receive zero fusion confidence.

If every non-reference frame is rejected, `HdrPipeline` throws a controlled merge failure. The existing capture orchestrator then renders and saves the normal exposure as a truthfully labelled Standard result.

### 3.2 Reference-anchored exposure fusion

The previous merger averaged all well-exposed frames throughout the image. The new merger begins with the reference exposure and gives other exposures **zero baseline contribution**.

A darker frame can contribute only where the reference is near highlight clipping.

A brighter frame can contribute only where the reference is very dark.

Even in those regions, contribution is multiplied by:

- alignment confidence;
- candidate exposure reliability;
- radiance consistency;
- a flat-region gate.

The flat-region gate keeps hard edges on the reference exposure, where small residual alignment errors would be visible as double outlines. Recovered range is concentrated inside broad clipped windows, clouds, lamps, and dark shadow regions rather than along object boundaries.

### 3.3 No residual ghost floor

Large radiance disagreement can now reduce a candidate weight to zero. The previous fixed non-zero floor was removed.

### 3.4 True RAW HDR receives the same safety policy

The Bayer-domain merger now uses the same principles:

- confidence-gated CFA-cell alignment;
- normal RAW exposure as the anchor;
- darker RAW only for saturated reference samples;
- brighter RAW only for near-black reference samples;
- same-CFA edge protection using two-pixel neighbours;
- controlled fallback if every non-reference RAW alignment is rejected.

## 4. Automated checks

The test suite now covers:

- exposure-offset-invariant known-shift recovery;
- rejection of flat/ambiguous alignment instead of inventing a displacement;
- zeroing of candidate influence in healthy midtones;
- edge protection;
- full rejection of large radiance disagreement;
- existing bracket, tone-map, transfer-function, Bayer, RAW-normalization, and output-geometry tests.

## 5. Physical validation required

Use the same room and approximately the same framing as the failed upload.

1. Standard, Aerochrome Classic.
2. Computational HDR Natural, Fast 1080.
3. Computational HDR Natural, HQ 1080.
4. Computational HDR Natural, Full Resolution.
5. Repeat one HDR capture while deliberately moving the phone slightly between the three visible exposure steps.

Pass criteria:

- no repeated cabinet, air-conditioner, wall, window, cable, table, or guitar edges;
- if alignment is uncertain, result may look close to Standard but must remain single-image and clean;
- window interior may recover detail, but its border must not double;
- capture completes in every output mode;
- filename/metadata must fall back to Standard if every additional exposure was rejected.

Only after this exact regression passes should HDR quality tuning resume.

## 6. Remaining limitation

This correction is intentionally conservative. It can recover less dynamic range than an aggressive blend, especially around complex edges and motion. That is the correct trade at this stage: a clean reference-based photograph is commercially acceptable; a stronger but visibly ghosted composite is not.

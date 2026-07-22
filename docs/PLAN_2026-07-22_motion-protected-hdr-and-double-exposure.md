# Plan: keep HDR identity under motion and add deliberate double exposure

**Date:** 2026-07-22  
**Branch:** `agent/hdr-imaging-pipeline`  
**Draft PR:** #25  
**Trigger:** a real True RAW HDR capture failed with `RAW HDR alignment rejected every non-reference exposure`. The user correctly rejected the previous whole-capture fallback to Standard and requested explicit capture identity plus an intentional Standard-mode double-exposure option.

## 1. Product decisions

### 1.1 An HDR shutter remains an HDR shutter

If the camera successfully records a bracket, the app must not silently rename the result Standard merely because global alignment is uncertain. Wind-blown leaves, water, people, and modest handheld movement are normal photography conditions, not exceptional programmer errors.

The result remains:

- **Computational HDR** for a JPEG bracket;
- **True RAW HDR** for a RAW_SENSOR bracket.

If alignment is uncertain, the app marks the result **motion protected** and reduces the amount of recovered range locally. It does not cancel the bracket or mislabel the file.

### 1.2 Clean moving detail is more important than maximum local range

The normal exposure remains the visual source of truth. Other bracket frames can assist only where the reference lacks usable information:

- darker exposure for clipped highlights;
- brighter exposure for near-black shadows;
- broad, low-detail areas rather than hard object boundaries;
- only as much as alignment and radiance consistency permit.

An uncertain frame is retained at zero shift with very low confidence. This lets a broad clipped window or flat deep shadow receive a small recovery contribution while leaves, people, cables, furniture edges, branches, and water texture remain reference-only.

This is not a regression to Standard. The capture is still normalized and tone-mapped from a bracket and retains HDR identity. The conservative areas simply receive little or no non-reference contribution.

### 1.3 Capture identity must be visible, not inferred

The active method is now shown in four places:

1. **Live header:** Standard, Computational HDR, True RAW HDR, or Double Exposure, plus output mode.
2. **Capture progress:** `Capturing Computational HDR…`, `Capturing True RAW HDR…`, `Capture 1`, or `Capture 2 + Save`.
3. **Saved confirmation:** actual method, frame count, output mode, and `motion protected` where applicable.
4. **File identity and MediaStore description:** `SDR1`, `JHDRn`, `JHDRnM`, `RHDRn`, `RHDRnM`, or `DEXP2`.

The Gallery data model also carries a human-readable capture-mode label for every current file, while older files default to Standard.

## 2. Motion-protected JPEG HDR

### Alignment

- Continue using exposure-invariant median-threshold alignment.
- Accepted estimates use their measured translation and confidence.
- Rejected estimates use zero translation and a deliberately small confidence rather than throwing.
- A rejected estimate sets `motionProtected = true`.

### Fusion

- Reference exposure always has the dominant base weight.
- Healthy midtones receive no additional exposure contribution.
- Hard edges receive no additional exposure contribution.
- Darker frames can assist only reference highlight clipping.
- Brighter frames can assist only reference near-black shadows.
- Candidate radiance disagreement can reduce contribution to zero.
- Low-confidence, zero-shift frames are therefore useful only in broad, stable tonal areas.

## 3. Motion-protected True RAW HDR

The same policy is applied in the Bayer mosaic:

- rejected CFA-cell translation becomes zero CFA-cell shift plus very low confidence;
- reference RAW sample remains dominant;
- darker RAW sample helps only near sensor saturation;
- brighter RAW sample helps only near the reference black floor;
- same-colour two-pixel neighbours form the edge gate;
- the bracket remains True RAW HDR and can still produce optional DNG members;
- `motionProtected` is written into saved identity and user messaging.

## 4. Standard-mode Double Exposure

### User workflow

1. Enable **Double Exposure** under Pro imaging.
2. Return to Live; the header says `Capture: Double Exposure`.
3. Press **Capture 1**.
4. The first frame is stored in memory and displayed as a transparent composition guide.
5. Recompose the live camera.
6. Press **Capture 2 + Save**.
7. Both sources are combined once, then the selected Aerochrome or monochrome IR film look is applied.
8. The mode remains active, ready for the next two-frame pair.
9. A visible cancel control discards a pending first frame.

### Image model

The two sources are not processed separately and then blended. Instead:

- each source is prepared using the chosen output geometry;
- each encoded source is converted to linear light;
- the two are combined as balanced half-exposures;
- the combined visible-RGB source enters synthetic NIR/material classification;
- the selected film look, grain, halation, manual adjustments, and export are applied once.

This gives a coherent double-exposed spectral image rather than two unrelated finished filters pasted together.

### Interaction rules

Double Exposure is deliberately separate from HDR:

- enabling Double Exposure turns both HDR modes off;
- enabling either HDR mode cancels a pending first double-exposure frame;
- RAW sidecar capture is off during Double Exposure;
- Ultra HDR export is off during Double Exposure;
- switching lens, output mode, or sensor mode cancels the pending first frame;
- changing final preset or manual film adjustments between frame 1 and frame 2 is allowed because the film transform is applied only after the pair is combined;
- optional source saving writes two clearly numbered source JPEGs.

## 5. Memory policy

A pending first frame must not make the second capture or film render exceed the mobile heap.

- Full Resolution and HQ 1080 double-exposure sources use a safe high-resolution working cap of 3072 pixels on the long edge.
- Fast 1080 uses 1920.
- The transparent live guide is capped at 1080 on the long edge.
- Combination is row-wise rather than allocating two additional full-frame pixel arrays.
- The existing adaptive offscreen-render fallback remains active.

## 6. File identity

Current tokens:

| Token | Meaning |
|---|---|
| `SDR1` | Standard single exposure |
| `JHDR2` / `JHDR3` | JPEG Computational HDR |
| `JHDR2M` / `JHDR3M` | JPEG HDR with motion-protected fusion |
| `RHDR2` / `RHDR3` | True RAW HDR |
| `RHDR2M` / `RHDR3M` | True RAW HDR with motion-protected fusion |
| `DEXP2` | Balanced two-frame Double Exposure |

Assets:

- `proc` — processed SDR JPEG;
- `uhdr` — processed Ultra HDR JPEG;
- `orig` — single/reference JPEG;
- `src01`, `src02` — optional double-exposure source frames;
- `dng` / `dng01`… — Standard or RAW-HDR DNG members.

## 7. Tests and release gates

### HDR motion tests

1. Static room on tripod or firm support.
2. Same room handheld.
3. Wind-blown leaves against sky.
4. Walking person across a static background.
5. Moving water with a static shoreline.
6. Deliberate small camera movement during the bracket.

Pass criteria:

- saved identity remains Computational HDR or True RAW HDR;
- motion-protected label appears when alignment is uncertain;
- no whole-frame triple image;
- object edges remain single;
- moving areas may recover less range, but static flat highlights/shadows can still improve;
- no alignment-rejection error reaches the user.

### Double-exposure tests

1. Capture frame 1 and verify the transparent guide.
2. Recompose and capture frame 2.
3. Verify one processed `DEXP2` image is saved.
4. With source saving enabled, verify `src01` and `src02` are saved.
5. Cancel after frame 1 and verify no file is created.
6. Switch lens/output mode after frame 1 and verify the pending pair is cancelled.
7. Repeat in portrait and landscape.
8. Verify Aerochrome and monochrome IR are applied once after the blend.
9. Repeat multiple pairs while the mode remains enabled.
10. Record memory, latency, and visual guide alignment.

## 8. Honest limitations

- Translation-only alignment still cannot fully model rotation, perspective, parallax, or deforming subjects.
- Motion-protected zero-shift recovery is deliberately weak; it preserves HDR identity but may recover little extra range in a scene with no stable areas.
- Double Exposure is a balanced digital half-exposure blend, not a simulation of every camera/film multiple-exposure behavior.
- The first-frame guide is a transparent preview aid, not a guarantee of pixel-perfect alignment with every preview crop or device rotation.
- Physical-device validation remains mandatory before release.

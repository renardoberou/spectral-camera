# Plan: remove RAW HDR spatial overlays and misregistered Ultra HDR gain

**Date:** 2026-07-22  
**Branch:** `agent/hdr-imaging-pipeline`  
**Draft PR:** #25  
**Trigger:** real portrait True RAW HDR captures saved successfully but displayed large translucent vertical/rectangular overlays. The supplied settings screenshot showed `True RAW HDR`, `Fast 1080`, and `Ultra HDR JPEG` enabled.

## 1. Diagnosis

Two RAW-only paths could create the observed result.

### 1.1 The RAW gain field did not follow the developed bitmap geometry

`RawHdrPipeline` builds its scene-headroom field in native sensor orientation. It then rotates/mirrors only the developed bitmap. After that, `OutputPipeline` may center-crop the upright bitmap from the RAW stream's 4:3 shape to 16:9 for HQ/Fast 1080.

Before this fix, the gain field was passed unchanged to `UltraHdrExporter`. A portrait bitmap could therefore receive a landscape/sensor-oriented headroom map, and a 16:9 image could receive a full 4:3 map. On an HDR display this paints recovered brightness onto unrelated spatial regions and can reveal large translucent bands or apparent scene overlays even when the SDR base is substantially cleaner.

### 1.2 Globally uncertain RAW bracket members still had non-zero influence

The movement-safe RAW merger retained rejected alignments at zero shift with a small global confidence. That policy avoided a shutter failure, but a large unrelated structure could still leak into broad flat highlight/shadow regions.

## 2. Implemented correction

### Gain-field geometry

The RAW headroom field now undergoes the same geometry as its bitmap:

1. rotate by the RAW image's capture rotation;
2. mirror for the front camera when applicable;
3. center-crop to the same 16:9 region used by HQ/Fast 1080;
4. keep normalized coordinates through later scaling/downsampling.

`UltraHdrExporter` now performs a final aspect-ratio consistency check. If a future code path supplies a spatially incompatible gain field, the app saves the correct SDR processed JPEG instead of attaching a corrupt gain map.

### RAW bracket safety

A first RAW merge is used to inspect the measured global alignment confidence and displacement of each auxiliary exposure.

- reliable members are retained;
- low-confidence or excessively displaced members are removed;
- the RAW development is repeated using only the reliable subset;
- if no auxiliary member is reliable, the normal RAW exposure is developed through the same RAW/tone/film path and the saved result remains `True RAW HDR • motion protected` rather than failing or changing to JPEG Standard;
- all captured DNG bracket files remain available when source saving is enabled.

This is deliberately conservative. It trades some recovered range for removal of a second translucent scene.

## 3. Automated coverage

Added JVM coverage for:

- 90-degree RAW gain-field rotation;
- front-camera mirroring after rotation;
- gain-field 4:3-to-16:9 crop;
- Full Resolution no-op geometry;
- existing output, HDR, RAW, Bayer, and double-exposure math remains covered.

## 4. Immediate device validation

Use the same phone and portrait orientation.

1. True RAW HDR + Natural + Fast 1080, **Ultra HDR off**.
2. Repeat the identical framing with **Ultra HDR on**.
3. True RAW HDR + Natural + HQ 1080, Ultra HDR on.
4. True RAW HDR + Natural + Full Resolution, Ultra HDR on.
5. Repeat one indoor monochrome IR frame and one outdoor Aerochrome frame.
6. Open each saved file inside Spectral Camera and one independent viewer.

Pass criteria:

- no large vertical or rectangular translucent overlay;
- Ultra HDR on/off have the same scene geometry;
- Ultra HDR may increase compatible-display highlight brightness but may not move, repeat, or reveal scene structures in another location;
- building, mirror, wall, window, furniture, tree, and pool edges remain single;
- the saved status remains True RAW HDR and identifies motion protection where used;
- Fast/HQ files remain exact Full HD;
- a mismatched gain field must fall back to SDR rather than save a damaged Ultra HDR file.

## 5. Remaining limitation

The RAW merger still uses global translation rather than dense optical flow, and its first demosaic is bilinear. Large parallax or independently moving subjects can reduce the amount of bracket information used. The protected result should remain clean, but the dynamic-range gain may be concentrated in stable regions.

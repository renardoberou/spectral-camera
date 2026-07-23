# Spectral Camera

Professional Android camera for **simulated infrared and Aerochrome-style photography**.

Built for photographers, artists, and researchers who want consistent, film-like IR rendering — not gimmicky filters.

---

## Current version

**1.19.6 (versionCode 53)**  
Status: Active development

This version introduces:

- Pro output pipeline (Full / HQ 1080 / Fast 1080)
- Computational HDR (JPEG)
- True RAW HDR (sensor-linear merge)
- Ultra HDR export (Android 14+)
- Double Exposure (film-style)
- Full focus system (AF + manual + infinity)
- Compact, production-ready Live UI
- Refined pre-film tone, red-detail, highlight-shoulder, and sky response
- Validated density-specific PNG launcher artwork with a round-safe variant

---

## What this app actually is (and is not)

**This is NOT a real infrared camera.**

All IR and Aerochrome looks are **physically-informed simulations** built from:

- visible-spectrum RGB input
- material classification
- synthetic NIR estimation
- film response modeling

HDR improves input data. It does NOT create true infrared capture.

---

## Core imaging pipeline

Camera / import

↓

Optional multi-frame capture

↓

HDR merge (JPEG or RAW where supported)

↓

Normalization + pre-film scene curve + tone mapping

↓

Synthetic NIR estimation

↓

Film rendering
- Rollei 400 IR styles
- Ilford SFX / HIE inspired monochrome IR
- Aerochrome-inspired colour IR families

↓

Output
- Full Resolution
- HQ 1080
- Fast 1080
- Optional Ultra HDR JPEG

---

## Main features

### 1. Pro output pipeline
- Full Resolution export
- HQ 1080 export
- Fast 1080 export
- output-specific processing instead of naive resize

### 2. HDR and RAW HDR
- JPEG Computational HDR
- True RAW HDR where hardware allows
- RAW sidecar support where available
- scene-linear merge before final rendering

### 3. Film-like IR rendering
- Aerochrome-style colour response
- monochrome IR families
- physically-motivated material classification
- refined pre-film curve, selective red highlight taper, smooth shoulder, and cleaner sky handling

### 4. Focus control
- Continuous AF
- Tap & Lock
- Macro AF (when supported)
- Manual Focus
- Infinity
- Fixed Focus fallback

### 5. Double Exposure
- two-step capture flow
- transparent first-frame guide
- balanced blend before film rendering

### 6. Honest product behavior
- no false claim of true infrared capture
- no false claim of full RAW-developed processing when unavailable
- capability-dependent features shown honestly

---

## Live UI

The Live screen is optimized for actual shooting, not demo clutter.

- Compact header
- Presets beside Exposure and Focus
- Bottom navigation for Gallery and Hardware
- Rear/selfie switching
- Film and capture controls available without covering the frame

---

## Scene-to-film response

The current rendering engine shapes the normalized scene before material rendering:

- a middle-grey-anchored curve lifts deep shadows slightly and compresses source highlights;
- red-dominant materials retain local texture and taper saturation only in highlights;
- a hue-preserving final shoulder prevents hard channel clipping;
- sky classification explicitly excludes foliage, reduces clear-blue noise, and preserves cloud structure.

This improves difficult Standard captures while remaining compatible with the larger source range supplied by Computational HDR and True RAW HDR. It cannot reconstruct detail already clipped by the sensor or phone ISP.

---

## Launcher artwork

The launcher uses the supplied dark-background Spectral Camera artwork directly as density-specific PNG files.

- The normal launcher icon preserves the full 1536×1536 composition without zooming or adaptive foreground cropping.
- The round icon uses the same artwork at 88% scale on the matching dark background so the upper-left viewfinder remains visible inside circular masks.
- CI validates PNG signatures, dimensions, CRCs, manifest references, and the packaged APK resources.
- The previously corrupted WebP and temporary adaptive-icon resources are not part of the active launcher path.

---

## Commercial position

Spectral Camera is not intended to be a novelty “infrared filter app.”

It is being built as a **professional computational camera tool** for:
- artists
- photographers
- visual researchers
- commercial image-makers who want repeatable simulated IR results on Android

The goal is consistency, control, and output quality.

---

## Limitations

Current honest limitations include:

- simulated IR only unless external hardware is used
- no claim of true IR sensor capture
- JPEG HDR still depends partly on phone ISP behavior
- RAW HDR quality depends on hardware support
- severe optical flare or clipped sunlight cannot be fully reconstructed from clipped Standard JPEG input

---

## Development direction

Current priorities:

- improve scene consistency against premium film apps
- preserve realistic red and foliage texture
- improve highlight behavior in severe backlight
- keep sky gradients smooth and clean
- continue validating against real outdoor scenes, not synthetic examples

---

## License / usage

Repository under active development.

Public release / store packaging should describe outputs as **simulated infrared / Aerochrome-style renderings** unless true external spectral hardware is connected.

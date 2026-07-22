# Spectral Camera

Professional Android camera for **simulated infrared and Aerochrome-style photography**.

Built for photographers, artists, and researchers who want consistent, film-like IR rendering — not gimmicky filters.

---

## Current version

**1.19.0 (versionCode 47)**  
Status: Active development

This version introduces:

- Pro output pipeline (Full / HQ 1080 / Fast 1080)
- Computational HDR (JPEG)
- True RAW HDR (sensor-linear merge)
- Ultra HDR export (Android 14+)
- Double Exposure (film-style)
- Full focus system (AF + manual + infinity)
- Compact, production-ready Live UI

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

## Core pipeline

```text
Camera (rear or selfie)
↓
Optional HDR / RAW HDR / Double Exposure
↓
Scene-linear merge
↓
Normalization + tone mapping
↓
Synthetic NIR estimation
↓
Film simulation (Rollei / HIE / Aerochrome / etc.)
↓
High-quality export
```

Everything runs **on-device**.

---

## Capture modes

### Standard

Fast, reliable, single-frame capture.

Best for:
- movement
- street photography
- handheld shooting

---

### Computational HDR

Multi-frame JPEG merge with:
- motion protection
- exposure anchoring
- clean highlight recovery

Best for:
- high contrast scenes
- handheld landscapes

---

### True RAW HDR

Sensor-level HDR:
- merges Bayer data before demosaic
- preserves maximum dynamic range
- uses real camera metadata

Best for:
- professional work
- grading
- print

---

### Double Exposure

Two-shot film-style workflow:

1. Capture frame 1
2. Recompose using overlay
3. Capture frame 2
4. Merge in linear light

Best for:
- artistic work
- layered compositions

---

## Focus system

Fully camera-aware. No fake controls.

### Modes

- **Continuous AF** — tracking autofocus
- **Tap & Lock** — focus and hold
- **Macro AF** — close-range focus (if supported)
- **Manual Focus** — direct lens control
- **Infinity** — locked far focus
- **Fixed Focus** — honest fallback

### Key behavior

- Focus and exposure are unified (no resets)
- HDR keeps a single focus plane
- Manual focus uses real lens distance when available

---

## Output quality

### Modes

- **Full Resolution** — maximum quality
- **HQ 1080** — best Full HD output
- **Fast 1080** — real-time performance

### Details

- Exact 1920×1080 output
- High-quality scaling (not naive resize)
- Adaptive GPU-safe rendering

---

## Ultra HDR (Android 14+)

- Backward-compatible JPEG
- Gain map generated AFTER film simulation
- No HDR halos or fake glow

---

## Film systems

### Monochrome IR

- Rollei Infrared 400
- Kodak HIE
- Ilford SFX
- Fine-grain / vintage variants

### Aerochrome

- Classic
- Soft
- Dense
- Gold
- Faded

All based on **film behavior**, not presets.

---

## Live UI (new)

Designed for real use:

- No redundant top controls
- Presets integrated with Exposure + Focus
- Compact header (more screen for framing)
- Bottom navigation handles gallery + hardware

---

## Files

Saved to:

```
DCIM/SpectralCamera
```

Includes:

- processed images
- HDR outputs
- RAW DNG (optional)
- Double Exposure sources

Metadata records:
- preset
- capture mode
- focus mode

---

## Limitations

- IR is simulated (not real sensor IR)
- RAW HDR uses simple demosaic (for now)
- HDR alignment is global (not optical flow)
- focus capabilities depend on device

---

## Build

```bash
./gradlew assembleDebug
```

Release builds require signing keys.

---

## Positioning

This is not a filter app.

It is a **computational photography tool** for:

- IR simulation
- Aerochrome aesthetics
- experimental imaging
- artistic production

---

## Next directions

- RAW-developed pipeline (no bitmap stage)
- focus peaking
- better demosaic
- optical-flow HDR
- external IR sensor support

---

## License

Private / in development

---

Spectral Camera — built for people who care about the image.
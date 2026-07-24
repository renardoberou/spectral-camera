# Spectral Camera

Professional Android camera for **simulated infrared and Aerochrome-style photography**.

Built for photographers, artists, and researchers who want consistent, film-like IR rendering — not gimmicky filters.

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_spectral_camera.webp" alt="Spectral Camera canonical analog-camera icon" width="180" />
</p>

---

## Current version

**1.20.3 (versionCode 51)**  
Status: Active development

This version introduces and stabilizes:

- Pro output pipeline (Full / HQ 1080 / Fast 1080)
- Computational HDR (JPEG)
- True RAW HDR (sensor-linear merge)
- Ultra HDR export (Android 14+)
- Double Exposure (film-style)
- Full focus system (AF + manual + infinity)
- Compact, production-ready Live UI
- Expanded monochrome IR stock personality separation
- Canonical analog-camera launcher icon approved as the app identity

---

## Canonical icon

The official Spectral Camera launcher identity is now the analog-camera artwork stored at:

```text
app/src/main/res/mipmap-xxxhdpi/ic_spectral_camera.webp
app/src/main/res/mipmap-xxxhdpi/ic_spectral_camera_round.webp
```

The manifest points directly at these canonical resources. Do not restore the temporary `ic_launcher_119x` resources, generic robot placeholders, or unrelated lens-vector experiments.

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

## Film stocks

### Monochrome Infrared (6 stocks)

- **Rollei Infrared 400** — the reference IR look. Fine grain, elegant contrast, controlled halation. Restrained, neutral.
- **Kodak HIE** — high-contrast drama. Deep toe, strongest bloom. The famous ethereal glow. Coarse grain.
- **Ilford SFX 200** — gentler extended-red response. Grey-acetate base for halation protection. Minimal glow, smoother tonality.
- **Moderate IR** — broad default between Rollei (restrained) and HIE (dramatic). Medium grain, medium everything.
- **Fine-Grain Infrared** — neutral, print-friendly. Minimal drama. The cleanest IR stock. Finest grain.
- **Soft Vintage IR** — print-oriented, romantic. Soft toe, low ceiling, milky highlights, wide halation. Lifted blacks. Coarser grain.

### Aerochrome (5 looks)

False-color rendering of the classic Kodak Aerochrome IR film:

- **Classic** — the reference Aerochrome grade this app was built on.
- **Soft** — gentler contrast, pastel magenta, paler sky, minimal glow. The everyday member.
- **Dense** — punchier contrast, deeper cyan sky, dramatic halation. The hero-shot grade.
- **Gold** — orange-filter EIR. Warmer foliage, teal sky.
- **Faded** — desaturated, lifted blacks, warm cast, hazy pale sky. Aged-print character.

### Classic Film (3 stocks)

Standard photographic stocks rendered with their real film response:

- **Kodak Ektar 100** — finest-grain color negative. Vivid reds/blues. Faithful skin. Punchy clean contrast. Whisper grain.
- **CineStill 800T** — tungsten-balanced vision film. Signature RED halation around lights. Cool/teal daylight. Lifted cinematic blacks.
- **Kodak Tri-X 400** — the photojournalism classic. Punchy panchromatic curve. Rich textured blacks. Honest gritty grain.

### Grain

Every stock carries an always-on baseline grain (`grainBase`, per FilmLookLibrary) so stock personality is visible at default settings — real film is never grainless. The **Grain** slider (0–1, independent of which stock is selected) adds on top.

#### Grain characteristics by stock family

**Monochrome IR** — grain density responds to exposure (strongest in midtones, tapering toward deep shadow and bright highlight), matching the visibility curve in a print and the behavior of real silver-halide emulsion. Per-stock grain personalities: HIE and Soft Vintage read visibly coarser, Fine-Grain reads tighter.

**Aerochrome & Classic Film** — grain density is also exposure-responsive (2026-07-23e). Baseline grain levels are calibrated per stock; the Grain slider modulates on top.

**Chroma grain (2026-07-23f)** — color-negative and Aerochrome stocks carry independent per-channel noise on top of the shared structural grain, rendering the dye-cloud speckle of real color film. Mono stocks (Tri-X in the Classic Film preset range) automatically revert to scalar-only grain via the existing `monoMix` uniform, so no chroma speckle appears on B&W stock.

**Clump irregularity (2026-07-23f)** — grain strength clusters into irregular patches rather than reading as a uniform texture everywhere, closer to the Boolean/Poisson-disk crystal-cluster model real film follows.

See `docs/PLAN_2026-07-23d_grain-quality-upgrade.md` and `docs/VALIDATION.md` for technical implementation detail and verification methodology.

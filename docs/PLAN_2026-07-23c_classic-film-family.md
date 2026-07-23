# Plan: Classic Film family — Ektar 100, CineStill 800T, Tri-X 400

**Date:** 2026-07-23c · **Status:** EXECUTED this cycle
**Goal:** make Spectral Camera a daily-driver: three canonical non-IR
emulsions as a third look family (`LookFamily.STANDARD_FILM`, uPreset 11-13),
each the best emulation of its stock. Strictly additive: zero changes to the
monoLook/aeroLook IR engines.

## Research basis (canonical stock characteristics)

**Kodak Ektar 100** — marketed and universally regarded as the world's
finest-grain colour negative. Signature: vivid but faithful saturation with
a documented red/blue emphasis, punchy clean contrast unusual for a
negative, near-invisible grain, crisp acutance, clean neutral highlights.
Emulation: sat 1.30 with redBias 1.15 / blueBias 1.10, contrast 0.60,
whisper grain (clump 0.45, base 0.02), acutance +0.18, minimal warm-neutral
halation (modern anti-halation), warmth +0.045.

**CineStill 800T** — Kodak Vision3 500T motion stock with the remjet
anti-halation backing REMOVED for C-41 processing: the defining consequence
is strong RED halation around lights and speculars. Tungsten-balanced
(3200K): in daylight it renders cool with teal-leaning shadows. Cine
negative tonality: lifted blacks, gentle low-contrast curve, soft shoulder.
Emulation: haloTint (1.0, 0.20, 0.14) at threshold 0.80 with the family's
strongest rings (0.55/0.42); warmth -0.10; tealShadows 0.55; contrast 0.36;
toeLift 0.035; grain clump 1.05 / base 0.14 (ISO 800 character).

**Kodak Tri-X 400** — the photojournalism standard for 70 years. Signature:
punchy panchromatic gradation with slightly red-favouring sensitivity, rich
but textured blacks, forgiving highlight shoulder, honest pronounced grain.
Emulation: monoMix 1 with pan mix (0.30R, 0.50G, 0.20B); contrast 0.68;
toeLift 0.012 (blacks breathe); ceiling 0.958; grain clump 1.35 / base 0.26
(the grittiest in the app, as it should be); acutance +0.12; minimal
neutral halation.

## Architecture

- `StandardFilmLook` data class + library in core/FilmLook.kt (all dials).
- New GLSL `standardFilm()` engine: WB character -> teal shadow split-tone
  -> panchromatic mono mix -> contrast s-curve -> lifted-toe..shoulder
  remap -> per-channel-biased saturation -> dye-coloured halation
  (reuses `halationEnergy` with a per-stock `uHaloTint`).
- Uniforms uStdTone/uStdTone2/uStdTone3/uHaloTint; dispatch `uPreset > 10.5`
  ahead of the Aerochrome fall-through; drawQuad `STANDARD_FILM` branch;
  preset sheet gains the "Classic Film" section.

## Validation additions (VALIDATION.md)

| # | Scene | Expected | Unacceptable |
|---|---|---|---|
| S1 | Ektar: saturated garden + skin | Vivid reds/blues, faithful skin (no orange shift), invisible grain | Neon skin; visible grain; muddy contrast |
| S2 | CineStill: night lights / daylight street | RED halos around lights; daylight cool with teal shadows; lifted blacks | Neutral/white halos; warm daylight; crushed blacks |
| S3 | Tri-X: street scene | Punchy pan B&W, textured blacks, gritty visible grain | Flat grey mush; silky grain; blocked shadows |

Device confirmation per protocol: shoot all three on one scene + a night
scene for the CineStill halos.

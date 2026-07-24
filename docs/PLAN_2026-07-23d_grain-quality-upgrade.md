# Plan: grain quality upgrade — matching/beating commercial grain apps

**Date:** 2026-07-23 (fourth cycle of the day) · **Status:** step 1 of 4 executed this cycle
**Trigger:** competitive research against "Grain" (Film Grain Editor) and adjacent apps
(GrainLab, StochasticGrain, Dehancer) requested directly, following up on the
2026-07-21b baseline-grain feature. Verification method matches this repo's convention:
numpy port of the exact GLSL math, validated against a real captured photo before
touching production code; no Android SDK exists in this environment for on-device
rendering.

---

## 0. Competitive research summary

"Grain" (Film Grain Editor, App Store) does not publish its internals; its own
listing advertises: preset stocks, intensity, **grain size**, **highlight protection**,
compare toggle, full-res export. Cross-referencing adjacent grain-focused apps
(GrainLab's "cluster controls" / luminosity curve, StochasticGrain's explicit citation
of Newson/Delon/Galerne's Boolean-model grain paper with "shadows show more grain,
highlights stay cleaner") gives a consistent picture of what separates a *good* result
from flat noise-overlay tools:

1. **Exposure-dependent density** — grain visibly denser in shadow/midtone, rolling off
   toward highlights ("highlight protection" is this, named as a feature).
2. **Per-channel (chroma) grain on color stock** — independent-but-correlated noise per
   RGB channel, not one scalar added identically to all three.
3. **Irregular clustering** — Boolean/Poisson-disk-like clump sizes, not uniform-texture
   octave noise.

Our shader already does resolution-independent grain scale and per-capture reseeding
correctly (both real strengths, kept as-is). The gap is specifically the three items
above, and only item 1 is even partially implemented today (mono presets only).

## 1. Baseline verification (this cycle — DONE)

**Method:** numpy port of `grainHash`/`valueNoise`/`filmGrain` and the grain-application
block from `SpectralGlPipeline.kt` (`core/gl/SpectralGlPipeline.kt`, "film grain"
section), run against a real captured reference photo
(`docs/assets/grain-baseline-2026-07-23/reference_photo.jpg` — the blue-tiled pool
capture from this session's batch, chosen for its wide luma range: near-black shadow
tile bottom-left through saturated mid-blue water to a bright sky reflection).

Script: `docs/assets/grain-baseline-2026-07-23/grain_port.py`. Full output:
`docs/assets/grain-baseline-2026-07-23/BASELINE_REPORT.md`.

**Confirmed numerically, on the real photo's own pixel distribution** (Grain slider
at 0, isolating each stock's `grainBase` per the 2026-07-21b convention):

| Preset family | Mean grain amplitude across luma buckets (0.0-0.15 → 0.85-1.0) |
|---|---|
| Rollei (mono) | 0.00098 → 0.00325 → 0.00357 → 0.00138 → 0.00042 (peaked, tapers both ends) |
| HIE (mono) | 0.00282 → 0.00937 → 0.01027 → 0.00397 → 0.00120 (peaked, tapers both ends) |
| Ektar 100 (color) | 0.00054 → 0.00054 → 0.00054 → 0.00054 → 0.00054 (**perfectly flat**) |
| CineStill 800T (color) | 0.00630 → 0.00630 → 0.00630 → 0.00630 → 0.00630 (**perfectly flat**) |
| Tri-X 400 (classic mono) | 0.01346 across every bucket incl. deepest shadow and brightest highlight (**perfectly flat**) |

This is exact confirmation, on real pixels, of the gap flagged in the prior report:
`uPreset <= 5` gates the density-weighting branch, so every non-mono-IR preset
(Aerochrome ×5, Classic Film ×3 — including Tri-X, which is itself a mono stock but
routed through the flat "classic" branch, not the density-weighted "mono IR" branch)
gets identical grain amplitude in the deepest shadow and the brightest highlight. This
directly explains two items flagged from this session's captures: dead-flat pool
blacks showing no grain texture, and no rolloff protecting near-clipped highlights.

**Regression-safety note:** this script changes nothing in production code — it is a
read-only numeric mirror of today's shipped math, so there is nothing to regress. It
becomes the "before" side of the diff for step 2.

## 2. Remaining implementation (next cycles, in priority order)

### Step 2 — universal exposure-dependent density
Extend the density-weighted branch to every preset family, not just `uPreset <= 5`.
Needs a per-family curve center/width (Aerochrome skies/chrome must stay clean; classic
color midtones/skin should get full grain) — new fields alongside `grainBias`/
`grainBase`/`grainClump` in `MonoIRLook`/`AerochromeLook`/`StandardFilmLook`, defaulting
to values that reproduce current mono behavior exactly (regression-safe: mono presets
must be provably byte-identical before/after, verified the same way Classic/Gold were
proven identical in the 2026-07-21b density-dial cycle).
Verification: re-run `grain_port.py` with the new formula; the "flat across every
bucket" rows above must become peaked/tapered like the mono rows, on the same
reference photo.

### Step 3 — per-channel grain for color stocks
Second/third `filmGrain()` sample per pixel, offset in hash-space per channel, only for
non-mono presets (mono must stay scalar — chroma grain on B&W stock is wrong).
Scaled down (~30-40% of the luma-channel amplitude) so it reads as chroma grain, not
RGB misregistration. Cheap: 3 noise taps instead of 1 for color presets only.

### Step 4 — clump irregularity
Add a third, lower-frequency `valueNoise()` octave used as a clump-density multiplier
(not summed into the noise value) so grain intensity itself clusters into irregular
blobs, closer to Boolean-model grain, instead of the current uniform-texture two-octave
sum. One extra noise call, reused.

Each step gets its own numpy verification pass against this cycle's reference photo
before touching `SpectralGlPipeline.kt`, per this repo's standing convention.

## 3. Findings from this session's capture batch (separate follow-up tickets, not grain)

Flagged during capture review, logged here for VALIDATION.md but explicitly **out of
scope** for the grain cycle:

- **Sky banding** in flat overcast-cloud regions before grain/dither masks it.
- **Red-channel clipping** on Aerochrome foliage crops — large leaf-red areas
  posterized at the top of the red channel; this also means grain has near-zero luma
  variance to key against in those regions once step 2 ships, so it's worth revisiting
  together.
- **Colored fringing at branch/sky silhouette edges** in backlit foliage crops — most
  likely halation/bloom bleeding into the classification chroma-denoise taps, not the
  grain stage itself.

## 4. Definition of done (this cycle)

- [x] Exact numpy port of `grainHash`/`valueNoise`/`filmGrain` + grain-application block.
- [x] Validated against a real captured photo with a wide luma range (not a synthetic
      gradient) — confirms today's shipped mono-vs-flat density gap numerically, on
      real pixels, not just by code inspection.
- [x] Baseline artifacts committed for the next cycle's before/after diff:
      `reference_photo.jpg`, `grain_port.py`, `BASELINE_REPORT.md`, per-look
      `out_*.png` renders.
- [x] Full remaining plan (steps 2-4) written up above.
- [ ] Steps 2-4 (universal density, per-channel grain, clump irregularity) — next
      cycles, not started.
- [ ] Device re-shoot / on-device confirmation — required before shipping any of steps
      2-4, consistent with this repo's verification standard for shader changes.

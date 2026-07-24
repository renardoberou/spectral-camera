# Plan: calibrating grain amplitude against real emulsion data (2026-07-24, second pass)

**Trigger:** after the step-5 defect fixes shipped, a direct question: the decision to
leave Ektar 100 / Fine-Grain IR "dither-dominated" and Rollei's amplitude untouched was
framed as a product-taste call. It wasn't supposed to be. The actual bar is fidelity to
the real emulsion, which is measurable, not a matter of taste.

This cycle replaces that judgment call with sourced data: official Kodak, Ilford, and
Rollei/MACO technical publications.

## Sourced data

**Group A — diffuse RMS granularity, same measurement convention (48-micrometre
aperture, net diffuse density 1.0), directly comparable across manufacturers:**

| Stock | RMS granularity | Source |
|---|---|---|
| Rollei Infrared 400 | 11 | rolleianalog.com official data sheet |
| Kodak Tri-X 400 | 17 ("fine") | Kodak Publication F-4017 |
| Kodak HIE | 18 ("fine") | Kodak Publication F-13 |
| Ilford SFX 200 | **not published** | confirmed absent from Ilford's own technical data sheet after full fetch — a real absence, not a research gap. Left unchanged. |

**Group B — Kodak Print Grain Index (PGI), 135 format, same convention, explicitly NOT
comparable to Group A (Kodak's own disclaimer: "a different scale which cannot be
compared to rms granularity"):**

| Stock | 4×6 (4.4×) | 8×10 (8.8×) | 16×20 (17.8×) | Source |
|---|---|---|---|---|
| Ektar 100 | <25 | 38 | 66 | Kodak E-4046 |
| Portra 160NC | 30 | 52 | 81 | Kodak E-58 |
| Supra 100 | 27 | 49 | 78 | Kodak E-58 |

(25 = the visual threshold for graininess; below it, PGI is reported as "less than 25.")

**Qualitative, sourced:** KODAK VISION3 500T (CineStill 800T's base stock) uses "Dye
Layering Technology," documented as specifically reducing grain in shadow regions for
improved shadow signal-to-noise — the opposite direction from a uniform shadow floor.

## What the data actually says, and what it changes

**Rollei IR 400 was ~3x too quiet relative to Tri-X/HIE.** Real ratios:
Rollei:Tri-X = 11:17 = 0.647, Rollei:HIE = 11:18 = 0.611 — Rollei is moderately finer,
not dramatically so. The shipped app had Rollei at 0.10 vs Tri-X's 0.299 and HIE's
0.288 (`grainBase × grainBias`) — ratios of 0.334 and 0.347, roughly half what the real
film supports. **Fixed:** `grainBase` 0.10 → 0.19, landing within a few percent of both
real ratios (0.635 vs 0.647, 0.660 vs 0.611).

**Ektar 100 was rendering as functionally zero grain, which contradicts Kodak's own
data.** Real Ektar crosses the PGI=25 visibility threshold at 8×10 print (PGI 38) and is
clearly above it at 16×20 (PGI 66) — subtly but genuinely visible at normal print sizes,
not literally grainless. The shipped app's peak amplitude never exceeded 0.27 LSB at any
tone — always at or under the anti-banding dither. "Whisper grain" had become "no grain."
PGI and shader LSB units aren't on a convertible scale, so there's no precise derived
target here — this part is a **reasoned judgment call, not a calculation** like Rollei's.
**Fixed:** `grainBase` 0.02 → 0.05, clearing the dither floor by a real margin for the
first time (peak amplitude 0.27 → 0.67 LSB) while staying under half of corrected
Rollei — preserving Ektar as clearly, unambiguously the subtlest of the six real stocks,
consistent with its ~25-30% PGI gap under same-class Kodak color negative film.

**CineStill 800T's shadow floor was pointed the wrong direction for that specific
stock.** The universal shadow-floor fix (previous cycle) boosts grain in deep shadow
uniformly. But real Vision3 500T is specifically engineered via Dye Layering Technology
to *suppress* shadow grain — the opposite of what a uniform floor does. No source gives
a precise magnitude, so this is qualitative-direction-sourced rather than a derived
number. **Fixed:** added a per-look `shadowFloorScale` field (default 1.0, i.e. every
other stock is bit-identical to before), set to 0.35 for CineStill only.

**Ilford SFX 200 is unchanged.** Ilford's official technical data sheet, fetched in
full, simply does not publish an RMS granularity figure for this stock. Rather than
invent one, it's left alone.

**Fine-Grain IR, Moderate IR, Soft Vintage IR are unchanged.** These are generic/composite
stocks, not modeled on one named real emulsion — there is no single real film to check
them against, and I'm not pretending otherwise.

## Implementation

`uStdTone3.z` (previously an unused, always-zero component — comment said "-, -") is
repurposed as a per-look shadow-floor scale multiplier, read uniformly by the shader:

```glsl
densityWeight = max(densityWeight, 0.62 * uStdTone3.z * shadowLift);
```

Mono-IR and Aerochrome branches now pass `1.0` in that slot instead of `0.0` — an
explicit "no change" rather than an accidental zero. STANDARD_FILM passes
`look.shadowFloorScale`, which defaults to `1.0f` for every stock except CineStill's
explicit `0.35f`. Because `shadowLift` is itself exactly `0` for luma ≥ 0.34 regardless
of the scale multiplier, the existing highlight-protection bit-identity proof is
untouched by this change — `max(gaussian, 0.62 × scale × 0) = gaussian` for any scale.

## Verification

Full acceptance suite (`step5_final_updated.py`) re-run against the corrected values:

- **Q1 (chroma reads as film speckle):** unchanged pass — Ektar and CineStill both
  still measure luma leak ~1e-9, chroma coarser than luma (0.72-0.94x). Mono stocks
  still exactly zero chroma energy.
- **Q2 (clump blotching):** unchanged pass even at Rollei's near-doubled amplitude —
  low-frequency leakage +0.0013 LSB, two orders of magnitude under the 0.30 threshold.
- **Q3 (shadow readability):** Rollei's grain share rose from 0.73-0.81 to 0.98-1.05
  (now essentially all-grain in shadow, matching its corrected real-world strength).
  Ektar rose from 0.22-0.27 to 0.36-0.39 (present and measurable, still clearly the
  quietest alongside Fine-Grain's 0.30-0.34). CineStill's shadow share at the deepest
  tones (0.03/0.08) fell from 0.95/0.90 to 0.51/0.58 — confirms the reduced shadow
  floor is doing exactly what it's sourced to do, while the 0.15 tone (further from the
  floor's influence) is unaffected at 0.85 both before and after.
- **Highlight protection:** re-confirmed bit-identical for every stock at luma ≥ 0.34,
  by construction (see above), not just by re-running the old numeric check.

## Honesty about confidence levels

These three fixes are not all the same kind of claim, and shouldn't be presented as if
they were:

- **Rollei** is a precise, sourced, same-scale ratio calculation. High confidence.
- **Ektar** is sourced (real Ektar does cross the visibility threshold) but the exact
  target amplitude is a reasoned judgment call, because PGI and shader LSB units don't
  convert. Medium confidence on the direction and rough magnitude, not the exact number.
- **CineStill** is sourced on direction only (DLT suppresses shadow grain) with no
  magnitude given anywhere I found. The 0.35 scale is defensible, not derived.

Still requires the device for the same reason as before: whether the corrected
amplitudes look right on real scenes at real viewing sizes.

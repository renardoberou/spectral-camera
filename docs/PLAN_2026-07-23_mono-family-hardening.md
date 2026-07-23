# Plan: monochrome family hardening (sky blob, black canopy, void pool)

**Date:** 2026-07-23 · **Status:** executed this cycle
**Trigger:** first full 6-preset real-device capture of the Monochrome IR family
(cloudy midday courtyard: pool + dense trees + towers + overcast sky), reviewed
against `docs/VALIDATION.md`. Three of the failure modes flagged there were
confirmed in the wild; all three are fixed this cycle. Verification uses the
established method: exact numpy port of the mono GLSL math run on the actual
captured reference photo, before/after, all six stocks (no Android SDK in this
environment).

---

## 1. Diagnosis (from the 6 device renders + colour reference)

| # | Symptom on device | Root cause in `SpectralGlPipeline.kt` |
|---|---|---|
| 1 | Hard-edged dark smudges/blobs in the overcast sky, worst near cloud/blue-gap transitions (the "sky blob" already logged 2026-07-21c on the dawn scene, now confirmed on a second scene) | `skyChroma = smoothstep(0.03, 0.11, ...)` in `irLuminance()` is near-binary: it snaps 0→1 across its decision boundary and imprints that boundary as a tonal edge. `skyHazy`'s `b-g` edge (0.02..0.09) also part-fires on *neutral* grey cloud, stamping mild grey smudges. Suppression had no cap, so fully classified sky fell to near-void. |
| 2 | Dense background canopy crushed to posterized black masses; sunlit foliage fine | Shaded canopy picks up a blue skylight cast and desaturates: it fails the `ngM-nrM` / `ngM-nbM` gates and the `chromaDistM >= 0.035` saturation gate zeroes what remains → zero Wood lift → `irBase ≈ 0.06` → Zone 0. Gate flicker at the boundary is the posterized edge. |
| 3 | Pool renders as a dead black void (M2 failure) | The v1.14 water floor keys on **source** darkness (`smoothLuma < 0.14`). A bright blue pool is dark only **after** the shader suppresses it, so the floor/sheen never applied. |

## 2. Fixes

1. **Sky (blob killer)** — `skyChroma` upper edge 0.11 → **0.20** (proportional
   ramp, weight 0.8 → 0.9); `skyHazy` requires decisive blueness (`b-g` edge
   0.04..0.12, weight 0.5 → 0.35) so neutral overcast cloud cannot fire; total
   suppression capped at **0.86**, so classified sky keeps a Zone I–II floor,
   mirroring Aerochrome's pale-sky path. The cap alone bounds the dawn-scene
   22.8× luma asymmetry to a smooth ≤ ~7× gradient.
2. **Shadow canopy** — new `shadowVegM` branch in the mono foliage classifier:
   green-over-red dominance with a mild tolerated blue excess, dark regions
   only, bypassing the saturation gate (`foliage = max(foliage, shadowVegM * 0.75)`).
   Sky (strong blue excess) and neutral facades (no green margin) stay excluded.
3. **Water life** — `irLuminance()` now returns its applied suppression
   (`out float suppressOut`); the floor+sheen stage keys on
   `max(sourceDarkness, suppress * lowInFrame)`, so pools the shader itself
   darkens keep Zone I–II tone and their specular ripple.

## 3. Verification (numpy port, real reference photo, all 6 stocks)

Sky-ROI spatial std (artifact energy; source std 0.032), treeline and pool
mean luma, before → after:

| Stock | sky std | treeline | pool |
|---|---|---|---|
| Rollei | 0.0119 → 0.0108 | 0.659 → 0.679 | 0.272 → 0.393 |
| HIE | 0.0119 → 0.0107 | 0.687 → 0.712 | 0.231 → 0.382 |
| SFX | 0.0106 → 0.0097 | 0.662 → 0.678 | 0.360 → 0.451 |
| Moderate | 0.0104 → 0.0095 | 0.684 → 0.704 | 0.345 → 0.448 |
| Fine-Grain | 0.0101 → 0.0094 | 0.679 → 0.695 | 0.412 → 0.490 |
| Soft Vintage | 0.0106 → 0.0098 | 0.596 → 0.612 | 0.341 → 0.411 |

- Visual bands (source / before / after): sky smudges reduced, black canopy
  holes fill with structured mid-grey, pool stripe pattern stays alive at
  Zone II–III instead of void. Proof render: `mono_fix_proof_grid.png`
  (attached to session).
- Stock ordering preserved everywhere (HIE darkest water/sky, Fine-Grain
  lightest) — personality intact, no classification flips.
- Buildings, sunlit foliage, and bright-cloud regions byte-stable in the port.

## 4. Not done this cycle

- Device re-shoot with the fixed shader (requires the physical device); the
  dawn scene (sky-blob origin) and this courtyard scene are the two regression
  references to re-run.
- Stock differentiation on overcast scenes remains modest — largely physical
  (halation/sky dials have little to bite on under flat light); revisit only if
  the re-shoot still reads interchangeable.

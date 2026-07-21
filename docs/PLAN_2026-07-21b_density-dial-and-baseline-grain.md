# Plan: dark-path density dial + always-on baseline grain

**Date:** 2026-07-21 (second cycle of the day) · **Status:** executed this cycle
**Trigger:** executes the top two next-cycle items from
`docs/PLAN_2026-07-21_commercial-hardening.md` §3, using the same verification method
(numpy port of the exact GLSL math, validated on the real captured photo before touching
production code; no Android SDK exists in this environment for on-device rendering).

---

## 1. Fix: per-look density dial on the dark blue paths (§3 item 1)

**Problem (from real-device evidence):** the dusk window in the validation photo rendered
nearly identically across all five Aerochrome looks. Root cause: that region is not
classified as sky (`skyMask ≈ 0` there), so it renders through the `vividBlue` and
`plainBlue`/`shadowCol` paths — which had **no per-look parameter at all**.

**Fix:** `skyDepthBoost` now doubles as the family density dial on those paths:
`densityScale = clamp(2.0 - skyDepthBoost, 0.6, 1.4)` multiplies `blueOut` (vivid
water/glass) and `shadowCol` (skylight shadow). A plain multiply is correct here — unlike
the near-black `deepCol` sky ramp (which needed last cycle's power curve), these paths sit
at luma ~0.1–0.4 where a 0.75–1.30× multiply is clearly visible at 8 bits.

**Verified on the real photo** (window-crop mean luma, per look):

| Look | Before | After |
|---|---|---|
| Classic | 0.0155 | 0.0155 (identity, by design) |
| Soft | 0.0198 | 0.0229 |
| Dense | 0.0119 | 0.0084 |
| Gold | 0.0147 | 0.0147 (identity, by design) |
| Faded | 0.0204 | 0.0269 |

Family luma spread on the previously-uniform region more than doubled (0.0085 → 0.0185),
with Classic and Gold provably byte-identical to their shipped rendering. The wall patch
(last cycle's neon-wall fix) was re-measured as a regression check: unchanged.
Proof render: `window_density_before_after.png` (attached to session).

## 2. Product decision: always-on per-stock baseline grain (§3 item 2)

**Decision: option (a)** — each stock now carries a small always-on baseline grain
(`grainBase`, new field in `MonoIRLook`/`AerochromeLook`, new `uGrainBase` uniform).
The shader's grain stage now keys on `uGrain + uGrainBase`, so the user's Grain slider
adds on top of the stock's own character rather than being the only source of grain.

**Rationale:** real film is never grainless, per-stock grain is one of the strongest
personality cues in the product's own master plan, and at the previous default
(Grain = 0) every `grainBias`/`grainClump` value in `FilmLookLibrary` was dead weight —
stock personality was invisible on a default install. Baselines are deliberately subtle
(0.05 Fine-Grain … 0.18 HIE; Aerochrome 0.06–0.14) — texture, not noise. This
intentionally retires the earlier "default Off = perfectly clean output" behaviour; the
README wording was updated to match. A future "clean export" toggle can restore a
zero-grain path if users ask for it.

**Verified:** exact-port render of the shader grain stage (two-octave value noise, clump
scale, mono density weighting) for all 11 stocks at default settings:
`grain_personality_strip.png` — HIE and Soft Vintage read clearly coarser, Fine-Grain
near-clean, mono vs. Aerochrome amplitudes distinct.

## 3. Not done this cycle (honest scope)

- **Device re-shoot** of the validation scenes (§3 item 3) and the missing 6th mono
  preset capture (§3 item 4) — requires the physical device; unchanged status.
- **`warmth` base-formula re-examination** (§3 item 5) — deliberately untouched; it
  interacts with every non-foliage surface and should not ride along in a cycle whose
  changes were verified on one scene.
- **Observed but out of scope:** on the Gold/Faded looks, dark foliage *reflected in the
  window glass* renders green (the veg classifier fires on the reflection; gold's
  `veg`-keyed green push amplifies it). Pre-existing behaviour, visible in both before and
  after rows of the proof grid. Logged in VALIDATION.md as a new watch item.

## 4. Definition of done

- [x] The previously dial-less dark paths respond to the family density dial, verified
      numerically and visually on the real photo, with Classic/Gold provably unchanged.
- [x] Stock grain personality visible at default settings; per-stock values in the look
      table are no longer dead weight; README updated to match the new behaviour.
- [x] Regression checks: neon-wall fix intact; Classic/Gold identity intact.
- [x] Verifiable outputs produced this run: `window_density_before_after.png`,
      `grain_personality_strip.png`.

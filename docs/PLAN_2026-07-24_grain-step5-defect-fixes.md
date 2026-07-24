# Plan: grain step 5 — resolving the device-verification questions by measurement

**Date:** 2026-07-24 · **Status:** executed
**Trigger:** step 5 of `docs/PLAN_2026-07-23d_grain-quality-upgrade.md` — the three
questions that were parked as "needs on-device confirmation":

1. does the new chroma grain read as film speckle, or as chromatic aberration?
2. does the clump irregularity overshoot into blotching on mono stocks?
3. does the density curve actually make shadow grain readable?

---

## 0. Scope honesty — what this cycle can and cannot settle

There is no camera and no Android device in this environment, so **this cycle does not
and cannot replace a device re-shoot**. What it does instead is turn all three
questions into measurements that *can* be made here — on the real device captures
already in hand, and on exact ports of the shipped shader math — and then fixes the
defects those measurements exposed.

Three of the questions turned out to be answerable numerically, and answering them
found **four real defects** that a visual check would likely have described only as
"the grain still doesn't look right". Those are fixed here. What still genuinely
requires the device is confirming that the corrected math *looks* right on real scenes
at real viewing sizes — the final aesthetic judgement, not the correctness questions.

## 1. Measurement of the real captures (what the shipped build was actually doing)

New harness: `docs/assets/grain-verification-2026-07-24/grain_analysis.py`
(highpass RMS binned by local tone, flat-patch isolation, per-channel decorrelation,
radial power spectra, black-point statistics).

Run on the 2026-07-23 device batch. Two readings stand out, both on the mono IR pool
capture:

- **52% of the frame sits in the deepest tone bucket (< 0.08)**, where measured
  highpass energy is **0.0157 vs 0.0597 at midtone — 3.8x weaker**.
- **31.8% of all pixels are at or below 2/255.**

Caveat recorded honestly: the captures are JPEG/WebP, and block DCT quantisation
attenuates precisely the low-amplitude high-frequency signal grain lives in, so
absolute RMS here understates what the shader emitted. Relative comparisons between
regions of the same frame, and clipping statistics, survive compression and are the
trustworthy readings.

## 2. Root cause — it was not the density curve alone

The first hypothesis (black-point clamping half-wave-rectifying the grain) was tested
and **rejected**: at luma 0.05 the grain survives the clamp intact. The actual causes,
both measured:

**(a) Sub-LSB quantisation.** Grain excursion in deep shadow falls below half an 8-bit
LSB and rounds away entirely. Rollei at default renders **0.38-0.49 LSB** of grain
amplitude below luma 0.05; Ektar renders **0.05-0.27 LSB across its entire tonal
range**.

**(b) The dither was louder than the grain.** The sub-LSB IGN dither runs *after* the
grain stage at a fixed ±0.77 LSB (std ≈ 0.49 LSB). Measured against the per-stock grain
amplitudes, the grain/dither texture ratio on the shipped build was **0.39-0.97 for
Rollei — the dither was never weaker than the grain** — and 0.13-0.37 for Ektar. The
texture a user saw on the finer stocks was substantially the dither's fixed
screen-space pattern, not the grain's clump structure. No density-curve change fixes
this.

## 3. Defects found and fixed

### D1 — chroma axis was not luma-neutral (proven algebraically, then measured)
Shipped green coefficient `-0.5*(nCr+nCb)` cancels the red term
(0.299 − 0.587·0.5 = +0.006, negligible) but leaves the **blue** term leaking
0.114 − 0.587·0.5 = **−0.180** into luma. The "chroma" term was injecting extra luma
noise equal to **18% of the chroma amplitude**, on an axis biased toward blue.
Solving `dot(vec3(nCr, g, nCb), REC601) = 0` gives the exact coefficients
`g = -0.5094*nCr - 0.1942*nCb`. Measured luma leak after fix: **1.8e-1 → ~1e-8**.

### D2 — chroma grain was FINER than luma grain (this is the "chromatic aberration" answer)
Shipped `cUv = gUv * 1.7` samples the noise at a *higher* rate, i.e. **smaller**
features. Measured spectral centroid: chroma was **1.54x** the luma grain's frequency.
That is backwards on both physical and perceptual grounds — colour-negative dye clouds
are larger than the silver grains that form them, and human chroma acuity is far below
luma acuity. Fine high-frequency colour noise is exactly what reads as colour fringing.
Changed to `gUv / 1.8`; measured centroid now **0.52-0.94x** the luma grain (coarser).

### D3 — deep-shadow density floor
`densityWeight = max(gaussian, 0.62 * smoothstep(0.34, 0.02, luma))`. Lifts only the
deep end; **for luma ≥ 0.34 the expression is bit-identical to the bare Gaussian**, so
highlight protection is exactly preserved (verified across the full range in
`final_verify.py`).

### D4 — grain-aware dither displacement
`grainDitherScale = 1.0 - clamp(grainAmp * 175.3, 0.0, 1.0) * 0.55` applied to the base
IGN dither only. Film grain is itself a dither, so where grain is strong the IGN pass is
redundant and is diluting the grain's structure. Deliberately conservative (55% max
displacement, referenced to 3.2 LSB) because banding lives in smooth *bright* gradients
where the density curve makes grain weakest. **The separate sky dither assist is not
touched at all.**

A first attempt at D4 (85% displacement, 1.6 LSB reference) was **rejected by its own
banding test** before shipping — recorded here because the failure is the reason the
shipped constants are the conservative ones.

## 4. Acceptance results

Full output: `docs/assets/grain-verification-2026-07-24/ACCEPTANCE_REPORT.txt`.

**Q1 — chroma reads as film speckle:** luma leak ~1e-8 (was 18%); chroma feature size
0.70-0.94x luma (coarser, was 1.54x finer). Mono stocks *including Tri-X* measure
exactly zero chroma energy. **Resolved.**

**Q2 — clump blotching on mono:** low-frequency (16x16 block) brightness variation
introduced by the clump mask is **+0.0002 to +0.0033 LSB**, against a 0.30 LSB blotch
threshold — two orders of magnitude below it. **Resolved: no blotching risk.**

**Q3 — shadow readability:** fraction of visible shadow texture that is actually film
grain, at tones 0.03/0.08/0.15:

| Stock | grain share (was ~0.39-0.47 on Rollei before) |
|---|---|
| Rollei | 0.81 / 0.75 / 0.73 |
| HIE | 1.07 / 1.08 / 1.08 |
| Tri-X | 1.06 / 1.07 / 1.09 |
| CineStill | 0.95 / 0.90 / 0.85 |
| Aerochrome Dense | 0.84 / 0.78 / 0.75 |
| Fine-Grain | 0.32 / 0.30 / 0.34 |
| Ektar | 0.23 / 0.22 / 0.27 |

Grain is now the dominant shadow texture on every stock that has meaningful grain.

**Banding regression test** (`DITHER_BANDING_TEST.txt`): ramp quantisation run-lengths
stay at 1.3-2.5 px against an undithered reference of 13-33 px. The rise to 2.5 px on
HIE/Tri-X is coarse-grain spatial correlation, not banding. **No regression.**

## 5. Deliberately NOT changed

- **Ektar and Fine-Grain remain dither-dominated** (grain share 0.22-0.34). This is not
  a bug: `grainBase` 0.02/0.03 is a deliberate product value ("whisper grain", "the
  finest-grain colour negative"). Raising it is a look decision, not a correctness fix,
  and is not made unilaterally here. Flagged for a product call.
- **No film base-fog pedestal.** Physically correct (real film never reaches zero
  density) and it would eliminate the residual true-black clipping, but it lifts every
  black in every preset — a visible look change nobody asked for. Logged as optional.
- **Overall grain amplitude scale.** Rollei's baseline peaks at ±1.1 LSB even at
  midtone. Whether that is "texture, not noise" or simply too quiet is a product
  judgement best made from a device re-shoot, not from here.

## 6. Proof artifacts

- `grain_proof_strip_2026-07-24.png` — 1:1 native-scale render, 7 stocks x 7 tones, so
  grain can be judged visually at true pixel scale without shooting.
- `grain_proof_strip_2026-07-24_amplified8x.png` — same data with deviation from local
  mean amplified 8x; a diagnostic view, not what ships.

## 7. Definition of done

- [x] Measurement harness built and run against the real device capture batch.
- [x] Root cause identified numerically (sub-LSB quantisation + dither masking), with
      the first hypothesis explicitly tested and rejected.
- [x] Four defects found and fixed, each verified before the shader was touched.
- [x] All three device questions answered with measurements, not opinion.
- [x] Banding regression test passes; highlight protection proven bit-identical.
- [x] CI green.
- [ ] **Device re-shoot still required** — for the aesthetic judgement these
      measurements cannot make: whether the corrected grain looks right on real scenes
      at real viewing sizes, and whether the fine-stock amplitude question above wants
      a product decision.

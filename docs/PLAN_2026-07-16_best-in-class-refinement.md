# Plan: best-in-class refinement pass

**Date:** 2026-07-16 · **Status:** P2/P3 executed in v1.13.1; P1/P4 planned
**Goal:** the best IR photography emulator available. The engine has crossed
from "is it right?" into "perfect it" - v1.13.0 produced a portfolio-grade
frame (the under-canopy ficus: translucent wine-red leaves, golden mossy
trunks, correct sky-gap rendering). The remaining gaps are precise.

## Verdict on v1.13.0 (image-referenced)

KEEP (working, field-confirmed):
- Magenta-red foliage manifold: authentic Aerochrome hue across all four
  frames; backlit translucency in the ficus shot is film-grade.
- Tone-preserving mono foliage (texture retained, no white sheet).
- Halation concept: correct physics, but see P2.
- Golden-yellow sunlit facades at low sun: AUTHENTIC EIR behaviour
  (documented; wontfix - do not "correct" this).

FIX:
1. **Sky integrity (P1 - the #1 quality gap).** Smooth sky gradients show
   chunky noise even at Grain=Low, and the mono sky has a visible tonal seam
   band in the gradient zone. Root causes: per-pixel white-noise dither+grain
   (no spatial structure; reads digital exactly where film is silkiest), and
   the mono hazy/positional sky blend creating a seam.
2. **Halation over-rings large bright areas (P2).** Glow outlines around
   towers against a huge bright sky read HDR-ish. The mono path has a
   local-contrast gate (halo = 0.35 + 0.65*smoothstep on luma - smoothLuma);
   the aero path was shipped WITHOUT it. Also the wide ring is slightly hot
   on skyline edges.
3. **Shaded white architecture renders saturated blue (P3).** Skylight-lit
   neutral walls (arch-courtyard building) should go cool-GREY, not lavender:
   the near-neutral protection (greyC) is too narrow to catch cool-cast
   whites, and the blue paths saturate them.
4. **Mono water void (P4, long-standing).** Pools render Zone-0 black with
   no sheen. Real Rollei water: Zone I-II with specular ripple life.

## Execution plan

### P2 - halation contrast gate for aero (SHIPPED v1.13.1)
Apply the mono path's edge gate to the aero halation: scale by
smoothstep(0.015, 0.09, luma - smoothLuma) so only genuine local highlights
halate, never broad sky boundaries. Reduce wide-ring weights ~25%.
Acceptance: no visible glow outline on tower/sky boundaries; speculars
(pool glints, sunlit railings) still ring.

### P3 - neutral-architecture protection (SHIPPED v1.13.1)
Widen the cool-neutral pull: pixels that are near-neutral with a mild BLUE
cast and mid-high luma get pulled toward neutral cool-grey before the blue
paths can saturate them (greyC gains a cool-cast branch: chromaDist up to
0.09 when the cast is blue-leaning). Acceptance: shaded white facades read
cool-grey; genuinely blue objects (tiles, pool) unaffected (they exceed the
chroma ceiling).

### P1 - film-structured noise (NEXT - the flagship item)
Replace per-pixel white noise with spatially-structured grain:
- Value-noise clumps: 2 octaves of INTERPOLATED hash noise (smooth between
  lattice points) at 720p-normalized scales ~1.6px and ~3.5px, luma-
  correlated (density-weighted bell already exists).
- Dither: swap the white-noise anti-banding dither for interleaved-gradient
  noise (Jimenez IGN - one line, dramatically better distribution), halve
  the sky dither amplitude.
- Mono sky seam: blend the hazy detector's positional product through a
  wider smoothstep so the gradient transition has no visible band.
Acceptance: at Grain=Low, a clear-sky gradient shows NO chunky noise at
100% on a 12MP capture; grain on foliage reads as clumped film texture.

### P4 - mono water rendering (NEXT)
Water floor + sheen for the B&W presets: in dark smooth low-chroma regions,
(a) floor the post-curve value at ~0.06, (b) re-add source luma detail
(ripple speculars) scaled ~0.8 so water has surface life. No water
segmentation heuristics beyond the existing murky signals - floor+detail is
safe even on false positives (dark smooth shadow gains a whisper of tone).
Acceptance: pool reads Zone I-II with visible ripple, never a void; deep
tree shadow unchanged in character.

## Verification protocol
Every item: numpy-validate on the four v1.13.0 field frames' originals
(reprocess A/B) with the acceptance criteria measured before any push;
glsl balance checks; CI green; user device confirmation on the same scenes.

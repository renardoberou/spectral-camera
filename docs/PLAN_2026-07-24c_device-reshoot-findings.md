# Plan: two real defects found on the first device re-shoot of grain + Classic Film (2026-07-24, third pass)

**Trigger:** first-ever device shoot of the Classic Film family (S1-S3 in VALIDATION.md,
previously all "New; needs first device shoot") plus a Rollei/Tri-X mono comparison,
requested to confirm everything shipped in the last two grain sessions actually looks
right on real glass. Six photos: Rollei IR, Tri-X 400, CineStill 800T (daylight),
CineStill 800T (night lamp), Rollei IR (interior), Ektar 100.

Two real, precisely-diagnosed defects came out of this, unrelated to grain. Both fixed,
verified numerically before touching the shader, same as prior cycles.

## Finding 1: CineStill halation only tints the bright side of an edge, never the dark side

**Photo:** CineStill 800T, a lit mosaic-glass lamp at night, standing in for a night
street scene.

**Measured:** scanned the actual bright-glass-to-dark-fixture luma transition pixel by
pixel (luma drops from 0.86 to 0.07-0.11 within ~35px). Right at and after the drop -
exactly where the red halo should be most visible - measured R-B = -0.07 to -0.11 (blue
skewed, if anything) at every sample point. Zero red tint anywhere on the dark side of
the edge, despite the light source clipping to pure white (well above CineStill's 0.80
halo threshold) a few dozen pixels away.

**Root cause:** `edgeGate = smoothstep(0.015, 0.09, lumaOf(src) - smoothLuma)` only
evaluates true where the CURRENT pixel is itself brighter than its own blurred
neighbourhood. That's satisfied on the light source's own rim (which is usually already
near-clipped, so an additive red push is invisible there) and never satisfied on the
dark pixels immediately surrounding it (their own luma is far below their blurred
neighbourhood, which includes the bright source, so the gate reads zero). The halo
energy (`hal.x`/`hal.y`, computed via ring-sampling for bright neighbours past the
per-stock threshold) was already correct and already zero in flat, unrelated regions -
`edgeGate` was gating the wrong side of the transition entirely.

**Fix:** `edgeGate = smoothstep(0.015, 0.09, abs(lumaOf(src) - smoothLuma))`. Verified in
numpy against a profile modeled on the actual measured transition: old edgeGate applies
tint only where luma=0.86 (the source's own edge, tint 0.015-0.03), never where
luma=0.08 near the same edge; new edgeGate applies the same 0.015-0.03 tint on BOTH
sides of the transition, and correctly returns to zero a short distance into the flat
dark interior (no bleed into unrelated regions) and is unchanged deep inside a uniformly
bright region (no new over-tinting there either).

## Finding 2: mono-IR sky suppression has zero effect on genuinely neutral overcast sky

**Photo:** Rollei IR 400, an overcast balcony portrait.

**Measured:** sky region averages 0.903 luma in the processed output - essentially the
unprocessed source brightness, with visible cloud texture (std 0.083) but no suppression
toward the Zone I-II floor M1 in VALIDATION.md calls for.

**Root cause:** the two existing sky detectors both require some degree of blue bias -
`skyChroma` needs blue to dominate red/green, `skyHazy` needs a decisive (0.04-0.12) blue-
over-green gap, explicitly to avoid a flickering/smudged look on cloud texture that's
merely blue-ish. A genuinely neutral grey/white overcast sky (b approx g approx r, which
is exactly what this real photo has) satisfies neither detector and gets `skyDown = 0`,
i.e. zero suppression, regardless of brightness. This wasn't a deliberate design choice
about overcast skies specifically - the comments describe guarding against partial-blue
flicker, not excluding true neutral sky from suppression altogether. Real IR film
suppresses sky whether it's blue or grey (Rayleigh scattering is absent in NIR in both
cases).

**Fix:** added a third detector, `skyOvercast`, gated on brightness (0.68-0.90 luma),
LOW saturation (opposite of skyHazy's requirement - this one wants near-neutral, not
blue-biased), and `skyT` (the existing sky-ward position gradient) so it only fires on
genuinely bright, flat, sky-positioned regions - not a bright neutral shirt or wall
lower in the frame. Verified in numpy: fires at 0.30 on the real photo's overcast-sky
values; stays exactly unchanged (1.0, 0.194) on clear-blue-sky and existing-hazy-sky
cases; stays exactly 0 on a bright neutral shirt/wall positioned lower in frame; produces
a small (0.078) partial contribution on hypothetical blown-white foliage highlights,
consistent with the existing `(1 - veg*0.7)` partial-exclusion already applied to the
other two sky terms (not a new risk, same existing damping behaviour extended to a third
term).

## Not yet resolved

- **Rollei IR, interior wall/window shot:** a genuinely blocky, high-variance patch in a
  dark window reflection (measured: 68x higher block-to-block variance than the flat
  wall beside it), confirmed straight-from-app (not a re-export compression artifact).
  Not root-caused this cycle - needs either a less-compressed export for closer pixel
  inspection or a dedicated re-shoot of a similar reflective/high-contrast scene.
- **Ektar 100 skin/warmth and CineStill 800T daylight teal-shadow visibility:** both
  photos show real color differences from their counterpart shots, but the two frames
  weren't taken in identical light, so I can't yet separate "the preset is doing this"
  from "the light was different." Needs a same-scene, same-light comparison.

## Verification

Both fixes ported to numpy and checked against the actual measured values from the real
photos before touching the shader (see chat transcript this session for the exact
scripts/output - not committed as standalone files this cycle, values quoted above).
Both are single-expression, narrowly-scoped changes: one wraps an existing expression in
`abs()`, the other adds one new multiplicative term to an existing sum, gated by
existing signals (`skyT`) already computed and threaded through the function. Neither
touches grain, tone curves, or any other preset family's code path.

**Still requires the device:** confirming both fixes look right on the actual scenes
that exposed them (same lamp-at-night framing for the halation fix; the same or a
similar overcast-sky framing for the sky fix), plus the two open items above.

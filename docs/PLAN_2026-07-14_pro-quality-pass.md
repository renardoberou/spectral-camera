# Plan: professional photo-quality pass + pro-tool shell

**Date:** 2026-07-14 · **Status:** PLANNED (not yet implemented)
**Input evidence:** 7 field images (balcony close-range pair through glass; garden/pool
in sun and overcast; mono + Aerochrome outputs of each), v1.9.3 engine.
**Goal:** outputs that survive a picky professional IR photographer, and the capture
tooling expected of a commercial app for professionals/artists.

## Part 1 — The photographer's verdict on v1.9.3 (image-referenced)

Mono, overcast garden (output vs reference):
1. **Sky: fixed.** Cloud structure retained, believable overcast IR rendering. Keep.
2. **FATAL — foliage is a white sheet.** Zero intra-canopy separation: no species
   differentiation, no clump shadowing, no leaf texture. The research's make-or-break
   rule (sunlit foliage Zone VII–VIII, TEXTURED) is violated worse than before.
   Diagnosis: the broadened (olive) classifier now catches essentially all canopy at
   full strength; the bilateral chroma denoise removed the pixel noise that previously
   provided *accidental* variation; and the Wood lift is flat (not tone-modulated), so
   uniform classification -> uniform white. The denoise was correct - it exposed that
   the lift model itself is flat.
3. **Water is a Zone-0 void.** Real Rollei water sits Zone I-II with surface sheen.
4. **No midtone spine.** Buildings wash pale; the frame reads binary white/black. A
   35x25 print would use two inks.

Aerochrome, overcast garden:
5. **Cloud sky: genuinely good.** Drama retained. Keep.
6. **Foliage is flat crimson upholstery.** Every tree the same hue and saturation; the
   yellow-green cecropia crowns, clearly distinct in the reference, vanish into the
   same red. Real EIR renders a magenta-red-orange-pink continuum across species and
   vigor. The discrete olive->coral branch is not enough; hue must be continuous.
7. **Pool is electric dye.** Oversaturated ultramarine, lane lines and water
   transparency flattened away.
8. **Red-carpet mask bleed.** Ground cover floods path edges; the pond renders dark
   blood-red - water must NEVER be red.
9. **Sticker-collage objects.** Playground equipment keeps native saturated yellow/blue
   against remapped surroundings; real reversal film passes EVERYTHING through one dye
   logic.

Balcony close-range through glass:
10. Potted plant itself: believable. Keep.
11. **Red speckle rain on the through-glass wall.** The green-tinted glass casts the
    whole wall slightly green; the olive gate's near-neutral branch fires patchily on
    it. Large-area coherent casts defeat the bilateral average (it denoises pixels,
    not casts).
12. **Bath mat = maximum-chroma flat red.** Textile texture obliterated: the
    saturation-headroom limiter preserved channel range, but the color REPLACEMENT is
    flat.

## Part 2 — Root causes (one sentence each)

- R1. Foliage rendering is *paint* (flat hue+sat mix), not *colorization* (film hue
  modulated by the source's full tonal structure).
- R2. Foliage hue is binary (veg red / olive coral) where the film gives a continuous
  manifold driven by NIR-proxy strength.
- R3. Classifiers lack a chroma-energy floor, so large near-neutral casts (glass,
  haze) fire them.
- R4. Water is not sacrosanct: foliage/red output can bleed into water regions.
- R5. Non-vegetation objects bypass the film grade, keeping native color.
- R6. Mono Wood lift is flat and unbounded by local structure; midtones are not
  anchored.

## Part 3 — Market research: what commercial pro apps have (2026)

Surveyed: ProShot, Camera FV-5, Open Camera, MotionCam, ProCam X, Lightroom Mobile
camera, plus film-look apps (VSCO, RNI-style). Professional table stakes:
- RAW/DNG capture; live RGB histogram; highlight-clipping zebras; focus peaking;
  manual WB; custom saved profiles; EXIF control. (ProShot ships a device-capability
  "Evaluator"; MotionCam ships zebras/waveforms.)
- Film-look apps compete on preset INTENSITY sliders and non-destructive re-editing
  of existing photos.
Our moat is the physics-grounded IR engine + identical-shader WYSIWYG preview; the
gap is the monitoring/IO shell around it.

## Part 4 — Next update scope

### P1 — Engine (fixes the verdict)
1. **Tone-preserving foliage colorization** (fixes R1, mono sheet + flat red):
   output luminance = source tonal structure (bilateral low-freq lift + high-freq
   detail retained and re-added post-curve); hue from the film model. Mono: lift
   applies to the low-pass; detail (luma minus bilateral) re-added scaled ~0.7.
   Acceptance: on the overcast garden, canopy std-dev within the veg mask >= 0.07
   in print value (currently ~0.02), foliage p95 <= 0.90, leaf clumps visibly
   separated at 100%.
2. **Continuous foliage hue manifold** (fixes R2): hue position = f(veg strength,
   ng-nb, source luma) sweeping crimson->scarlet->coral->yellow-green; delete the
   discrete olive branch in favor of the manifold. Acceptance: cecropia crowns
   visibly distinct from almond trees in the garden scene.
3. **Chroma-energy floor + cast guard** (fixes R3): all veg/olive classification
   multiplied by smoothstep on chroma distance from neutral (floor ~0.045) AND
   gated to zero where the bilateral-average chroma is itself near-neutral
   (coherent casts). Acceptance: zero red speckle on the through-glass wall crop.
4. **Water sanctity** (fixes R4): inside the water mask, foliage/red contributions
   are suppressed to <= 10%; murky water -> dark neutral. Acceptance: pond hue
   within +-12deg of neutral-brown/blue axis, never red; pool keeps lane-line
   luminance variation (transparency).
5. **Unified reversal grade for non-veg** (fixes R5) + **mono midtone anchor**
   (fixes R6): all non-classified pixels pass through a single soft 3x3 dye
   matrix + curve so man-made objects harmonize; mono Zone V re-anchored so
   neutral buildings land 0.45-0.60. Acceptance: playground reads as part of the
   same film frame; mono histogram has a real midtone population.

### P2 — Pro-tool shell (research-driven, this update)
6. **Live RGB histogram + clipping zebras** in the HUD (tiny render pass or the
   existing analysis stream; zebras as diagonal overlay above 0.98). This would
   have prevented every blown-session incident in the project history.
7. **Reprocess from gallery**: long-press a gallery item (or open detail) ->
   "Process with current preset" via the existing import path = non-destructive
   versioning of any shot, including saved originals.
8. **Preset intensity chip row** (Off/25/50/75/100%): final mix between source and
   processed in-shader; the single most-requested control in film-look apps.

### P3 — Deferred (next cycle, listed for the commercial roadmap)
RAW/DNG capture; focus peaking; named custom profiles; corner-docked minimal HUD
(translucency attempt from v1.9.2 silently failed - 0 cards matched - carry it
here); device-capability diagnostics screen; onboarding with the honest-IR note.

## Part 5 — Verification protocol
1. Numpy port of P1 items validated against ALL SEVEN field images before any
   push, with the acceptance numbers above measured and reported.
2. glslangValidator on both assembled shaders.
3. CI green; then device verification by the user: re-shoot the garden (sun +
   overcast) and the balcony-through-glass scene, plus Reprocess-from-gallery on
   the same originals for A/B.

# Plan: structured film-look families (Aerochrome + Monochrome IR)

**Date:** 2026-07-17 · **Status:** executed this cycle
**Goal:** advance both flagship pillars (Aerochrome, Monochrome IR) from
isolated presets into coherent, structured families, per the master plan's
Phase 0-3 ordering, without regressing the existing physically-motivated
engine or the preview/save parity that is the app's core strength.

## Diagnosis going in

- The rendering engine (`SpectralGlPipeline.kt`) was already physically
  motivated and materially strong: chromaticity-based classification,
  exposure-invariant vegetation detection, water-sanctity gating,
  physics-correct halation, structured grain. This was not a rewrite
  situation.
- The gap was architectural, exactly as the master plan named it: each of
  the 3 mono and 2 aero presets was a **copy-pasted `if (uPreset == N)`
  block** with magic numbers duplicated across branches (three
  near-identical water-floor blocks, two near-identical aero-halation
  blocks). There was no look-definition layer separate from the rendering
  engine, and only 5 of the 11 requested family members existed
  (Rollei/HIE/SFX + Classic/Gold).
- `README.md` stated version 1.8.7 (code 29); `app/build.gradle.kts` was
  actually at 1.15.0 (code 43) - a Phase 0 truth-cleanup item.
- No validation artifact existed despite `docs/PLAN_2026-07-16` already
  doing scene-referenced tuning informally.

## Scope this cycle

1. **Truth cleanup:** fix the README version-number drift; rewrite the
   preset table to describe the two families accurately.
2. **Unified film-look architecture:** new `core/FilmLook.kt` with
   `MonoIRLook` / `AerochromeLook` data classes and a `FilmLookLibrary`
   table. Refactored `irHDCurve()` and `irLuminance()` to take explicit
   curve/lift/sky parameters instead of an internal `grade` branch;
   refactored `aerochrome()` to take `curveMix`/`satCap`/`magentaBoost`/
   `skyDepthBoost` instead of hardcoded constants. Collapsed the 3 mono
   and 2 aero `if` blocks in `presetColor()` into one `monoLook` engine
   (`uPreset <= 5`) and one `aeroLook` engine (`uPreset <= 10`), each
   reading uniforms sourced from `FilmLookLibrary` in `drawQuad()`.
3. **Aerochrome family:** added Soft, Dense, Faded/Vintage to the existing
   Classic/Gold, differentiated via curve/saturation/magenta/sky-depth/
   halation/grain/fade dials.
4. **Monochrome IR family:** added Moderate (Konica-style), Fine-Grain,
   Soft Vintage to the existing Rollei/HIE/SFX, differentiated via the
   same tone-curve/Wood-lift/sky-strength/halation/grain/water-floor/
   acutance dials the existing three already implied but never exposed as
   data.
5. **UI:** grouped the preset picker by family with section headers instead
   of one flat list of nine.
6. **Validation:** `docs/VALIDATION.md` - 8 named Aerochrome scenes + 8
   named Monochrome IR scenes, expected behavior, failure modes, engine
   hooks, and a status column for physical-device re-verification.

Deliberately out of scope this cycle: HQ-only export-path divergence beyond
what already exists (the shader is already preview=capture identical by
design, which is a stated product strength to preserve, not a gap); RAW/DNG
ingest; per-look UI copy/iconography beyond section headers.

## Why this scope

The single highest-leverage move was separating look *data* from the
rendering *engine*, because every other roadmap item (family breadth, stock
differentiation, maintainability) is downstream of it. Doing family
expansion first without the architecture change would have meant 6 more
copy-pasted `if` blocks - the opposite of what the master plan asks for.

## Verification approach

No Android SDK / emulator is available in this execution environment, so
this cycle was verified by: full manual review of the shader's brace/paren
balance and every changed call site against its new signature; confirming
every new/renamed uniform has a matching `glGetUniformLocation` and a
`glUniform*` call in `drawQuad()` for all three `LookFamily` branches;
confirming `SpectralPreset.toShaderIndex()` stays in exact sync with the
shader's `presetColor()` index ranges; confirming settings persistence
(`CameraSettingsRepository`) keys presets by enum `.name`, so the new
entries are additive and do not break existing stored preferences.
Physical-device visual verification (the project's own stated bar for
shader changes) is listed as a limitation below - it could not be run in
this environment.

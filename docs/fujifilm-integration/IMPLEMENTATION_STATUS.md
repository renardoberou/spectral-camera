# Fujifilm-inspired integration implementation status

Date: 2026-08-18
Branch: `feat/fujifilm-inspired-rendering`

## Completed source scope

- Recorded the clean baseline and hashes of preserved repository fixtures.
- Added Android-free working-space helpers in `core/color/WorkingSpaceMath.kt`.
- Added monotonic toe/mid/shoulder reference math in `core/color/ToneMath.kt`.
- Added deterministic JVM tests for finite handling, conversion round trips, headroom, monotonic tone, shoulder slope, and final bounds.
- Added `ToneProfile`, `ProtectionProfile`, `DensityProfile`, and `SharedFilmProfile` as additive data structures in `FilmLook.kt`.
- Added original `Archive Chrome`, `Cinematic Neutral`, and `Warm Negative` visible-spectrum profiles.
- Added a generic GLSL shared refinement stage after `presetColor()`. Legacy profiles receive identity uniforms; the spectral front ends remain unchanged.
- Added per-draw uniform reset and shader-contract tests for ordering and preset indices.
- Added a deterministic Python calibration harness under `tools/fujifilm_calibration/` with separate tone, grain, colour/chart, reference, and report functions.
- Added calibration protocol and manifest templates with no fabricated device/reference captures.
- Updated product and release documentation with Fuji-inspired disclosure and explicit verification boundaries.

## Evidence

- `git diff --check`: PASS.
- Python bytecode compilation: PASS.
- Calibration package import/chart smoke test: PASS.
- Calibration worker's six deterministic tests: PASS using an explicit Python runner; `pytest` is unavailable in the Hermes venv.
- `./gradlew testDebugUnitTest`: NOT RUN successfully; the environment has no `java` executable and `JAVA_HOME` is unset.
- Android lint/build/APK: NOT RUN locally for the same toolchain blocker.
- Physical Moto Edge 60 Fusion: NOT RUN; no device connection was available.

## Interpretation

The repository now contains a reviewable implementation slice: new visible
profiles use a shared, data-driven refinement after the existing film-family
front end, while Aerochrome synthetic-NIR/EIR and monochrome-IR construction
remain upstream and identity-configured for legacy looks. The calibration
harness can measure future paired captures but does not claim that any profile
matches a Fujifilm camera.

## Remaining gates

- Install Java 17/Android SDK or use GitHub Actions to execute JVM tests, lint,
and APK builds.
- Parent-side review must inspect the full diff and exact CI head SHA.
- Build and checksum an APK from the verified commit.
- Install and exercise the APK on the Moto Edge 60 Fusion.
- Record preview/capture/gallery/export and named failure-scene results in a
dated validation report before calling the profiles device-verified.

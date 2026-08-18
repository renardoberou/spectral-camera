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
- `./gradlew testDebugUnitTest`: NOT RUN locally; the environment has no `java` executable and `JAVA_HOME` is unset.
- GitHub Actions Android CI: **PASS** for head `8648f9fc9ad3ad6eb83a96604eb222e407ae8665`; run `32163202346` at `https://github.com/renardoberou/spectral-camera/actions/runs/32163202346`.
- CI debug APK artifact: **PASS**; `/home/bernardo/.hermes/profiles/spectral-camera/artifacts/8648f9f/app-debug.apk`, 27,517,186 bytes, SHA-256 `f18b783d6743c4231fd10fe66e2b6ae0e049fc9c0465a14df196eed72a17b41c`.
- Android lint/build: **PASS in CI**; local Gradle execution remains unavailable.
- Physical Moto Edge 60 Fusion: NOT RUN; no device connection was available.

## Interpretation

The repository now contains a reviewable implementation slice: new visible
profiles use a shared, data-driven refinement after the existing film-family
front end, while Aerochrome synthetic-NIR/EIR and monochrome-IR construction
remain upstream and identity-configured for legacy looks. The calibration
harness can measure future paired captures but does not claim that any profile
matches a Fujifilm camera.

## Remaining gates

- Local Java 17/Android SDK installation is optional now that CI produced a
verified artifact; local Gradle tests remain a reproducibility improvement.
- Parent-side review inspected the full diff, corrected an undefined GLSL
`smoothstep` edge, and verified the exact CI head SHA.
- Install and exercise the CI APK on the Moto Edge 60 Fusion.
- Record preview/capture/gallery/export and named failure-scene results in a
dated validation report before calling the profiles device-verified.

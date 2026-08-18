# Fujifilm integration fixtures

This directory records fixtures for the Fujifilm-inspired renderer without replacing existing evidence.

## Rules

- Preserve the original file and record SHA-256 in the baseline manifest.
- Record source image, preset, device, resolution, renderer version, and confidence when known.
- Mark unknown provenance as `unknown`; do not infer device or scene conditions.
- Synthetic arrays used by unit tests must be labeled synthetic and must not be presented as physical-device evidence.
- Device captures belong in a dated subdirectory only after they are actually captured.

The initial baseline reuses the existing grain artifacts under `docs/assets/grain-baseline-2026-07-23/` and `docs/assets/grain-verification-2026-07-24/`. Their hashes are recorded in `docs/fujifilm-integration/baseline-2026-08-04.md`.

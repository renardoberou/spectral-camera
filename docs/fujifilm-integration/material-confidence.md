# Material confidence contract

Material protection is continuous confidence weighting, not hard segmentation. `MaterialConfidenceMath` is the Android-free reference implementation for this contract. Each field is deterministic, continuous, finite, and bounded to `[0, 1]`:

- `skinConfidence`, `foliageConfidence`, `skyConfidence`, and `blueCyanConfidence` compare soft RGB chromaticity targets.
- `neutralConfidence` rewards low channel spread.
- `highlightConfidence` and `shadowConfidence` are soft luminance bands.
- Every field is multiplied by `reliability`, which smoothly suppresses chromatic classification near black where ratios are unstable.
- `weightedProtection` combines the fields with non-negative `MaterialConfidenceWeights` and returns a bounded weighted mean. Zero total weight returns zero.

These are broad protection signals, not semantic segmentation or a claim of material identity. Inputs may retain scene headroom; non-finite values are treated as unreliable. Renderer integration should preserve the same soft blending behavior and stock-specific weighting.

Acceptance scenes: mixed foliage, foliage/sky boundary, skin, saturated red objects, blue/cyan surfaces, neutral walls, and reflective glass. A source/build result cannot upgrade these rows to device `PASS`; physical re-shoots remain separate. No device evidence is claimed by the pure math tests.

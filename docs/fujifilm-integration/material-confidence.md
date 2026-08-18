# Material confidence contract

Material protection is continuous confidence weighting, not hard segmentation. Confidence must be bounded to `[0, 1]`, fall toward zero near black where chromaticity is unreliable, and blend rather than cut at material boundaries.

The first implementation may reuse the renderer's existing smoothed vegetation, sky, and water signals. New visible-spectrum protection should preserve neutrals and plausible skin while allowing foliage and sky density to remain stock-specific.

Acceptance scenes: mixed foliage, foliage/sky boundary, skin, saturated red objects, blue/cyan surfaces, neutral walls, and reflective glass. A source/build result cannot upgrade these rows to device `PASS`; physical re-shoots remain separate.

# Color-density calibration contract

Color-density behavior is not global saturation. The analytic stage should compute chroma relative to luminance, apply smooth hue-sector weighting and midtone weighting, then compress extreme chroma before channel clipping. Neutral and skin protection remain active.

Calibration reports must keep separate measurements for neutral-axis error, skin, foliage, sky, highlight clipping, shadow separation, grain, halation, and preview/export difference. No single aggregate score may hide a regression.

Numeric coefficients are provisional until paired controlled captures exist. Internet reference JPEGs with unknown exposure or editing history are qualitative direction only.

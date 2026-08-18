# Color-density calibration contract

Color-density behavior is not global saturation. The analytic stage computes a bounded density from chroma, a circular hue-sector weight, and a midtone luminance weight before compression:

```text
hue_weight = cos(pi * circular_distance(hue, center) / sector_width)
              when distance < sector_width / 2, otherwise 0
luma_weight = 1 - abs(2 * clamp(luminance, 0, 1) - 1)
raw_density = clamp(chroma, 0, 1) * hue_weight * luma_weight * max(gain, 0)
density = raw_density / (1 + max(compression, 0) * raw_density)
```

Hue distance wraps at 0/360 degrees, so sectors centered near zero remain continuous. Neutral chroma is exactly zero, while midtones receive more density than black or white. Inputs are sanitized and the final result is finite and bounded to `[0, 1]`.

The pure Kotlin implementation is `HueSectorMath`; the deterministic NumPy equivalent is in `reference_pipeline.py`. Keep their formulas and defaults aligned when validating a shader implementation. The reference functions accept scalars or NumPy arrays, enabling patch-level calibration without LUTs or proprietary assets.

Calibration reports must keep separate measurements for neutral-axis error, skin, foliage, sky, highlight clipping, shadow separation, grain, halation, and preview/export difference. No single aggregate score may hide a regression.

Numeric coefficients are provisional until paired controlled captures exist. Internet reference JPEGs with unknown exposure or editing history are qualitative direction only.

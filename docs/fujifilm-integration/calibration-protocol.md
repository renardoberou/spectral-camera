# Fujifilm calibration protocol

This protocol defines a repeatable harness for comparing a Fujifilm reference capture
with a Spectral Camera render. It is an analysis aid, not a substitute for on-device
review or a claim that a shader reproduces a physical emulsion.

## Capture

1. Use the same camera, lens, focus, exposure, white balance, lighting, and chart
   position for the reference and candidate captures. Record them in a manifest.
2. Capture a neutral chart and a texture-rich scene. Keep the camera fixed; do not
   compare crops with different framing.
3. Preserve original files. Do not silently apply sharpening, denoising, resizing,
   or colour-management conversions before measurement.
4. Mark every sample as `synthetic` or `device`. Synthetic fixtures are for testing
   the harness only and must not be presented as device evidence.

## Measurements

The harness accepts RGB images and normalized crop boxes `(left, top, right, bottom)`.
Run the measurements from `tools/fujifilm_calibration/` (numpy is required for numeric
work and Pillow is required only for image-file loading):

```sh
python measure_tone.py capture.png
python measure_grain.py capture.png
```

For chart comparisons, call `compare_chart(reference, candidate, patches)` from Python.
The output keeps three independent sections:

- **Tone:** luma mean and percentile response per patch, plus candidate deltas.
- **Grain:** deterministic luma high-pass RMS and 95th-percentile absolute residual.
  This is a texture proxy, not a physical granularity measurement.
- **Colour:** mean RGB, per-channel deltas, and mean absolute error per patch.

`report.py` renders these sections to Markdown. There is intentionally no composite
score: a tone mismatch must not be hidden by a good grain or colour result.

## Interpretation and evidence

Use identical chart geometry and image dimensions. Report the manifest, source image
digests, tool version/schema, and whether each input is synthetic or device data.
Treat thresholds as study-specific and document them alongside results; this initial
harness does not invent acceptance thresholds or device/reference data.

A passing numeric comparison still requires visual inspection for clipping, halos,
colour casts, and spatial artifacts. Record failures rather than tuning until a score
passes.

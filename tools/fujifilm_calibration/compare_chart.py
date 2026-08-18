"""Compare chart patches while preserving tone, colour, and texture metrics."""
from __future__ import annotations

from typing import Any, Iterable, Sequence

try:
    from .reference_pipeline import as_rgb, crop_fraction, require_numpy
    from .measure_grain import compare_grain
    from .measure_tone import compare_tone
except ImportError:  # direct script execution
    from reference_pipeline import as_rgb, crop_fraction, require_numpy
    from measure_grain import compare_grain
    from measure_tone import compare_tone


def compare_chart(reference: Any, candidate: Any, patches: Iterable[Sequence[float]]) -> dict:
    """Return structured chart comparison; no composite pass/fail score is made."""
    np = require_numpy()
    boxes = list(patches)
    ref = as_rgb(reference)
    got = as_rgb(candidate)
    if ref.shape != got.shape:
        raise ValueError("reference and candidate must have the same dimensions")
    tone = compare_tone(ref, got, boxes)
    grain = compare_grain(ref, got, boxes)
    colour = []
    for box in boxes:
        a, b = crop_fraction(ref, box).mean((0, 1)), crop_fraction(got, box).mean((0, 1))
        colour.append({"box": [float(v) for v in box], "reference_rgb": [float(v) for v in a],
                       "candidate_rgb": [float(v) for v in b],
                       "mae": float(np.mean(np.abs(b - a))),
                       "delta_rgb": [float(v) for v in (b - a)]})
    return {"schema": "fujifilm-calibration/chart-comparison-v1", "tone": tone,
            "grain": grain, "colour": {"metric": "colour", "patches": colour}}

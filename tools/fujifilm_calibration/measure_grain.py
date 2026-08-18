"""Deterministic spatial grain/texture measurements, separate from tone."""
from __future__ import annotations

import argparse
import json
from typing import Any, Iterable, Sequence

try:
    from .reference_pipeline import as_rgb, crop_fraction, luma, load_image, require_numpy
except ImportError:  # direct script execution
    from reference_pipeline import as_rgb, crop_fraction, luma, load_image, require_numpy


def measure_grain(image: Any, regions: Iterable[Sequence[float]] | None = None, blur_radius: int = 1) -> dict:
    """Estimate texture as RMS high-pass energy per region.

    This is a measurement proxy, not a claim about physical emulsion granularity.
    A one-pixel neighbourhood mean is used so results are deterministic and dependency-light.
    """
    np = require_numpy()
    if blur_radius < 1:
        raise ValueError("blur_radius must be >= 1")
    boxes = list(regions or [(0.0, 0.0, 1.0, 1.0)])
    rows = []
    for box in boxes:
        patch = luma(crop_fraction(image, box))
        if min(patch.shape) <= 2 * blur_radius:
            raise ValueError("region is too small for blur_radius")
        # Edge-padded box filter; integral image keeps this reproducible and fast.
        pad = np.pad(patch, blur_radius, mode="edge")
        size = 2 * blur_radius + 1
        integral = np.pad(pad, ((1, 0), (1, 0)), mode="constant").cumsum(0).cumsum(1)
        smooth = (integral[size:, size:] - integral[:-size, size:] - integral[size:, :-size] + integral[:-size, :-size]) / (size * size)
        residual = patch - smooth
        rms = float(np.sqrt(np.mean(residual ** 2)))
        if rms < 1e-5:  # suppress float32 round-off on truly flat fixtures
            rms = 0.0
        rows.append({"box": [float(v) for v in box], "rms": rms,
                     "p95_abs": float(np.percentile(np.abs(residual), 95)),
                     "channel_rms": [float(np.sqrt(np.mean((as_rgb(crop_fraction(image, box))[..., i] - smooth) ** 2))) for i in range(3)]})
    return {"metric": "grain", "method": "luma_high_pass_box", "blur_radius": blur_radius, "regions": rows}


def compare_grain(reference: Any, candidate: Any, regions=None, blur_radius: int = 1) -> dict:
    ref, got = measure_grain(reference, regions, blur_radius), measure_grain(candidate, regions, blur_radius)
    return {"metric": "grain", "reference": ref, "candidate": got,
            "deltas": [{"box": a["box"], "rms_delta": b["rms"] - a["rms"]}
                       for a, b in zip(ref["regions"], got["regions"])]}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image")
    parser.add_argument("--blur-radius", type=int, default=1)
    args = parser.parse_args(argv)
    print(json.dumps(measure_grain(load_image(args.image), blur_radius=args.blur_radius), indent=2))

if __name__ == "__main__":
    main()

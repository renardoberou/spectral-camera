"""Tone-response measurements, independent of grain and colour metrics."""
from __future__ import annotations

import argparse
import json
from typing import Any, Iterable, Sequence

try:
    from .reference_pipeline import as_rgb, crop_fraction, luma, load_image
except ImportError:  # direct script execution
    from reference_pipeline import as_rgb, crop_fraction, luma, load_image


def _regions(regions: Iterable[Sequence[float]] | None):
    return list(regions or [(0.0, 0.0, 1.0, 1.0)])


def measure_tone(image: Any, regions: Iterable[Sequence[float]] | None = None) -> dict:
    """Return per-region mean/percentile luma statistics (not a single score)."""
    values = []
    for box in _regions(regions):
        patch = luma(crop_fraction(image, box))
        values.append({"box": [float(v) for v in box], "mean": float(patch.mean()),
                       "p05": float(__import__("numpy").percentile(patch, 5)),
                       "p50": float(__import__("numpy").percentile(patch, 50)),
                       "p95": float(__import__("numpy").percentile(patch, 95))})
    return {"metric": "tone", "regions": values}


def compare_tone(reference: Any, candidate: Any, regions=None) -> dict:
    ref, got = measure_tone(reference, regions), measure_tone(candidate, regions)
    deltas = []
    for a, b in zip(ref["regions"], got["regions"]):
        deltas.append({"box": a["box"], "mean_delta": b["mean"] - a["mean"],
                       "median_delta": b["p50"] - a["p50"]})
    return {"metric": "tone", "reference": ref, "candidate": got, "deltas": deltas}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image")
    parser.add_argument("--regions", help="JSON list of normalized crop boxes")
    args = parser.parse_args(argv)
    print(json.dumps(measure_tone(load_image(args.image), json.loads(args.regions) if args.regions else None), indent=2))

if __name__ == "__main__":
    main()

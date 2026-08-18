"""Small, deterministic primitives for comparing calibration images.

The harness deliberately does not emulate the Android shader.  It normalizes image
inputs and leaves tone, grain, and chart comparisons as separate measurements.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any, Mapping, Sequence

try:
    import numpy as np
except ImportError:  # pragma: no cover - exercised by import-only environments
    np = None

try:
    from PIL import Image
except ImportError:  # pragma: no cover
    Image = None


def require_numpy():
    if np is None:
        raise RuntimeError("numpy is required for image measurements")
    return np


def load_image(path: str | Path):
    """Load an image as float32 RGB in [0, 1]; Pillow is optional until called."""
    require_numpy()
    if Image is None:
        raise RuntimeError("Pillow is required to load image files")
    with Image.open(path) as image:
        return np.asarray(image.convert("RGB"), dtype=np.float32) / 255.0


def as_rgb(image: Any):
    """Validate an RGB array (or grayscale array) and return float32 [0, 1]."""
    require_numpy()
    array = np.asarray(image, dtype=np.float32)
    if array.ndim == 2:
        array = np.repeat(array[..., None], 3, axis=2)
    if array.ndim != 3 or array.shape[2] != 3:
        raise ValueError("image must have shape (height, width, 3) or (height, width)")
    if not np.isfinite(array).all():
        raise ValueError("image contains non-finite values")
    if array.max(initial=0) > 1.0 or array.min(initial=0) < 0.0:
        array = np.clip(array / 255.0, 0.0, 1.0)
    return array.astype(np.float32, copy=False)


def luma(image: Any):
    array = as_rgb(image)
    return np.sum(array * np.array([0.2126, 0.7152, 0.0722], dtype=np.float32), axis=2)


def image_digest(image: Any) -> str:
    """Stable digest of normalized RGB pixels, useful for manifest provenance."""
    array = as_rgb(image)
    return hashlib.sha256(np.ascontiguousarray(np.round(array * 65535).astype("<u2")).tobytes()).hexdigest()


def crop_fraction(image: Any, box: Sequence[float]):
    """Crop using normalized (left, top, right, bottom) coordinates."""
    array = as_rgb(image)
    if len(box) != 4 or not all(0 <= float(v) <= 1 for v in box):
        raise ValueError("box must contain four normalized coordinates")
    h, w = array.shape[:2]
    left, top, right, bottom = [float(v) for v in box]
    x0, x1 = int(left * w), int(right * w)
    y0, y1 = int(top * h), int(bottom * h)
    if x1 <= x0 or y1 <= y0:
        raise ValueError("crop must have non-zero area")
    return array[y0:y1, x0:x1]


def read_manifest(path: str | Path) -> Mapping[str, Any]:
    return json.loads(Path(path).read_text(encoding="utf-8"))

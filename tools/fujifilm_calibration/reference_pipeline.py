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


def _finite_float(value: Any, default: float = 0.0) -> float:
    number = float(value)
    return number if np.isfinite(number) else default


def hue_sector_weight(hue_degrees: Any, center_degrees: Any, width_degrees: Any = 60.0):
    """Cosine-tapered circular weight, with zero at the sector boundary."""
    require_numpy()
    hue = np.nan_to_num(np.asarray(hue_degrees, dtype=np.float64), nan=0.0, posinf=0.0, neginf=0.0)
    center = _finite_float(center_degrees)
    width = _finite_float(width_degrees)
    if width <= 0.0:
        result = np.zeros_like(hue, dtype=np.float64)
    elif width >= 360.0:
        result = np.ones_like(hue, dtype=np.float64)
    else:
        distance = np.abs((hue - center + 180.0) % 360.0 - 180.0)
        result = np.where(
            distance >= width / 2.0,
            0.0,
            np.cos(np.pi * distance / width),
        )
    result = np.clip(np.nan_to_num(result, nan=0.0, posinf=1.0, neginf=0.0), 0.0, 1.0)
    return float(result) if result.ndim == 0 else result.astype(np.float32)


def luminance_weight(luminance: Any):
    """Triangular midtone weighting: black and white get zero weight."""
    require_numpy()
    value = np.nan_to_num(np.asarray(luminance, dtype=np.float64), nan=0.0, posinf=1.0, neginf=0.0)
    result = 1.0 - np.abs(2.0 * np.clip(value, 0.0, 1.0) - 1.0)
    return float(result) if result.ndim == 0 else result.astype(np.float32)


def compress_density(density: Any, compression: Any = 1.0):
    """Bound positive density with a deterministic rational compression curve."""
    require_numpy()
    value = np.nan_to_num(np.asarray(density, dtype=np.float64), nan=0.0, posinf=1.0, neginf=0.0)
    amount = max(0.0, _finite_float(compression))
    value = np.clip(value, 0.0, 1.0)
    result = value / (1.0 + amount * value)
    return float(result) if result.ndim == 0 else result.astype(np.float32)


def color_density(
    hue_degrees: Any,
    chroma: Any,
    luminance: Any,
    center_degrees: Any,
    width_degrees: Any = 60.0,
    density_gain: Any = 1.0,
    compression: Any = 1.0,
):
    """Reference for hue-weighted, luminance-weighted chroma density."""
    require_numpy()
    chroma_value = np.nan_to_num(np.asarray(chroma, dtype=np.float64), nan=0.0, posinf=1.0, neginf=0.0)
    gain = max(0.0, _finite_float(density_gain))
    weighted = np.clip(chroma_value, 0.0, 1.0)
    weighted = weighted * hue_sector_weight(hue_degrees, center_degrees, width_degrees)
    weighted = weighted * luminance_weight(luminance) * gain
    return compress_density(weighted, compression)

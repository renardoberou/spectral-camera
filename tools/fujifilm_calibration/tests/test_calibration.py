import json
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))

from compare_chart import compare_chart
from measure_grain import measure_grain
from measure_tone import measure_tone
from reference_pipeline import as_rgb, crop_fraction, image_digest
from report import markdown_report


def test_as_rgb_normalizes_uint8_and_grayscale():
    image = np.array([[0, 255], [128, 64]], dtype=np.uint8)
    got = as_rgb(image)
    assert got.shape == (2, 2, 3)
    assert got[0, 1, 0] == 1.0
    assert np.all(got[..., 0] == got[..., 1])


def test_fraction_crop_has_expected_shape():
    image = np.zeros((10, 20, 3), dtype=np.float32)
    assert crop_fraction(image, (0.25, 0.2, 0.75, 0.8)).shape == (6, 10, 3)


def test_tone_is_separate_and_deterministic():
    reference = np.full((12, 12, 3), 0.25, dtype=np.float32)
    candidate = np.full((12, 12, 3), 0.5, dtype=np.float32)
    result = measure_tone(candidate, [(0, 0, 1, 1)])
    assert result["metric"] == "tone"
    assert result["regions"][0]["mean"] == 0.5


def test_grain_detects_texture_but_flat_image_is_zero():
    flat = np.full((20, 20, 3), 0.4, dtype=np.float32)
    noisy = flat.copy()
    noisy[::2, ::2] += 0.1
    assert measure_grain(flat)["regions"][0]["rms"] == 0.0
    assert measure_grain(noisy)["regions"][0]["rms"] > 0


def test_chart_keeps_tone_grain_and_colour_sections():
    reference = np.full((20, 20, 3), 0.3, dtype=np.float32)
    candidate = reference.copy()
    candidate[:, :, 0] += 0.1
    result = compare_chart(reference, candidate, [(0, 0, 1, 1)])
    assert set(("tone", "grain", "colour")) <= set(result)
    assert result["colour"]["patches"][0]["delta_rgb"][0] > 0
    assert "score" not in result


def test_digest_and_report_are_stable():
    image = np.full((3, 3, 3), 0.25, dtype=np.float32)
    assert image_digest(image) == image_digest(image.copy())
    result = compare_chart(image, image, [(0, 0, 1, 1)])
    text = markdown_report(result)
    assert "Metrics are intentionally reported separately" in text
    assert "## Tone" in text and "## Grain" in text

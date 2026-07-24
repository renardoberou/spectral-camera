"""
Step 2 pre-verification: simulate the PROPOSED shader change (universal
exposure-dependent grain density, replacing the `uPreset <= 5` gate) and
diff it against the shipped baseline from grain_port.py, on the same
reference photo.

Proposed production change (SpectralGlPipeline.kt, "film grain" block):

    float effGrain = uGrain + uGrainBase;
    if (effGrain > 0.001) {
        float d = (lumaOf(c) - 0.42) / 0.30;
        float densityWeight = exp(-d * d);
        float grainAmp = effGrain * 0.040 * densityWeight * uGrainBias;
        vec2 gUv = grainUv / max(uHaloGrain.w, 0.05);
        c += filmGrain(gUv, uGrainSeed) * grainAmp * 2.2;
    }

i.e. the `if (uPreset <= 5)` branch is deleted; every preset now takes the
path previously reserved for mono-IR. Mono presets must therefore be
PROVABLY BYTE-IDENTICAL (same formula, same constants, same branch taken
before and after) - verified below, not just asserted.
"""
import numpy as np
from PIL import Image
from grain_port import film_grain, luma_of, LOOKS, luma_bucket_stats, apply_grain_current


def apply_grain_proposed(c, grain_base, grain_bias, grain_clump, grain_seed, u_grain=0.0):
    """Universal density weighting - identical math to the mono branch,
    now applied unconditionally regardless of uPreset."""
    h, w, _ = c.shape
    xs = (np.arange(w) + 0.5) / w
    ys = (np.arange(h) + 0.5) / h
    gx, gy = np.meshgrid(xs * 720.0, ys * 720.0)

    eff_grain = u_grain + grain_base
    if eff_grain <= 0.001:
        return c.copy(), np.zeros((h, w), dtype=np.float32)

    luma = luma_of(c)
    d = (luma - 0.42) / 0.30
    density_weight = np.exp(-d * d)
    grain_amp = eff_grain * 0.040 * density_weight * grain_bias

    g_ux = gx / max(grain_clump, 0.05)
    g_uy = gy / max(grain_clump, 0.05)
    noise = film_grain(g_ux, g_uy, grain_seed)
    delta = noise * grain_amp * 2.2
    out = np.clip(c + delta[..., None], 0.0, 1.0)
    return out.astype(np.float32), grain_amp.astype(np.float32)


def main():
    img = Image.open("reference_photo.jpg").convert("RGB")
    c = np.asarray(img).astype(np.float32) / 255.0
    h, w, _ = c.shape
    luma = luma_of(c)
    seed = 42.0
    u_grain = 0.0

    report = ["# Step 2 pre-verification: universal density weighting (proposed vs. shipped)\n\n"]

    for name, look in LOOKS.items():
        cur_out, cur_amp = apply_grain_current(
            c, look["preset"], u_grain, look["base"], look["bias"], look["clump"], seed, w, h)
        new_out, new_amp = apply_grain_proposed(
            c, look["base"], look["bias"], look["clump"], seed, u_grain)

        is_mono = look["preset"] <= 5
        identical = np.array_equal(cur_out, new_out)
        max_abs_diff = float(np.abs(cur_out.astype(np.float32) - new_out.astype(np.float32)).max())

        report.append(f"## {name} (uPreset={look['preset']}, {'MONO' if is_mono else 'COLOR/CLASSIC'})\n")
        if is_mono:
            report.append(f"- Regression check (must be identical): "
                           f"{'PASS - byte-identical' if identical else f'FAIL - max abs diff {max_abs_diff:.6f}'}\n")
        else:
            rows_before = luma_bucket_stats(luma, cur_amp)
            rows_after = luma_bucket_stats(luma, new_amp)
            report.append("- Before (flat) -> After (density-weighted), mean amplitude by luma bucket:\n")
            for (bucket, n, before), (_, _, after) in zip(rows_before, rows_after):
                report.append(f"  - luma {bucket}: {before:.5f} -> {after:.5f}\n")
            Image.fromarray((new_out * 255).clip(0, 255).astype(np.uint8)).save(f"proposed_{name}.png")
        report.append("\n")

    with open("STEP2_PREVERIFY.md", "w") as f:
        f.writelines(report)
    print("".join(report))


if __name__ == "__main__":
    main()

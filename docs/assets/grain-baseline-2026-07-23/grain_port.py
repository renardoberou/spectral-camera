"""
Exact-math numpy port of the grain stage in
app/src/main/java/com/renardoberou/spectralcamera/core/gl/SpectralGlPipeline.kt

Ported functions (line-for-line, same constants):
  - grainHash(vec2)         -> grain_hash(x, y)
  - valueNoise(vec2)        -> value_noise(x, y)
  - filmGrain(vec2, float)  -> film_grain(u, v, seed)
  - grain application block from `void main()` ("// film grain" section)

Purpose: baseline verification before touching GLSL. This script must
reproduce today's shipped behavior byte-for-byte in its math (allowing for
float32 vs GLSL mediump rounding), on the real captured reference photo,
so that the NEXT cycle's numpy diffs are trustworthy.
"""
import numpy as np
from PIL import Image

# ---------------------------------------------------------------------------
# 1:1 ports of the GLSL functions (SpectralGlPipeline.kt lines ~116-135)
# ---------------------------------------------------------------------------

def grain_hash(x, y):
    # return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
    d = x * 127.1 + y * 311.7
    s = np.sin(d) * 43758.5453
    return s - np.floor(s)


def value_noise(x, y):
    # vec2 i = floor(p); vec2 f = fract(p); vec2 u = f*f*(3-2f);
    ix, iy = np.floor(x), np.floor(y)
    fx, fy = x - ix, y - iy
    ux = fx * fx * (3.0 - 2.0 * fx)
    uy = fy * fy * (3.0 - 2.0 * fy)
    a = grain_hash(ix, iy)
    b = grain_hash(ix + 1.0, iy)
    c = grain_hash(ix, iy + 1.0)
    d = grain_hash(ix + 1.0, iy + 1.0)
    return (a * (1 - ux) + b * ux) * (1 - uy) + (c * (1 - ux) + d * ux) * uy


def film_grain(u, v, seed):
    # vec2 g = uv + vec2(seed*17.31, seed*9.77);
    gx = u + seed * 17.31
    gy = v + seed * 9.77
    # float n = valueNoise(g/1.6)*0.65 + valueNoise(g/3.4)*0.35;
    n = value_noise(gx / 1.6, gy / 1.6) * 0.65 + value_noise(gx / 3.4, gy / 3.4) * 0.35
    return n - 0.5


def luma_of(rgb):
    # dot(c, vec3(0.299, 0.587, 0.114))
    return rgb[..., 0] * 0.299 + rgb[..., 1] * 0.587 + rgb[..., 2] * 0.114


# ---------------------------------------------------------------------------
# Grain application block, ported as-is from `void main()`.
# This is TODAY'S shipped logic — includes the mono-only density gap.
# ---------------------------------------------------------------------------

def apply_grain_current(c, u_preset, u_grain, grain_base, grain_bias, grain_clump,
                         grain_seed, tex_w, tex_h):
    """
    c: (H,W,3) float32 in [0,1] — post-tonemap color, pre-grain.
    Returns (out, grain_amp_map) so callers can inspect the amplitude field
    independent of the noise itself.
    """
    h, w, _ = c.shape
    # vTexCoord in [0,1]; grainUv = vTexCoord * 720.0
    xs = (np.arange(w) + 0.5) / w
    ys = (np.arange(h) + 0.5) / h
    gx, gy = np.meshgrid(xs * 720.0, ys * 720.0)

    eff_grain = u_grain + grain_base
    out = c.copy()
    if eff_grain <= 0.001:
        return out, np.zeros((h, w), dtype=np.float32)

    luma = luma_of(c)
    if u_preset <= 5:
        d = (luma - 0.42) / 0.30
        density_weight = np.exp(-d * d)
        grain_amp = eff_grain * 0.040 * density_weight * grain_bias
    else:
        grain_amp = np.full((h, w), eff_grain * 0.045 * grain_bias, dtype=np.float32)

    g_ux = gx / max(grain_clump, 0.05)
    g_uy = gy / max(grain_clump, 0.05)
    noise = film_grain(g_ux, g_uy, grain_seed)

    delta = noise * grain_amp * 2.2
    out = np.clip(c + delta[..., None], 0.0, 1.0)
    return out.astype(np.float32), grain_amp.astype(np.float32)


# ---------------------------------------------------------------------------
# Baseline verification driver
# ---------------------------------------------------------------------------

LOOKS = {
    # name: (uPreset, grainClump, grainBias, grainBase)   -- values from FilmLook.kt
    "HIE_mono":     dict(preset=1,  clump=1.25, bias=1.20, base=0.24),
    "Rollei_mono":  dict(preset=0,  clump=1.00, bias=1.00, base=0.10),
    "Ektar_color":  dict(preset=6,  clump=0.45, bias=0.60, base=0.02),
    "CineStill":    dict(preset=7,  clump=1.05, bias=1.00, base=0.14),
    "TriX_400":     dict(preset=8,  clump=1.35, bias=1.15, base=0.26),
}


def luma_bucket_stats(luma, grain_amp, edges=(0.0, 0.15, 0.35, 0.65, 0.85, 1.0)):
    rows = []
    for lo, hi in zip(edges[:-1], edges[1:]):
        mask = (luma >= lo) & (luma < hi)
        n = mask.sum()
        mean_amp = float(grain_amp[mask].mean()) if n else float("nan")
        rows.append((f"{lo:.2f}-{hi:.2f}", int(n), mean_amp))
    return rows


def main():
    img = Image.open("reference_photo.jpg").convert("RGB")
    c = np.asarray(img).astype(np.float32) / 255.0
    h, w, _ = c.shape
    luma = luma_of(c)
    print(f"Reference photo: {w}x{h}, luma range [{luma.min():.4f}, {luma.max():.4f}], "
          f"mean {luma.mean():.4f}")

    seed = 42.0
    u_grain = 0.0  # user slider at default/off — isolates grainBase per plan doc's own test

    report_lines = []
    report_lines.append("# Grain baseline verification — current shipped math\n")
    report_lines.append(f"Reference photo: reference_photo.jpg ({w}x{h})\n")
    report_lines.append(f"User Grain slider: {u_grain} (isolating grainBase, per prior-cycle method)\n")
    report_lines.append(f"Seed: {seed}\n\n")

    for name, look in LOOKS.items():
        out, grain_amp = apply_grain_current(
            c, look["preset"], u_grain, look["base"], look["bias"], look["clump"],
            seed, w, h,
        )
        diff = out - c
        rms = float(np.sqrt((diff ** 2).mean()))
        rows = luma_bucket_stats(luma, grain_amp)

        Image.fromarray((out * 255).clip(0, 255).astype(np.uint8)).save(f"out_{name}.png")

        report_lines.append(f"## {name}  (uPreset={look['preset']}, "
                             f"grainClump={look['clump']}, grainBias={look['bias']}, "
                             f"grainBase={look['base']})\n")
        report_lines.append(f"- Overall RMS delta vs. source: {rms:.5f}\n")
        report_lines.append("- Mean grain amplitude by source luma bucket:\n")
        for bucket, n, mean_amp in rows:
            report_lines.append(f"  - luma {bucket}: n={n:>9,}  mean_amp={mean_amp:.5f}\n")
        density_note = ("density-weighted (mono path, uPreset<=5)"
                         if look["preset"] <= 5 else
                         "FLAT — no density weighting (color/classic path, uPreset>5)")
        report_lines.append(f"- Density behavior: {density_note}\n\n")

    with open("BASELINE_REPORT.md", "w") as f:
        f.writelines(report_lines)

    print("".join(report_lines))
    print("Wrote out_*.png crops and BASELINE_REPORT.md")


if __name__ == "__main__":
    main()

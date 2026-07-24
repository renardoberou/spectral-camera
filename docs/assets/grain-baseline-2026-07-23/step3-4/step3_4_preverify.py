"""
Steps 3+4 pre-verification (CORRECTED).

The first version of this script measured channel decorrelation on
(out - c) AFTER clipping to [0,1]. That is contaminated: c is a real
color photo with different R/G/B values, so even a perfectly scalar
(identical-across-channels) grain delta produces a slightly different
post-clip residual per channel wherever a channel sits near 0 or 1 -
a clipping artifact, not evidence of chroma injection. This version
measures decorrelation on the RAW delta (pre-add, pre-clip), which is
the actual quantity the gating logic controls, and uses np.allclose
(not exact array_equal) for regression checks, since float32
multiplication-order differences are ~1e-7 - far below 8-bit
quantization (~1/255 = 0.0039) and not a real behavioral change.
"""
import numpy as np
from PIL import Image
from grain_port import film_grain, value_noise, luma_of, LOOKS

LOOKS_EXT = dict(LOOKS)
LOOKS_EXT["Aerochrome_Dense"] = dict(preset=9, clump=1.1, bias=1.1, base=0.10)

MONO_MIX = {
    "HIE_mono": 0.0, "Rollei_mono": 0.0,
    "Ektar_color": 0.0, "CineStill": 0.0, "TriX_400": 1.0,
    "Aerochrome_Dense": 0.0,
}


def grain_clump_mask(g_ux, g_uy, seed):
    gx = g_ux / 4.0 + seed * 5.11
    gy = g_uy / 4.0 + seed * 8.87
    return 0.5 + value_noise(gx, gy)


def compute_delta(c, u_preset, grain_base, grain_bias, grain_clump, seed,
                   mono_mix_uniform, u_grain=0.0, enable_chroma=True, enable_clump=True):
    """Returns (delta, grain_amp) - the RAW pre-add, pre-clip signal."""
    h, w, _ = c.shape
    xs = (np.arange(w) + 0.5) / w
    ys = (np.arange(h) + 0.5) / h
    gx, gy = np.meshgrid(xs * 720.0, ys * 720.0)

    eff_grain = u_grain + grain_base
    if eff_grain <= 0.001:
        return np.zeros_like(c), np.zeros((h, w), dtype=np.float32)

    luma = luma_of(c)
    d = (luma - 0.42) / 0.30
    density_weight = np.exp(-d * d)
    grain_amp = eff_grain * 0.040 * density_weight * grain_bias

    g_ux = gx / max(grain_clump, 0.05)
    g_uy = gy / max(grain_clump, 0.05)
    n_luma = film_grain(g_ux, g_uy, seed)
    delta = np.stack([n_luma, n_luma, n_luma], axis=-1)

    if enable_chroma:
        chroma_amt = 0.0 if u_preset <= 5 else (1.0 - mono_mix_uniform)
        if chroma_amt > 0.001:
            n_cr = film_grain(g_ux * 1.7 + 31.7, g_uy * 1.7 + 11.3, seed + 7.0)
            n_cb = film_grain(g_ux * 1.7 - 19.1, g_uy * 1.7 + 47.7, seed + 13.0)
            chroma = np.stack([n_cr, -0.5 * (n_cr + n_cb), n_cb], axis=-1)
            delta = delta + chroma_amt * 0.35 * chroma

    mult = 1.0
    if enable_clump:
        mult = grain_clump_mask(g_ux, g_uy, seed)

    return delta * (grain_amp * 2.2 * mult)[..., None], grain_amp


def apply(c, *args, **kwargs):
    delta, _ = compute_delta(c, *args, **kwargs)
    return np.clip(c + delta, 0.0, 1.0).astype(np.float32), delta


def main():
    img = Image.open("reference_photo.jpg").convert("RGB")
    c = np.asarray(img).astype(np.float32) / 255.0
    h, w, _ = c.shape
    seed = 42.0
    u_grain = 0.0
    report = ["# Steps 3+4 pre-verification (corrected: raw-delta decorrelation, tolerant regression check)\n\n"]

    report.append("## Step 3: per-channel color grain + stale-uniform bug check\n\n")
    for name, look in LOOKS_EXT.items():
        mono_family = look["preset"] <= 5
        mono_mix = MONO_MIX[name]

        base_delta, _ = compute_delta(c, look["preset"], look["base"], look["bias"],
                                       look["clump"], seed, 0.0, u_grain,
                                       enable_chroma=False, enable_clump=False)
        new_delta, _ = compute_delta(c, look["preset"], look["base"], look["bias"],
                                      look["clump"], seed, mono_mix, u_grain,
                                      enable_chroma=True, enable_clump=False)

        rg = float((new_delta[..., 0] - new_delta[..., 1]).std())
        gb = float((new_delta[..., 1] - new_delta[..., 2]).std())
        close = bool(np.allclose(base_delta, new_delta, atol=1e-6))

        report.append(f"### {name} (uPreset={look['preset']}, monoMix={mono_mix}, mono_family={mono_family})\n")
        report.append(f"- Raw delta channel decorrelation: std(dR-dG)={rg:.6f}, std(dG-dB)={gb:.6f}\n")
        if mono_family or mono_mix >= 0.999:
            verdict = "PASS" if (rg < 1e-8 and gb < 1e-8) else "FAIL"
            report.append(f"  -> expect exactly 0 (scalar grain): {verdict}\n")
            report.append(f"  -> matches step-2 shipped delta (scalar-only): {'PASS' if close else 'FAIL'}\n")
        else:
            verdict = "PASS" if (rg > 1e-4 and gb > 1e-4) else "FAIL"
            report.append(f"  -> expect > 0 (independent per-channel noise present): {verdict}\n")

        if not mono_family and mono_mix < 0.5:
            stale_delta, _ = compute_delta(c, look["preset"], look["base"], look["bias"],
                                            look["clump"], seed, 1.0, u_grain,
                                            enable_chroma=True, enable_clump=False)
            bug_reproduced = bool(np.allclose(stale_delta, base_delta, atol=1e-6))
            report.append(f"  -> STALE-UNIFORM BUG CHECK (monoMix leaks as 1.0 from a prior Classic-Film "
                          f"frame, reset not applied): chroma grain would be "
                          f"{'SILENTLY DISABLED (bug reproduced)' if bug_reproduced else 'still present'}.\n")
        report.append("\n")

    report.append("## Step 4: clump-irregularity multiplier\n\n")
    xs = (np.arange(w) + 0.5) / w
    ys = (np.arange(h) + 0.5) / h
    gx, gy = np.meshgrid(xs * 720.0, ys * 720.0)
    for name, look in LOOKS_EXT.items():
        g_ux = gx / max(look["clump"], 0.05)
        g_uy = gy / max(look["clump"], 0.05)
        mask = grain_clump_mask(g_ux, g_uy, seed)
        report.append(f"- {name}: clump mask mean={mask.mean():.4f} (target ~1.0), "
                      f"min={mask.min():.3f}, max={mask.max():.3f}, std={mask.std():.4f}\n")

    hie = LOOKS_EXT["HIE_mono"]
    out_with, _ = apply(c, hie["preset"], hie["base"], hie["bias"], hie["clump"], seed, 0.0, u_grain,
                         enable_chroma=False, enable_clump=True)
    out_without, _ = apply(c, hie["preset"], hie["base"], hie["bias"], hie["clump"], seed, 0.0, u_grain,
                            enable_chroma=False, enable_clump=False)
    rms_with = float(np.sqrt(((out_with - c) ** 2).mean()))
    rms_without = float(np.sqrt(((out_without - c) ** 2).mean()))
    report.append(f"\n- HIE overall RMS delta: without clump={rms_without:.5f}, with clump={rms_with:.5f} "
                  f"(should stay close - mask is mean-preserving)\n")

    Image.fromarray((out_with * 255).clip(0, 255).astype(np.uint8)).save("step4_HIE_with_clump.png")
    Image.fromarray((out_without * 255).clip(0, 255).astype(np.uint8)).save("step4_HIE_without_clump.png")

    ektar = LOOKS_EXT["Ektar_color"]
    ektar_final, _ = apply(c, ektar["preset"], ektar["base"], ektar["bias"], ektar["clump"], seed, 0.0, u_grain,
                            enable_chroma=True, enable_clump=True)
    Image.fromarray((ektar_final * 255).clip(0, 255).astype(np.uint8)).save("step3and4_Ektar_final.png")

    aero = LOOKS_EXT["Aerochrome_Dense"]
    aero_final, _ = apply(c, aero["preset"], aero["base"], aero["bias"], aero["clump"], seed, 0.0, u_grain,
                           enable_chroma=True, enable_clump=True)
    Image.fromarray((aero_final * 255).clip(0, 255).astype(np.uint8)).save("step3and4_AerochromeDense_final.png")

    with open("STEP3_4_PREVERIFY.md", "w") as f:
        f.writelines(report)
    print("".join(report))


if __name__ == "__main__":
    main()

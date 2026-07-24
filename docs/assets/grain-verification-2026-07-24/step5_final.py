"""
Exact port of the grain + dither stage AS NOW SHIPPED (2026-07-24 fixes), and
the acceptance checks for each of the three questions device verification was
supposed to answer:

  Q1 does chroma grain read as film speckle rather than chromatic aberration?
  Q2 does clump irregularity overshoot into blotching on mono stocks?
  Q3 does the density curve actually make shadows readable?

Also emits a 1:1 proof strip per stock so the grain can be judged visually at
native pixel scale without needing to shoot anything.
"""
import numpy as np
from PIL import Image
from grain_port import film_grain, value_noise, luma_of

REC601 = np.array([0.299, 0.587, 0.114])


def shipped_grain(c, preset, mono_mix, base, bias, clump, seed=42.0, u_grain=0.0,
                  apply_dither=True, enable_clump=True, enable_chroma=True):
    """1:1 port of the current shader block."""
    h, w, _ = c.shape
    xs = (np.arange(w) + 0.5) / w
    ys = (np.arange(h) + 0.5) / h
    gxs, gys = np.meshgrid(xs * 720.0, ys * 720.0)

    eff = u_grain + base
    out = c.copy()
    dither_scale = np.ones((h, w))
    if eff > 0.001:
        gl = luma_of(c)
        d = (gl - 0.42) / 0.30
        dw = np.exp(-d * d)
        # smoothstep(0.34, 0.02, gLuma)
        t = np.clip((gl - 0.34) / (0.02 - 0.34), 0, 1)
        dw = np.maximum(dw, 0.62 * (t * t * (3 - 2 * t)))

        amp = eff * 0.040 * dw * bias
        dither_scale = 1.0 - np.clip(amp * 175.3, 0, 1) * 0.55

        gux, guy = gxs / max(clump, 0.05), gys / max(clump, 0.05)
        n_luma = film_grain(gux, guy, seed)
        delta = np.repeat(n_luma[..., None], 3, axis=2)

        chroma_amt = 0.0 if preset <= 5 else (1.0 - mono_mix)
        if enable_chroma and chroma_amt > 0.001:
            cux, cuy = gux / 1.8, guy / 1.8
            n_cr = film_grain(cux + 31.7, cuy + 11.3, seed + 7.0)
            n_cb = film_grain(cux - 19.1, cuy + 47.7, seed + 13.0)
            n_cg = -0.5094 * n_cr - 0.1942 * n_cb
            delta += chroma_amt * 0.35 * np.stack([n_cr, n_cg, n_cb], -1)

        if enable_clump:
            mask = 0.5 + value_noise(gux / 4.0 + seed * 5.11, guy / 4.0 + seed * 8.87)
        else:
            mask = np.ones((h, w))
        out = c + delta * (amp * 2.2)[..., None] * mask[..., None]

    if apply_dither:
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
        inner = 0.06711056 * xx + 0.00583715 * yy
        v = 52.9829189 * (inner - np.floor(inner))
        ign = (v - np.floor(v)) - 0.5
        out = out + (ign * 0.006 * dither_scale)[..., None]
    return np.clip(out, 0, 1)


def q8(a):
    return np.round(a * 255.0) / 255.0


STOCKS = [
    ("Rollei",   0, 0.0, 0.10, 1.00, 1.00),
    ("HIE",      1, 0.0, 0.24, 1.20, 1.25),
    ("FineGrain",4, 0.0, 0.03, 0.65, 0.55),
    ("Ektar",    6, 0.0, 0.02, 0.60, 0.45),
    ("CineStill",7, 0.0, 0.14, 1.00, 1.05),
    ("TriX",     8, 1.0, 0.26, 1.15, 1.35),
    ("AeroDense",9, 0.0, 0.10, 1.10, 1.10),
]


def q1_chroma_reads_as_film():
    print("=" * 84)
    print("Q1  chroma grain: film speckle, or chromatic aberration?")
    print("=" * 84)
    print("  Test: on a flat neutral mid-grey patch, measure (a) luma noise injected by")
    print("  the chroma term, (b) chroma feature size vs luma feature size.")
    from grain_analysis import radial_spectrum, highpass

    def centroid(f):
        p = radial_spectrum(f)
        i = np.arange(len(p)) / (len(p) - 1)
        return float((p * i).sum() / p.sum())

    patch = np.full((512, 512, 3), 0.45)
    for name, preset, mm, base, bias, clump in STOCKS:
        lum_only = shipped_grain(patch, preset, mm, base, bias, clump,
                                 apply_dither=False, enable_chroma=False)
        full = shipped_grain(patch, preset, mm, base, bias, clump,
                             apply_dither=False, enable_chroma=True)
        chroma_component = full - lum_only
        luma_leak = float(luma_of(chroma_component).std())
        chroma_energy = float(chroma_component.std())
        y = luma_of(full)
        cr = full[..., 0] - y
        cl, cc = centroid(highpass(y)), centroid(highpass(cr))
        ratio = (cc / cl) if cl > 0 else float("nan")
        verdict = "OK film speckle" if (ratio < 1.0 and luma_leak < 1e-4) else \
                  ("mono (no chroma)" if chroma_energy < 1e-9 else "CHECK")
        print(f"  {name:<10} chroma_energy={chroma_energy:.6f}  "
              f"luma_leak={luma_leak:.2e}  chroma/luma_size={ratio:>5.2f}  {verdict}")


def q2_clump_blotching():
    print("\n" + "=" * 84)
    print("Q2  clump irregularity: does it overshoot into blotching on mono?")
    print("=" * 84)
    print("  Test: flat mid-grey patch, compare LOW-FREQUENCY energy with clump on vs off.")
    print("  Blotching = the clump mask leaking visible large-scale brightness variation.")
    patch = np.full((512, 512, 3), 0.42)
    for name, preset, mm, base, bias, clump in STOCKS:
        if preset > 5:
            continue
        off = q8(shipped_grain(patch, preset, mm, base, bias, clump,
                               apply_dither=True, enable_clump=False))
        on = q8(shipped_grain(patch, preset, mm, base, bias, clump,
                              apply_dither=True, enable_clump=True))

        def lowfreq(img):
            y = luma_of(img)
            k = 16
            small = y[:512 // k * k, :512 // k * k].reshape(512 // k, k, 512 // k, k).mean(axis=(1, 3))
            return float(small.std() * 255.0)

        lo_off, lo_on = lowfreq(off), lowfreq(on)
        tot_off, tot_on = float(luma_of(off).std() * 255), float(luma_of(on).std() * 255)
        print(f"  {name:<10} lowfreq_LSB off={lo_off:.4f} on={lo_on:.4f} "
              f"(delta {lo_on-lo_off:+.4f})  total_LSB {tot_off:.3f}->{tot_on:.3f}  "
              f"{'OK' if (lo_on - lo_off) < 0.30 else 'BLOTCH RISK'}")
    print("  (blotch threshold 0.30 LSB of 16x16-block brightness variation; below that")
    print("   the clumping modulates grain visibility without reading as patches)")


def q3_shadow_readability():
    print("\n" + "=" * 84)
    print("Q3  shadow readability: is grain actually visible in deep shadow now?")
    print("=" * 84)
    print("  Test: flat patches at real shadow tones, texture surviving 8-bit quantisation.")
    print("  'grain_share' = fraction of visible texture that is film grain (rest is dither).")
    for name, preset, mm, base, bias, clump in STOCKS:
        row = []
        for tone in (0.03, 0.08, 0.15):
            patch = np.full((256, 256, 3), tone)
            wg = q8(shipped_grain(patch, preset, mm, base, bias, clump))
            ng = q8(shipped_grain(patch, preset, mm, 0.0, bias, clump))
            tot = float(luma_of(wg).std() * 255)
            grain = float(luma_of(wg - ng).std() * 255)
            row.append((tone, tot, grain / max(tot, 1e-9)))
        s = "  ".join(f"t={t:.2f} tex={v:.3f}LSB share={sh:.2f}" for t, v, sh in row)
        print(f"  {name:<10} {s}")


def proof_strips():
    """1:1 proof render: tone ramp x stock, so grain can be judged visually
    at native pixel scale without shooting anything."""
    tones = [0.03, 0.06, 0.12, 0.22, 0.42, 0.65, 0.85]
    tile = 150
    rows = []
    for name, preset, mm, base, bias, clump in STOCKS:
        cells = []
        for t in tones:
            patch = np.full((tile, tile, 3), t)
            cells.append(q8(shipped_grain(patch, preset, mm, base, bias, clump)))
        rows.append(np.concatenate(cells, axis=1))
    strip = np.concatenate(rows, axis=0)
    Image.fromarray((strip * 255).astype(np.uint8)).save("grain_proof_strip_2026-07-24.png")

    # amplified view: same data, deviation from local mean pushed 8x so the
    # structure is legible on any display. NOT what ships - a diagnostic.
    amp = strip.copy()
    y = luma_of(amp)
    k = 25
    hh, ww = y.shape
    base_img = y[:hh // k * k, :ww // k * k].reshape(hh // k, k, ww // k, k).mean(axis=(1, 3))
    base_img = np.repeat(np.repeat(base_img, k, 0), k, 1)
    dev = (y[:base_img.shape[0], :base_img.shape[1]] - base_img) * 8.0 + 0.5
    Image.fromarray((np.clip(dev, 0, 1) * 255).astype(np.uint8)).save(
        "grain_proof_strip_2026-07-24_amplified8x.png")
    print("\n  wrote grain_proof_strip_2026-07-24.png "
          f"({strip.shape[1]}x{strip.shape[0]}, rows=stocks, cols=tones {tones})")
    print("  wrote grain_proof_strip_2026-07-24_amplified8x.png (deviation x8, diagnostic)")


if __name__ == "__main__":
    q1_chroma_reads_as_film()
    q2_clump_blotching()
    q3_shadow_readability()
    proof_strips()

"""
End-to-end flat-patch simulation of the REAL output path:

    tone -> grain -> IGN dither -> clamp -> 8-bit quantise

and the decomposition that matters: how much of the texture a viewer sees in
each tone zone is FILM GRAIN, and how much is the screen-space IGN dither
that runs after it. In deep shadow the shipped build's grain amplitude falls
below the dither amplitude, so the visible texture there is the dither's fine
deterministic pattern rather than the grain's clump structure. That is what
"dead flat / digital-looking blacks" actually is.

Everything is measured on the real generators (filmGrain, the real IGN
expression from the shader), not on gaussian stand-ins.
"""
import numpy as np
from grain_port import film_grain

REC601 = np.array([0.299, 0.587, 0.114])
PATCH = 512


def ign_field(w=PATCH, h=PATCH, x0=0, y0=0):
    """Exact port of the shader's interleaved-gradient-noise dither."""
    yy, xx = np.mgrid[y0:y0 + h, x0:x0 + w].astype(np.float64)
    inner = 0.06711056 * xx + 0.00583715 * yy
    v = 52.9829189 * (inner - np.floor(inner))
    return (v - np.floor(v)) - 0.5


def grain_field(clump=1.0, seed=42.0, w=PATCH, h=PATCH):
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    return film_grain(xx / max(clump, 0.05), yy / max(clump, 0.05), seed)


def dw_shipped(luma):
    d = (luma - 0.42) / 0.30
    return np.exp(-d * d)


def dw_fixed(luma, floor=0.62, lo=0.02, hi=0.34):
    g = dw_shipped(luma)
    t = np.clip((hi - luma) / (hi - lo), 0.0, 1.0)
    return np.maximum(g, floor * (t * t * (3.0 - 2.0 * t)))


def render_patch(tone, base, bias, clump, dw_fn, pedestal=0.0, with_grain=True):
    lum = tone * (1.0 - pedestal) + pedestal
    dw = dw_fn(np.array([lum]))[0]
    amp = base * 0.040 * dw * bias * 2.2
    c = np.full((PATCH, PATCH), lum)
    if with_grain:
        c = c + grain_field(clump) * amp
    c = c + ign_field() * 0.006
    q = np.round(np.clip(c, 0, 1) * 255.0)
    return q, amp * 255.0


def texture_breakdown(tone, base, bias, clump, dw_fn, pedestal=0.0):
    with_g, amp_lsb = render_patch(tone, base, bias, clump, dw_fn, pedestal, True)
    no_g, _ = render_patch(tone, base, bias, clump, dw_fn, pedestal, False)
    total = float(with_g.std())
    dither_only = float(no_g.std())
    # grain's own contribution to the visible texture
    grain_contrib = float((with_g - no_g).std())
    return dict(amp_lsb=amp_lsb, total=total, dither=dither_only, grain=grain_contrib)


def main():
    stocks = [("Rollei", 0.10, 1.00, 1.00),
              ("HIE", 0.24, 1.20, 1.25),
              ("Ektar", 0.02, 0.60, 0.45),
              ("TriX", 0.26, 1.15, 1.35)]
    tones = [0.03, 0.08, 0.15, 0.25, 0.42, 0.70]

    print("=" * 84)
    print("VISIBLE TEXTURE DECOMPOSITION (8-bit LSB units, flat patch)")
    print("  grain = texture contributed by film grain")
    print("  dith  = texture contributed by the post-grain IGN dither")
    print("  ratio = grain/dither. <1.0 means the DITHER dominates what you see.")
    print("=" * 84)

    for name, base, bias, clump in stocks:
        print(f"\n{name}  (grainBase={base}, bias={bias}, clump={clump})")
        print(f"  {'tone':>5} | {'--------- SHIPPED ---------':^28} | "
              f"{'---------- FIXED ----------':^28}")
        print(f"  {'':>5} | {'amp':>6} {'grain':>6} {'dith':>6} {'ratio':>6} | "
              f"{'amp':>6} {'grain':>6} {'dith':>6} {'ratio':>6}")
        for t in tones:
            s = texture_breakdown(t, base, bias, clump, dw_shipped)
            f = texture_breakdown(t, base, bias, clump, dw_fixed, pedestal=1.5 / 255)
            sr = s["grain"] / max(s["dither"], 1e-9)
            fr = f["grain"] / max(f["dither"], 1e-9)
            flag = "  <-- dither dominates" if sr < 1.0 else ""
            print(f"  {t:>5.2f} | {s['amp_lsb']:>6.2f} {s['grain']:>6.3f} "
                  f"{s['dither']:>6.3f} {sr:>6.2f} | "
                  f"{f['amp_lsb']:>6.2f} {f['grain']:>6.3f} "
                  f"{f['dither']:>6.3f} {fr:>6.2f}{flag}")

    print("\n" + "=" * 84)
    print("REGRESSION GUARD - midtone/highlight density weight must be untouched")
    print("=" * 84)
    ok = True
    for t in np.arange(0.34, 1.001, 0.02):
        a, b = dw_shipped(np.array([t]))[0], dw_fixed(np.array([t]))[0]
        if abs(a - b) > 1e-12:
            ok = False
            print(f"  luma={t:.2f} CHANGED {a:.6f} -> {b:.6f}")
    print(f"  all tones >= 0.34: {'IDENTICAL (highlight protection preserved)' if ok else 'CHANGED'}")

    print("\n" + "=" * 84)
    print("CHROMA AXIS")
    print("=" * 84)
    gr, gb = -REC601[0] / REC601[1], -REC601[2] / REC601[1]
    rng = np.random.default_rng(7)
    nCr, nCb = rng.normal(size=400000), rng.normal(size=400000)
    ship = np.stack([nCr, -0.5 * (nCr + nCb), nCb], -1) @ REC601
    fix = np.stack([nCr, gr * nCr + gb * nCb, nCb], -1) @ REC601
    print(f"  green coeff shipped: -0.5*nCr + -0.5*nCb")
    print(f"  green coeff fixed  : {gr:.4f}*nCr + {gb:.4f}*nCb")
    print(f"  luma leak per unit chroma:  shipped={ship.std():.5f}  fixed={fix.std():.2e}")
    print(f"  -> shipped chroma term was injecting extra LUMA noise equal to "
          f"{ship.std()*100:.1f}% of the chroma amplitude")

    print("\n  chroma feature size (spectral centroid, 0=coarse 1=fine):")
    from grain_analysis import radial_spectrum
    def cent(f):
        p = radial_spectrum(f)
        i = np.arange(len(p)) / (len(p) - 1)
        return float((p * i).sum() / p.sum())
    yy, xx = np.mgrid[0:PATCH, 0:PATCH].astype(np.float64)
    L = film_grain(xx, yy, 42.0)
    Cs = film_grain(xx * 1.7 + 31.7, yy * 1.7 + 11.3, 49.0)
    Cf = film_grain(xx / 1.8 + 31.7, yy / 1.8 + 11.3, 49.0)
    cl, cs, cf = cent(L), cent(Cs), cent(Cf)
    print(f"    luma            = {cl:.4f}")
    print(f"    chroma shipped  = {cs:.4f}  ({cs/cl:.2f}x luma) FINER -> reads as fringing")
    print(f"    chroma fixed    = {cf:.4f}  ({cf/cl:.2f}x luma) COARSER -> reads as dye clouds")


if __name__ == "__main__":
    main()

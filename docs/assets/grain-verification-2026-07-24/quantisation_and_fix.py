"""
Root-cause: why shadow grain vanishes, and does the proposed fix restore it.

Audit 3 showed the black CLAMP barely fires (grain at luma 0.05 survives the
clamp fine). That was the wrong suspect. The real killer is 8-BIT
QUANTISATION: in deep shadow the grain excursion falls below half an LSB, so
it rounds away entirely and the region renders as a dead flat plateau — which
is exactly what the pool captures show.

This measures surviving grain AFTER quantisation to 8 bits, which is what the
user actually sees, and evaluates the proposed shadow-floor fix against it.
"""
import numpy as np
from grain_port import film_grain

REC601 = np.array([0.299, 0.587, 0.114])


def density_weight_shipped(luma):
    d = (luma - 0.42) / 0.30
    return np.exp(-d * d)


def density_weight_fixed(luma, floor=0.62, lo=0.02, hi=0.34):
    """Gaussian, plus a deep-shadow floor.

    Rationale: film granularity does fall at low density, but print grain
    VISIBILITY does not fall as fast — shadow grain is a defining feature of
    pushed B&W stock. More practically, the shipped curve drops grain below
    the 8-bit quantisation step, so 'less grain' becomes 'no grain at all'.
    The floor only lifts the deep end; midtone and highlight are untouched, so
    highlight protection is preserved exactly."""
    g = density_weight_shipped(luma)
    t = np.clip((hi - luma) / (hi - lo), 0.0, 1.0)
    smooth = t * t * (3.0 - 2.0 * t)          # smoothstep(hi, lo, luma)
    return np.maximum(g, floor * smooth)


def surviving_grain_after_quantisation(luma, eff_grain, bias, dw_fn, pedestal=0.0, n=400000):
    """Render a flat patch at `luma`, add grain, quantise to 8 bits, and
    measure the grain that actually survives into the output pixels."""
    lum = np.full(n, luma, dtype=np.float64)
    lum = lum * (1.0 - pedestal) + pedestal          # film base-fog pedestal
    dw = dw_fn(lum)
    amp = eff_grain * 0.040 * dw * bias * 2.2
    # filmGrain output distribution, sampled from the real generator
    xs = np.arange(n) % 2000
    ys = np.arange(n) // 2000
    g = film_grain(xs.astype(np.float64), ys.astype(np.float64), 42.0)
    out = np.clip(lum + g * amp, 0.0, 1.0)
    q = np.round(out * 255.0) / 255.0
    return float(q.std() * 255.0), float(amp.mean() * 255.0)


def main():
    print("=" * 78)
    print("ROOT CAUSE: grain surviving 8-bit quantisation, by tone")
    print("=" * 78)
    stocks = [("Rollei  base=0.10", 0.10, 1.00),
              ("HIE     base=0.24", 0.24, 1.20),
              ("Ektar   base=0.02", 0.02, 0.60)]
    tones = [0.02, 0.05, 0.10, 0.20, 0.42, 0.70]

    for name, base, bias in stocks:
        print(f"\n{name}")
        print(f"  {'tone':>6} | {'SHIPPED amp':>11} {'survives':>9} | "
              f"{'FIXED amp':>10} {'survives':>9}")
        for t in tones:
            s_std, s_amp = surviving_grain_after_quantisation(
                t, base, bias, density_weight_shipped)
            f_std, f_amp = surviving_grain_after_quantisation(
                t, base, bias, density_weight_fixed, pedestal=1.5 / 255)
            flag = ""
            if s_std < 0.30:
                flag = "  <-- SHIPPED: dead flat"
            print(f"  {t:>6.2f} | {s_amp:>9.2f}LSB {s_std:>8.3f} | "
                  f"{f_amp:>8.2f}LSB {f_std:>8.3f}{flag}")

    print("\n" + "=" * 78)
    print("REGRESSION GUARD: midtone and highlight must be unchanged by the floor")
    print("=" * 78)
    for t in (0.42, 0.55, 0.70, 0.85, 0.95):
        a = density_weight_shipped(np.array([t]))[0]
        b = density_weight_fixed(np.array([t]))[0]
        print(f"  luma={t:.2f}  shipped_dw={a:.5f}  fixed_dw={b:.5f}  "
              f"delta={b-a:+.6f}  {'IDENTICAL' if abs(b-a) < 1e-9 else 'CHANGED'}")

    print("\n" + "=" * 78)
    print("CHROMA AXIS FIX: luma neutrality")
    print("=" * 78)
    gr, gb = -REC601[0] / REC601[1], -REC601[2] / REC601[1]
    rng = np.random.default_rng(7)
    nCr, nCb = rng.normal(size=300000), rng.normal(size=300000)
    ship = np.stack([nCr, -0.5 * (nCr + nCb), nCb], -1) @ REC601
    fixed = np.stack([nCr, gr * nCr + gb * nCb, nCb], -1) @ REC601
    print(f"  shipped green coeff: -0.5*(nCr+nCb)")
    print(f"  fixed   green coeff: {gr:.4f}*nCr + {gb:.4f}*nCb")
    print(f"  luma noise injected per unit chroma noise:")
    print(f"    shipped = {ship.std():.5f}")
    print(f"    fixed   = {fixed.std():.5f}   ({ship.std()/max(fixed.std(),1e-9):.0f}x reduction)")

    # chroma saturation excursion must stay comparable, not collapse
    ship_rgb = np.stack([nCr, -0.5 * (nCr + nCb), nCb], -1)
    fix_rgb = np.stack([nCr, gr * nCr + gb * nCb, nCb], -1)
    print(f"  chroma excursion preserved (std over RGB):"
          f" shipped={ship_rgb.std():.4f}  fixed={fix_rgb.std():.4f}")


if __name__ == "__main__":
    main()

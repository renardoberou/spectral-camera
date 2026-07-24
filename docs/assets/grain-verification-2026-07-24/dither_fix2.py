"""
Revised, conservative grain-aware dither + a REAL banding test.

Two corrections to the first attempt:

1. The ratio metric divided by the dither-only std, which goes to ~0 once the
   dither is suppressed -> meaningless huge numbers. Replaced with
   grain_share = grain_texture / total_texture (bounded 0..1).

2. displace=0.85 / ref=1.6 was too aggressive and tripped the banding guard.
   Banding lives in SMOOTH BRIGHT GRADIENTS (sky), which is exactly where the
   density curve makes grain weakest - so aggressive displacement removes
   dither precisely where it is most needed. Revised to displace=0.55,
   ref=3.2 LSB: only stocks with genuinely strong grain displace much dither,
   and bright/sky tones keep nearly all of theirs.

   The shader's separate sky dither assist (ign * skyMask * 0.003) is NOT
   touched by this at all.

Banding is tested on an actual smooth ramp, by counting quantisation plateau
runs - the thing that is visible as a band - rather than inferred from a flat
patch std.
"""
import numpy as np
from final_verify import ign_field, grain_field, dw_shipped, dw_fixed, PATCH

DITHER_BASE = 0.006


def dither_scale(amp_lsb, displace=0.55, ref=3.2):
    return 1.0 - np.clip(amp_lsb / ref, 0.0, 1.0) * displace


# ---------------------------------------------------------------------------
# flat-patch texture composition (bounded metric)
# ---------------------------------------------------------------------------

def compose(tone, base, bias, clump, dw_fn, gad):
    dw = dw_fn(np.array([tone]))[0]
    amp = base * 0.040 * dw * bias * 2.2
    amp_lsb = amp * 255.0
    ds = float(dither_scale(amp_lsb)) if gad else 1.0
    g = grain_field(clump) * amp
    d = ign_field() * DITHER_BASE * ds
    base_c = np.full((PATCH, PATCH), float(tone))
    wg = np.round(np.clip(base_c + g + d, 0, 1) * 255.0)
    ng = np.round(np.clip(base_c + d, 0, 1) * 255.0)
    total = float(wg.std())
    grain_tex = float((wg - ng).std())
    share = grain_tex / max(total, 1e-9)
    return dict(amp=amp_lsb, ds=ds, total=total, share=min(share, 1.0))


# ---------------------------------------------------------------------------
# real banding test on a smooth ramp
# ---------------------------------------------------------------------------

def banding_score(tone_lo, tone_hi, base, bias, clump, dw_fn, gad, h=256, w=1024):
    """Render a smooth horizontal ramp, quantise, and measure banding as the
    mean run-length of identical quantised values along a scanline. A clean
    dithered ramp breaks into short runs; a banded ramp has long flat plateaus."""
    xs = np.linspace(tone_lo, tone_hi, w)
    ramp = np.tile(xs, (h, 1))
    dw = dw_fn(ramp)
    amp = base * 0.040 * dw * bias * 2.2
    amp_lsb = amp * 255.0
    ds = dither_scale(amp_lsb) if gad else np.ones_like(amp_lsb)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    g = grain_field(clump, w=w, h=h) * amp
    inner = 0.06711056 * xx + 0.00583715 * yy
    v = 52.9829189 * (inner - np.floor(inner))
    ign = (v - np.floor(v)) - 0.5
    q = np.round(np.clip(ramp + g + ign * DITHER_BASE * ds, 0, 1) * 255.0)
    runs = []
    for r in range(0, h, 16):
        row = q[r]
        change = np.flatnonzero(np.diff(row) != 0)
        if len(change) > 1:
            runs.append(np.mean(np.diff(change)))
    return float(np.mean(runs)) if runs else float(w)


def main():
    stocks = [("Rollei", 0.10, 1.00, 1.00),
              ("HIE", 0.24, 1.20, 1.25),
              ("Ektar", 0.02, 0.60, 0.45),
              ("TriX", 0.26, 1.15, 1.35)]
    tones = [0.03, 0.08, 0.15, 0.25, 0.42, 0.70]

    print("=" * 88)
    print("TEXTURE COMPOSITION  (share = fraction of visible texture that is FILM GRAIN)")
    print("=" * 88)
    for name, base, bias, clump in stocks:
        print(f"\n{name}")
        print(f"  {'tone':>5} | {'--- SHIPPED ---':^24} | {'--- FIXED ---':^31}")
        print(f"  {'':>5} | {'amp':>6} {'share':>6} {'total':>6} | "
              f"{'amp':>6} {'dscale':>6} {'share':>6} {'total':>6}")
        for t in tones:
            s = compose(t, base, bias, clump, dw_shipped, False)
            f = compose(t, base, bias, clump, dw_fixed, True)
            print(f"  {t:>5.2f} | {s['amp']:>6.2f} {s['share']:>6.2f} {s['total']:>6.3f} | "
                  f"{f['amp']:>6.2f} {f['ds']:>6.2f} {f['share']:>6.2f} {f['total']:>6.3f}")

    print("\n" + "=" * 88)
    print("BANDING TEST on smooth ramps (mean quantisation run-length, lower = better)")
    print("  reference: an UNDITHERED ramp is the failure case")
    print("=" * 88)
    ramps = [("deep shadow 0.00-0.12", 0.00, 0.12),
             ("shadow      0.05-0.25", 0.05, 0.25),
             ("sky/bright  0.55-0.85", 0.55, 0.85),
             ("highlight   0.80-1.00", 0.80, 1.00)]
    worst = 0.0
    for name, base, bias, clump in stocks:
        print(f"\n{name}")
        for rn, lo, hi in ramps:
            und = banding_score(lo, hi, base, bias, clump, dw_shipped, False, h=64)
            # undithered reference
            xs = np.linspace(lo, hi, 1024)
            qr = np.round(np.clip(np.tile(xs, (8, 1)), 0, 1) * 255.0)
            ch = np.flatnonzero(np.diff(qr[0]) != 0)
            ref = float(np.mean(np.diff(ch))) if len(ch) > 1 else 1024.0
            fx = banding_score(lo, hi, base, bias, clump, dw_fixed, True, h=64)
            worst = max(worst, fx)
            flag = "  <-- REGRESSION" if fx > und * 1.35 else ""
            print(f"  {rn:<22} undithered={ref:>6.1f}  shipped={und:>5.2f}  "
                  f"fixed={fx:>5.2f}{flag}")

    print(f"\n  worst fixed run-length across all cases: {worst:.2f}")
    print(f"  verdict: {'PASS - no banding regression' if worst < 3.0 else 'CHECK - inspect above'}")


if __name__ == "__main__":
    main()

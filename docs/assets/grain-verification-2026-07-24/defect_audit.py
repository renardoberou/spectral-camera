"""
Two jobs:

(A) Measure grain on FLAT patches only. Whole-image chroma/highpass statistics
    are dominated by scene content (edges, foliage colour), not grain. Flat
    patches — low variance at coarse scale — are where grain is the only
    signal, so that is where grain must be measured.

(B) Audit the SHIPPED grain math (steps 3+4, commit 5d1b28b) for defects that
    the existing pre-verification did not test for. The prior check confirmed
    "chroma noise is present / mono unchanged / clump mask is mean-preserving".
    It never checked whether the chroma axis is luma-neutral, what spatial
    frequency the chroma noise sits at, or what happens at the black point.
"""
import numpy as np
from PIL import Image
from grain_analysis import luma_of, highpass, radial_spectrum
from grain_port import film_grain, value_noise


# ---------------------------------------------------------------------------
# (A) flat-patch grain measurement
# ---------------------------------------------------------------------------

def flat_patches(rgb, size=32, keep=0.20):
    """Return (patch, mean_luma) for the flattest `keep` fraction of tiles.
    Flatness judged on a 4x-downsampled tile so grain itself doesn't
    disqualify a patch for being 'not flat'."""
    y = luma_of(rgb)
    h, w = y.shape
    out = []
    for ty in range(0, h - size, size):
        for tx in range(0, w - size, size):
            t = y[ty:ty + size, tx:tx + size]
            coarse = t.reshape(size // 4, 4, size // 4, 4).mean(axis=(1, 3))
            out.append((float(coarse.std()), ty, tx, float(t.mean())))
    out.sort(key=lambda r: r[0])
    n = max(1, int(len(out) * keep))
    return [(rgb[ty:ty + size, tx:tx + size], m) for _, ty, tx, m in out[:n]]


def measure_flat(path, label):
    rgb = np.asarray(Image.open(path).convert("RGB")).astype(np.float32) / 255.0
    patches = flat_patches(rgb)
    bins = {"deep<0.10": [], "shadow0.10-0.25": [], "mid0.25-0.60": [], "high>0.60": []}
    chroma = {k: [] for k in bins}
    for p, m in patches:
        k = ("deep<0.10" if m < 0.10 else "shadow0.10-0.25" if m < 0.25
             else "mid0.25-0.60" if m < 0.60 else "high>0.60")
        bins[k].append(float(highpass(luma_of(p)).std()))
        hr, hg = highpass(p[..., 0]), highpass(p[..., 1])
        chroma[k].append(float((hr - hg).std()))
    print(f"\n--- {label}: grain on FLAT patches only (n_patches={len(patches)}) ---")
    for k in bins:
        if bins[k]:
            print(f"  {k:<18} n={len(bins[k]):>4}  luma_grain_std={np.mean(bins[k]):.5f}"
                  f"   chroma_grain_std={np.mean(chroma[k]):.5f}")
    ref = np.mean(bins["mid0.25-0.60"]) if bins["mid0.25-0.60"] else float("nan")
    deep = np.mean(bins["deep<0.10"]) if bins["deep<0.10"] else float("nan")
    if ref == ref and deep == deep:
        print(f"  >> deep-shadow grain is {deep/ref:.2f}x midtone grain "
              f"({'TOO WEAK - shadows read as dead flat' if deep/ref < 0.55 else 'ok'})")
    return bins


# ---------------------------------------------------------------------------
# (B) shipped-math defect audit
# ---------------------------------------------------------------------------

REC601 = np.array([0.299, 0.587, 0.114])


def audit_chroma_luma_neutrality():
    """SHIPPED: grainDelta += chromaAmt*0.35*vec3(nCr, -0.5*(nCr+nCb), nCb)

    A chroma term must not move luma, or it stops being chroma noise and
    becomes extra (colour-tinted) luma noise. Solve symbolically."""
    print("\n=== DEFECT AUDIT 1: is the chroma axis luma-neutral? ===")
    # luma delta coefficients w.r.t. nCr and nCb for the shipped axis
    kr_shipped = REC601[0] + REC601[1] * (-0.5)          # coeff of nCr
    kb_shipped = REC601[2] + REC601[1] * (-0.5)          # coeff of nCb
    print(f"  shipped  vec3(nCr, -0.5*(nCr+nCb), nCb):")
    print(f"    luma leak per unit nCr = {kr_shipped:+.4f}")
    print(f"    luma leak per unit nCb = {kb_shipped:+.4f}   <-- should be ~0")
    # exact luma-preserving green coefficient
    gr = -REC601[0] / REC601[1]
    gb = -REC601[2] / REC601[1]
    print(f"  corrected vec3(nCr, {gr:.4f}*nCr + {gb:.4f}*nCb, nCb):")
    print(f"    luma leak per unit nCr = {REC601[0] + REC601[1]*gr:+.4f}")
    print(f"    luma leak per unit nCb = {REC601[2] + REC601[1]*gb:+.4f}")

    rng = np.random.default_rng(0)
    nCr, nCb = rng.normal(size=200000), rng.normal(size=200000)
    ship = np.stack([nCr, -0.5 * (nCr + nCb), nCb], -1) @ REC601
    corr = np.stack([nCr, gr * nCr + gb * nCb, nCb], -1) @ REC601
    print(f"  Monte-Carlo luma-noise injected by the chroma term:")
    print(f"    shipped   std = {ship.std():.4f}  (of unit chroma noise)")
    print(f"    corrected std = {corr.std():.4f}")
    print(f"  >> shipped chroma term leaks {ship.std():.3f} units of LUMA noise "
          f"per unit chroma noise - it is not a pure chroma axis.")
    return gr, gb


def audit_chroma_frequency():
    """SHIPPED: cUv = gUv * 1.7  -> chroma sampled at HIGHER uv rate.
    filmGrain divides uv by 1.6/3.4, so a larger uv multiplier means a
    HIGHER spatial frequency = FINER features."""
    print("\n=== DEFECT AUDIT 2: chroma grain feature size vs luma ===")
    n = 512
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float64)
    luma = film_grain(xx, yy, 42.0)
    chroma_shipped = film_grain(xx * 1.7 + 31.7, yy * 1.7 + 11.3, 49.0)
    chroma_fixed = film_grain(xx / 1.7 + 31.7, yy / 1.7 + 11.3, 49.0)

    def centroid(f):
        prof = radial_spectrum(f)
        idx = np.arange(len(prof)) / (len(prof) - 1)
        return float((prof * idx).sum() / prof.sum())

    cl, cs, cf = centroid(luma), centroid(chroma_shipped), centroid(chroma_fixed)
    print(f"  spectral centroid (0=coarse, 1=fine):")
    print(f"    luma grain            = {cl:.4f}")
    print(f"    chroma SHIPPED (x1.7) = {cs:.4f}  ratio vs luma = {cs/cl:.2f}x")
    print(f"    chroma FIXED   (/1.7) = {cf:.4f}  ratio vs luma = {cf/cl:.2f}x")
    print("  >> shipped chroma noise is FINER than luma noise. Real colour film "
          "dye clouds are\n     COARSER than silver grains, and human chroma acuity "
          "is ~3x lower than luma.\n     Fine high-frequency chroma noise is exactly "
          "what reads as colour fringing /\n     chromatic aberration rather than film "
          "speckle.")


def audit_black_point(capture_path):
    """Symmetric additive grain cannot survive a hard black floor: the negative
    half of each excursion is clipped, leaving one-sided speckle and a lifted
    local mean. Real film never reaches zero density (base+fog)."""
    print("\n=== DEFECT AUDIT 3: grain survival at the black point ===")
    rgb = np.asarray(Image.open(capture_path).convert("RGB")).astype(np.float32) / 255.0
    y = luma_of(rgb)
    for t, name in ((0.0, "exactly 0"), (2 / 255, "<=2/255"), (8 / 255, "<=8/255")):
        print(f"  real capture: fraction {name:>9} = {float((y <= t).mean()):.4f}")

    # Rollei at default: grainBase 0.10, bias 1.0
    eff, bias = 0.10, 1.0
    for lum in (0.0, 0.004, 0.012, 0.03, 0.42):
        d = (lum - 0.42) / 0.30
        amp = eff * 0.040 * np.exp(-d * d) * bias * 2.2
        rng = np.random.default_rng(1)
        g = (rng.random(200000) - 0.5) * 2 * amp * 0.5  # filmGrain is ~[-0.5,0.5]
        clipped = np.clip(lum + g, 0, 1)
        survived = clipped.std() / (g.std() + 1e-12)
        print(f"  luma={lum:.3f}: grain amp={amp:.5f} ({amp*255:.2f} LSB)  "
              f"surviving fraction after clamp = {survived:.3f}"
              f"{'   <-- grain destroyed' if survived < 0.9 else ''}")
    print("  >> below ~1.5x the grain amplitude the black clamp half-wave "
          "rectifies grain.\n     A film base-fog pedestal (real film has no true "
          "zero) both fixes this and is\n     physically correct.")


def audit_density_floor():
    print("\n=== DEFECT AUDIT 4: density curve value in deep shadow ===")
    for lum in (0.0, 0.02, 0.05, 0.10, 0.20, 0.42):
        d = (lum - 0.42) / 0.30
        print(f"  luma={lum:.2f}  densityWeight={np.exp(-d*d):.4f}")
    print("  >> pool/shadow tones sit at 0.17-0.30 of peak grain BEFORE the black "
          "clamp\n     removes most of what is left. Together these are why shadows "
          "read as dead flat.")


if __name__ == "__main__":
    measure_flat("/mnt/user-data/uploads/1000226941.jpg", "mono IR pool capture")
    measure_flat("/mnt/user-data/uploads/1000226931.jpg", "Aerochrome foliage capture")
    audit_chroma_luma_neutrality()
    audit_chroma_frequency()
    audit_black_point("/mnt/user-data/uploads/1000226941.jpg")
    audit_density_floor()

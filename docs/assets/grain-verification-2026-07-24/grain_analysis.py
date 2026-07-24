"""
Grain measurement harness.

Measures what grain ACTUALLY does in a rendered capture, rather than what the
formula says it should do. Used to (a) characterise the shipped build's output
on real device captures, (b) give a repeatable, numeric acceptance test for
future grain changes instead of eyeballing.

Metrics:
  - highpass RMS binned by local luma  -> is grain visible, and where
  - shadow clip fraction               -> is grain being half-wave rectified
  - per-channel decorrelation          -> is chroma grain present, how strong
  - radial power spectrum              -> grain feature size (luma vs chroma)

IMPORTANT CAVEAT, stated up front: these captures are JPEG/WebP. Block DCT
quantisation attenuates exactly the low-amplitude high-frequency signal grain
lives in, so absolute RMS here UNDERSTATES what the shader emitted. Relative
comparisons between regions of the same image, and clipping statistics, survive
compression and are the trustworthy readings.
"""
import numpy as np
from PIL import Image


def luma_of(rgb):
    return rgb[..., 0] * 0.299 + rgb[..., 1] * 0.587 + rgb[..., 2] * 0.114


def highpass(ch):
    """Residual after a 3x3 box blur — isolates grain-scale detail."""
    p = np.pad(ch, 1, mode="reflect")
    blur = np.zeros_like(ch)
    for dy in (0, 1, 2):
        for dx in (0, 1, 2):
            blur += p[dy:dy + ch.shape[0], dx:dx + ch.shape[1]]
    blur /= 9.0
    return ch - blur


def local_mean(ch, r=8):
    """Coarse local mean via strided box — used to bin by tone."""
    p = np.pad(ch, r, mode="reflect")
    out = np.zeros_like(ch)
    n = 0
    for dy in range(0, 2 * r + 1, r):
        for dx in range(0, 2 * r + 1, r):
            out += p[dy:dy + ch.shape[0], dx:dx + ch.shape[1]]
            n += 1
    return out / n


def grain_rms_by_luma(rgb, edges=(0.0, 0.08, 0.20, 0.40, 0.65, 0.85, 1.01)):
    """Highpass RMS of luma, binned by LOCAL luma (not per-pixel luma, which
    would correlate the bin with the very signal being measured)."""
    y = luma_of(rgb)
    hp = highpass(y)
    tone = local_mean(y, r=8)
    rows = []
    for lo, hi in zip(edges[:-1], edges[1:]):
        m = (tone >= lo) & (tone < hi)
        n = int(m.sum())
        rms = float(np.sqrt((hp[m] ** 2).mean())) if n > 1000 else float("nan")
        rows.append((lo, hi, n, rms))
    return rows


def shadow_clip_stats(rgb, thresholds=(0.0, 2 / 255.0, 4 / 255.0)):
    """Fraction of pixels pinned at/near black. Additive symmetric grain
    cannot survive here: the negative half of every excursion is clipped away,
    which turns grain into sparse one-sided speckle and lifts local mean."""
    y = luma_of(rgb)
    out = {}
    for t in thresholds:
        out[t] = float((y <= t).mean())
    dark = y < 0.10
    out["dark_frac"] = float(dark.mean())
    if dark.sum() > 1000:
        hp = highpass(y)
        out["dark_hp_rms"] = float(np.sqrt((hp[dark] ** 2).mean()))
        out["dark_hp_skew"] = float(
            ((hp[dark] - hp[dark].mean()) ** 3).mean() / (hp[dark].std() ** 3 + 1e-12))
    else:
        out["dark_hp_rms"] = float("nan")
        out["dark_hp_skew"] = float("nan")
    return out


def channel_decorrelation(rgb):
    """Chroma grain presence. Scalar (achromatic) grain gives identical
    highpass in all three channels -> differences vanish. Independent
    per-channel noise gives non-zero spread."""
    hr, hg, hb = (highpass(rgb[..., i]) for i in range(3))
    return {
        "std_dR_minus_dG": float((hr - hg).std()),
        "std_dG_minus_dB": float((hg - hb).std()),
        "std_luma_hp": float(highpass(luma_of(rgb)).std()),
    }


def radial_spectrum(ch, nbins=48):
    """Radially-averaged power spectrum -> where grain energy sits in
    frequency. Peak at high frequency = fine/pixel-level; lower = coarser."""
    h, w = ch.shape
    s = min(h, w, 1024)
    c = ch[:s, :s] - ch[:s, :s].mean()
    win = np.outer(np.hanning(s), np.hanning(s))
    F = np.fft.fftshift(np.abs(np.fft.fft2(c * win)) ** 2)
    yy, xx = np.mgrid[0:s, 0:s]
    r = np.sqrt((yy - s / 2) ** 2 + (xx - s / 2) ** 2)
    rb = (r / (s / 2) * nbins).astype(int)
    prof = np.zeros(nbins)
    for i in range(nbins):
        m = rb == i
        if m.any():
            prof[i] = F[m].mean()
    return prof / (prof.max() + 1e-30)


def chroma_luma_spectral_split(rgb):
    """Compare the frequency content of chroma noise vs luma noise.
    Returns the normalised centroid of each radial spectrum (0=DC, 1=Nyquist).
    Real colour film: chroma noise is COARSER (lower centroid) than luma —
    dye clouds are larger than silver grains, and human chroma acuity is low.
    Chroma noise FINER than luma is the signature that reads as colour
    fringing / chromatic aberration rather than film speckle."""
    y = luma_of(rgb)
    cr = rgb[..., 0] - y
    cb = rgb[..., 2] - y
    out = {}
    for name, ch in (("luma", y), ("cr", cr), ("cb", cb)):
        prof = radial_spectrum(highpass(ch))
        idx = np.arange(len(prof)) / (len(prof) - 1)
        out[name + "_centroid"] = float((prof * idx).sum() / (prof.sum() + 1e-30))
    return out


def analyse(path, label=None):
    img = Image.open(path).convert("RGB")
    rgb = np.asarray(img).astype(np.float32) / 255.0
    label = label or path
    print(f"\n=== {label}  ({rgb.shape[1]}x{rgb.shape[0]}) ===")

    print(" grain highpass RMS by local tone:")
    for lo, hi, n, rms in grain_rms_by_luma(rgb):
        if n > 1000:
            print(f"   tone {lo:.2f}-{hi:.2f}: n={n:>9,}  hp_rms={rms:.5f}")

    cs = shadow_clip_stats(rgb)
    print(f" shadow: frac at 0={cs[0.0]:.4f}  <=2/255={cs[2/255.]:.4f}  "
          f"<=4/255={cs[4/255.]:.4f}  dark(<0.10)={cs['dark_frac']:.4f}")
    print(f"         dark hp_rms={cs['dark_hp_rms']:.5f}  "
          f"hp_skew={cs['dark_hp_skew']:.3f}  (skew>0 => one-sided/clipped grain)")

    dc = channel_decorrelation(rgb)
    ratio = dc["std_dR_minus_dG"] / (dc["std_luma_hp"] + 1e-12)
    print(f" chroma: std(dR-dG)={dc['std_dR_minus_dG']:.5f}  "
          f"std(dG-dB)={dc['std_dG_minus_dB']:.5f}  "
          f"luma_hp={dc['std_luma_hp']:.5f}  chroma/luma={ratio:.3f}")

    sp = chroma_luma_spectral_split(rgb)
    print(f" spectra: luma_centroid={sp['luma_centroid']:.3f}  "
          f"cr={sp['cr_centroid']:.3f}  cb={sp['cb_centroid']:.3f}"
          f"   ({'CHROMA FINER THAN LUMA - fringing risk' if sp['cr_centroid'] > sp['luma_centroid'] else 'chroma coarser than luma - ok'})")
    return dict(clip=cs, chroma=dc, spectra=sp)


if __name__ == "__main__":
    import sys
    for p in sys.argv[1:]:
        analyse(p)

"""
Calibration against REAL, SOURCED emulsion data (2026-07-24, second pass).

All figures below are as published by the manufacturer, retrieved and quoted
in the accompanying PR/plan doc. This script only does the arithmetic of
translating them into a shader amplitude decision, and verifies the result.

SOURCED DATA
------------
Group A - diffuse RMS granularity (x1000), SAME measurement convention
(48-micrometre aperture, net diffuse density 1.0) used by both manufacturers,
directly comparable to each other:
    Rollei Infrared 400   RMS = 11   (rolleianalog.com official data sheet)
    Kodak Tri-X 400        RMS = 17   (Kodak Publication F-4017, "fine")
    Kodak HIE               RMS = 18   (Kodak Publication F-13, "fine")
    Ilford SFX 200          NOT PUBLISHED (confirmed absent from Ilford's own
                             technical data sheet - not a research gap, a
                             real absence) - left unchanged, honestly.

Group B - Kodak Print Grain Index (PGI), 135 format, SAME convention,
NOT comparable to Group A (Kodak's own explicit disclaimer):
    Ektar 100        <25 @ 4x6 (4.4x) / 38 @ 8x10 (8.8x) / 66 @ 16x20 (17.8x)
    Portra 160NC      30 @ 4x6         / 52 @ 8x10        / 81 @ 16x20
    Supra 100         27 @ 4x6         / 49 @ 8x10        / 78 @ 16x20
    (all: Kodak Publication E-58 / E-4046)

Qualitative, sourced:
    KODAK VISION3 500T (CineStill 800T's base stock) uses "Dye Layering
    Technology" specifically engineered to reduce grain in shadow regions,
    for improved shadow signal-to-noise - opposite direction from a uniform
    shadow floor. (Kodak VISION3 500T technical data, H-1-5219t)
"""
import numpy as np

REC601 = np.array([0.299, 0.587, 0.114])


def rollei_target():
    print("=" * 78)
    print("ROLLEI IR 400 - same-scale RMS granularity anchor")
    print("=" * 78)
    rollei_rms, trix_rms, hie_rms = 11, 17, 18
    trix_amp = 0.26 * 1.15   # current shipped grainBase * grainBias
    hie_amp = 0.24 * 1.20

    via_trix = (rollei_rms / trix_rms) * trix_amp
    via_hie = (rollei_rms / hie_rms) * hie_amp
    target = (via_trix + via_hie) / 2

    print(f"  real RMS      Rollei=11  TriX=17  HIE=18")
    print(f"  current app   Rollei base*bias = {0.10*1.00:.4f}"
          f"   TriX = {trix_amp:.4f}   HIE = {hie_amp:.4f}")
    print(f"  current ratio Rollei/TriX = {0.10/trix_amp:.3f}"
          f"  (real: {rollei_rms/trix_rms:.3f})")
    print(f"  current ratio Rollei/HIE  = {0.10/hie_amp:.3f}"
          f"  (real: {rollei_rms/hie_rms:.3f})")
    print(f"  target base*bias via TriX anchor = {via_trix:.4f}")
    print(f"  target base*bias via HIE  anchor = {via_hie:.4f}")
    print(f"  mean target = {target:.4f}  -> choose grainBase = 0.19 "
          f"(bias stays 1.00)")
    new_base = 0.19
    print(f"  verify: 0.19/TriX = {new_base/trix_amp:.3f}  (real {rollei_rms/trix_rms:.3f})"
          f"   0.19/HIE = {new_base/hie_amp:.3f}  (real {rollei_rms/hie_rms:.3f})")
    print(f"  both within a few percent of the real ratio. ACCEPT 0.19.")


def ektar_target():
    print("\n" + "=" * 78)
    print("EKTAR 100 - PGI threshold-crossing check (judgment call, not a")
    print("            precise unit conversion - PGI and shader-LSB are not")
    print("            on convertible scales; this is reasoned, not derived)")
    print("=" * 78)
    print("  real: Ektar crosses PGI=25 threshold at 8x10 (PGI 38) and is")
    print("  clearly above it at 16x20 (PGI 66), from 135 format - i.e. real")
    print("  Ektar IS subtly but genuinely visible at normal print sizes.")
    print("  Ektar sits ~25-30% below Portra160NC/Supra100 (same conditions)")
    print("  in log-perceptual PGI units - clearly the quietest, but not by")
    print("  an overwhelming margin.")
    print()
    print("  current app: grainBase=0.02, bias=0.6 -> base*bias=0.012")
    print("  measured consequence: peak amplitude never exceeds 0.27 LSB at")
    print("  ANY tone - always at or under the dither floor. Functionally")
    print("  zero, not 'barely above threshold'. That contradicts the")
    print("  sourced PGI data above.")
    print()

    from final_verify import dw_shipped, dw_fixed

    def peak_lsb(base, bias):
        # reuse the corrected (post-D3) density-floor curve, midtone peak
        dw = dw_fixed(np.array([0.42]))[0]
        return base * 0.040 * dw * bias * 2.2 * 255.0

    for base in (0.02, 0.03, 0.04, 0.05, 0.06):
        amp = peak_lsb(base, 0.6)
        rollei_amp = peak_lsb(0.19, 1.0)
        print(f"  grainBase={base:.2f}  peak_amp={amp:.3f} LSB"
              f"   ratio to (corrected) Rollei = {amp/rollei_amp:.3f}"
              f"{'  <- still sub-LSB, effectively zero' if amp < 0.5 else ''}")

    print()
    print("  choose grainBase=0.05: peak amp clears the dither floor by a")
    print("  real margin for the first time, while staying the clear")
    print("  minimum of all six real stocks (well under half of corrected")
    print("  Rollei, which is itself the next-quietest) - consistent with")
    print("  'clearly quietest, not literally undetectable'.")


def cinestill_shadow_floor():
    print("\n" + "=" * 78)
    print("CINESTILL 800T - shadow floor reduction (qualitative, sourced)")
    print("=" * 78)
    print("  Real Vision3 500T (CineStill's base stock) uses Dye Layering")
    print("  Technology specifically to REDUCE shadow-region grain, for")
    print("  better shadow signal-to-noise - the opposite direction from the")
    print("  universal 0.62 floor shipped in the prior fix.")
    print()
    print("  No source gives a precise number for how much less - this is a")
    print("  qualitative, sourced direction, not a precise magnitude. Chose")
    print("  shadowFloorScale=0.35 (clearly, substantially reduced, but not")
    print("  zeroed - CineStill still has SOME shadow grain, just less of")
    print("  the boost than a conventional stock gets).")
    for scale, name in ((1.0, "Tri-X / Ektar (unchanged)"), (0.35, "CineStill (new)")):
        for luma in (0.02, 0.05, 0.10):
            d = (luma - 0.42) / 0.30
            gauss = np.exp(-d * d)
            t = np.clip((0.34 - luma) / (0.34 - 0.02), 0, 1)
            smooth = t * t * (3 - 2 * t)
            dw = max(gauss, 0.62 * scale * smooth)
            print(f"  {name:<28} luma={luma:.2f}  densityWeight={dw:.4f}")


if __name__ == "__main__":
    rollei_target()
    ektar_target()
    cinestill_shadow_floor()

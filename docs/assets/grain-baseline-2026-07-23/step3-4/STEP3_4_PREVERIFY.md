# Steps 3+4 pre-verification (corrected: raw-delta decorrelation, tolerant regression check)

## Step 3: per-channel color grain + stale-uniform bug check

### HIE_mono (uPreset=1, monoMix=0.0, mono_family=True)
- Raw delta channel decorrelation: std(dR-dG)=0.000000, std(dG-dB)=0.000000
  -> expect exactly 0 (scalar grain): PASS
  -> matches step-2 shipped delta (scalar-only): PASS

### Rollei_mono (uPreset=0, monoMix=0.0, mono_family=True)
- Raw delta channel decorrelation: std(dR-dG)=0.000000, std(dG-dB)=0.000000
  -> expect exactly 0 (scalar grain): PASS
  -> matches step-2 shipped delta (scalar-only): PASS

### Ektar_color (uPreset=6, monoMix=0.0, mono_family=False)
- Raw delta channel decorrelation: std(dR-dG)=0.000075, std(dG-dB)=0.000075
  -> expect > 0 (independent per-channel noise present): FAIL
  -> STALE-UNIFORM BUG CHECK (monoMix leaks as 1.0 from a prior Classic-Film frame, reset not applied): chroma grain would be SILENTLY DISABLED (bug reproduced).

### CineStill (uPreset=7, monoMix=0.0, mono_family=False)
- Raw delta channel decorrelation: std(dR-dG)=0.000877, std(dG-dB)=0.000877
  -> expect > 0 (independent per-channel noise present): PASS
  -> STALE-UNIFORM BUG CHECK (monoMix leaks as 1.0 from a prior Classic-Film frame, reset not applied): chroma grain would be SILENTLY DISABLED (bug reproduced).

### TriX_400 (uPreset=8, monoMix=1.0, mono_family=False)
- Raw delta channel decorrelation: std(dR-dG)=0.000000, std(dG-dB)=0.000000
  -> expect exactly 0 (scalar grain): PASS
  -> matches step-2 shipped delta (scalar-only): PASS

### Aerochrome_Dense (uPreset=9, monoMix=0.0, mono_family=False)
- Raw delta channel decorrelation: std(dR-dG)=0.000689, std(dG-dB)=0.000689
  -> expect > 0 (independent per-channel noise present): PASS
  -> STALE-UNIFORM BUG CHECK (monoMix leaks as 1.0 from a prior Classic-Film frame, reset not applied): chroma grain would be SILENTLY DISABLED (bug reproduced).

## Step 4: clump-irregularity multiplier

- HIE_mono: clump mask mean=0.9998 (target ~1.0), min=0.500, max=1.500, std=0.2139
- Rollei_mono: clump mask mean=0.9995 (target ~1.0), min=0.501, max=1.500, std=0.2144
- Ektar_color: clump mask mean=0.9991 (target ~1.0), min=0.500, max=1.500, std=0.2146
- CineStill: clump mask mean=0.9997 (target ~1.0), min=0.500, max=1.500, std=0.2140
- TriX_400: clump mask mean=1.0005 (target ~1.0), min=0.501, max=1.500, std=0.2136
- Aerochrome_Dense: clump mask mean=0.9995 (target ~1.0), min=0.500, max=1.500, std=0.2139

- HIE overall RMS delta: without clump=0.00309, with clump=0.00316 (should stay close - mask is mean-preserving)

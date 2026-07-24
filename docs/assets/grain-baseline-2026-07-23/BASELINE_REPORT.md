# Grain baseline verification — current shipped math
Reference photo: reference_photo.jpg (1080x1920)
User Grain slider: 0.0 (isolating grainBase, per prior-cycle method)
Seed: 42.0

## HIE_mono  (uPreset=1, grainClump=1.25, grainBias=1.2, grainBase=0.24)
- Overall RMS delta vs. source: 0.00309
- Mean grain amplitude by source luma bucket:
  - luma 0.00-0.15: n=   97,512  mean_amp=0.00282
  - luma 0.15-0.35: n=  479,288  mean_amp=0.00937
  - luma 0.35-0.65: n=1,234,105  mean_amp=0.01027
  - luma 0.65-0.85: n=  238,695  mean_amp=0.00397
  - luma 0.85-1.00: n=   24,000  mean_amp=0.00120
- Density behavior: density-weighted (mono path, uPreset<=5)

## Rollei_mono  (uPreset=0, grainClump=1.0, grainBias=1.0, grainBase=0.1)
- Overall RMS delta vs. source: 0.00108
- Mean grain amplitude by source luma bucket:
  - luma 0.00-0.15: n=   97,512  mean_amp=0.00098
  - luma 0.15-0.35: n=  479,288  mean_amp=0.00325
  - luma 0.35-0.65: n=1,234,105  mean_amp=0.00357
  - luma 0.65-0.85: n=  238,695  mean_amp=0.00138
  - luma 0.85-1.00: n=   24,000  mean_amp=0.00042
- Density behavior: density-weighted (mono path, uPreset<=5)

## Ektar_color  (uPreset=6, grainClump=0.45, grainBias=0.6, grainBase=0.02)
- Overall RMS delta vs. source: 0.00018
- Mean grain amplitude by source luma bucket:
  - luma 0.00-0.15: n=   97,512  mean_amp=0.00054
  - luma 0.15-0.35: n=  479,288  mean_amp=0.00054
  - luma 0.35-0.65: n=1,234,105  mean_amp=0.00054
  - luma 0.65-0.85: n=  238,695  mean_amp=0.00054
  - luma 0.85-1.00: n=   24,000  mean_amp=0.00054
- Density behavior: FLAT — no density weighting (color/classic path, uPreset>5)

## CineStill  (uPreset=7, grainClump=1.05, grainBias=1.0, grainBase=0.14)
- Overall RMS delta vs. source: 0.00209
- Mean grain amplitude by source luma bucket:
  - luma 0.00-0.15: n=   97,512  mean_amp=0.00630
  - luma 0.15-0.35: n=  479,288  mean_amp=0.00630
  - luma 0.35-0.65: n=1,234,105  mean_amp=0.00630
  - luma 0.65-0.85: n=  238,695  mean_amp=0.00630
  - luma 0.85-1.00: n=   24,000  mean_amp=0.00630
- Density behavior: FLAT — no density weighting (color/classic path, uPreset>5)

## TriX_400  (uPreset=8, grainClump=1.35, grainBias=1.15, grainBase=0.26)
- Overall RMS delta vs. source: 0.00443
- Mean grain amplitude by source luma bucket:
  - luma 0.00-0.15: n=   97,512  mean_amp=0.01346
  - luma 0.15-0.35: n=  479,288  mean_amp=0.01346
  - luma 0.35-0.65: n=1,234,105  mean_amp=0.01345
  - luma 0.65-0.85: n=  238,695  mean_amp=0.01346
  - luma 0.85-1.00: n=   24,000  mean_amp=0.01346
- Density behavior: FLAT — no density weighting (color/classic path, uPreset>5)


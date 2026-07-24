# Step 2 pre-verification: universal density weighting (proposed vs. shipped)

## HIE_mono (uPreset=1, MONO)
- Regression check (must be identical): PASS - byte-identical

## Rollei_mono (uPreset=0, MONO)
- Regression check (must be identical): PASS - byte-identical

## Ektar_color (uPreset=6, COLOR/CLASSIC)
- Before (flat) -> After (density-weighted), mean amplitude by luma bucket:
  - luma 0.00-0.15: 0.00054 -> 0.00012
  - luma 0.15-0.35: 0.00054 -> 0.00039
  - luma 0.35-0.65: 0.00054 -> 0.00043
  - luma 0.65-0.85: 0.00054 -> 0.00017
  - luma 0.85-1.00: 0.00054 -> 0.00005

## CineStill (uPreset=7, COLOR/CLASSIC)
- Before (flat) -> After (density-weighted), mean amplitude by luma bucket:
  - luma 0.00-0.15: 0.00630 -> 0.00137
  - luma 0.15-0.35: 0.00630 -> 0.00456
  - luma 0.35-0.65: 0.00630 -> 0.00499
  - luma 0.65-0.85: 0.00630 -> 0.00193
  - luma 0.85-1.00: 0.00630 -> 0.00058

## TriX_400 (uPreset=8, COLOR/CLASSIC)
- Before (flat) -> After (density-weighted), mean amplitude by luma bucket:
  - luma 0.00-0.15: 0.01346 -> 0.00292
  - luma 0.15-0.35: 0.01346 -> 0.00973
  - luma 0.35-0.65: 0.01345 -> 0.01066
  - luma 0.65-0.85: 0.01346 -> 0.00412
  - luma 0.85-1.00: 0.01346 -> 0.00125


# Plan: expose discoverable physical camera lenses

**Date:** 2026-07-22  
**Branch:** `agent/hdr-imaging-pipeline`  
**Draft PR:** #25

## Problem

The app previously bound only `LENS_FACING_BACK` or `LENS_FACING_FRONT`. The rear choice meant CameraX's default logical camera, not a user-selectable ultra-wide, main, or telephoto lens. Consequently the focus validation text referred to lenses that the UI could not select.

## Implementation

- Enumerate CameraX rear and front `CameraInfo` objects.
- Read logical-camera physical members through `physicalCameraInfos`.
- Build exact selectors using the logical camera ID plus `setPhysicalCameraId` for a chosen physical lens.
- Include an `Auto rear` logical option when a logical camera exposes multiple physical members.
- Derive approximate 35 mm-equivalent focal length from reported focal length and sensor dimensions, then label ultra-wide/main/tele relative to the main lens.
- Persist the selected lens ID and label.
- Rebind CameraX when lens identity changes.
- Recalculate focus, RAW, HDR, flash, exposure, and zoom capabilities for the active selection.
- Cancel a pending Double Exposure when the lens changes, because the source geometry is no longer guaranteed compatible.
- Record the selected lens in MediaStore metadata.

## Fallback behavior

CameraX can select only cameras exposed by Android. Some manufacturers hide auxiliary lenses or expose them only through proprietary camera apps. In that case the selector truthfully shows only the default rear camera and selfie camera. An unavailable persisted ID falls back to the default logical rear or front camera and updates the saved selection.

## Device validation

1. Open the Lens panel and record every option.
2. Capture the same detailed scene with Auto rear and every explicit rear lens.
3. Confirm field of view changes in the expected order.
4. Confirm the header and saved metadata name the active lens.
5. Re-run focus-mode capability checks on each explicit lens.
6. Re-run Standard, Computational HDR, True RAW HDR, HQ 1080, Fast 1080, and Full Resolution where each lens enables them.
7. Confirm unsupported RAW/HDR modes disable or fall back without breaking preview.
8. Confirm changing lens clears a pending Double Exposure frame.
9. Restart the app and verify the selected lens returns or falls back cleanly.
10. Confirm the front camera remains available as Selfie.

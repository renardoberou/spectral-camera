# Plan: photographer focus modes

**Date:** 2026-07-22  
**Branch:** `agent/hdr-imaging-pipeline`  
**Draft PR:** #25  
**Status:** implemented and build-verified; physical-lens validation required

## Objective

Add explicit focus behavior without disturbing the validated Standard, Computational HDR, True RAW HDR, Ultra HDR, output-mode, or Double Exposure pipelines.

## Product modes

- **Continuous AF:** continuously follows focus. A tap temporarily prioritizes the selected subject and auto-releases.
- **Tap & Lock:** tap to autofocus and hold until Unlock or a mode change.
- **Macro AF:** use the camera-reported macro autofocus mode, tap, and hold.
- **Manual Focus:** normalized control from infinity to the nearest reported position. A squared response gives more useful precision at distant focus.
- **Infinity:** manual zero-diopter focus for landscapes, skies, and architecture.
- **Fixed Focus:** truthful state for lenses with no adjustable focus.

Unsupported modes are disabled per active physical lens. The app does not imitate macro or manual focus digitally.

## Camera architecture

Exposure and focus are emitted through one combined Camera2 request-options builder. This prevents a manual-exposure update from erasing focus settings and prevents a focus update from turning auto exposure back on.

Capability detection uses the active lens's available autofocus modes, minimum focus distance, and focus-distance calibration level.

Manual focus uses Camera2 lens distance values: zero is infinity and the lens-reported maximum is its nearest supported position. Uncalibrated lenses use a normalized label rather than claiming a physical distance.

## HDR behavior

Before a JPEG or RAW exposure bracket, the current lens distance is frozen when manual lens control and capture metadata are available. Manual and infinity modes are already deterministic. After the bracket, the selected focus and exposure behavior are restored together.

This matters because focus hunting changes magnification and edge position between HDR frames, which can be mistaken for scene movement during alignment.

## Live interface

The live header displays the selected focus mode. The Focus panel exposes only modes supported by the selected lens, instructions for tap behavior, an Unlock control for held autofocus, and the manual-focus slider.

Tap feedback reports focus acquired, focus locked, failed focus, exposure-only metering, ignored input under fully manual controls, or an unsupported operation.

## Automated verification

Green Android CI run 196 verifies unit tests, debug APK creation, unsigned release APK creation, and signing safeguards for the focus-mode head.

Pure JVM coverage includes manual-position-to-diopter mapping, increased far-distance precision, calibrated distance labels, and honest normalized labels for uncalibrated lenses.

## Physical validation matrix

Repeat on every rear lens and the front lens:

1. Continuous AF from near to far and far to near.
2. Continuous AF tap priority, then automatic return.
3. Tap & Lock over several Standard captures.
4. Tap & Lock across JPEG HDR and True RAW HDR brackets.
5. Macro AF on a close textured subject.
6. Manual Focus from infinity through midpoint to nearest position.
7. Infinity on distant architecture and sky.
8. Fixed-focus fallback on any non-adjustable camera.
9. Manual exposure plus every focus mode.
10. Lens switching while an unsupported focus mode is selected.
11. Double Exposure with different intentional focus distances for frames 1 and 2.

## Release criteria

- No unsupported focus mode is presented as functional.
- Tap & Lock remains held until Unlock, another tap, or a mode change.
- Continuous AF resumes after its temporary tap priority.
- Manual focus does not drift or get overridden by autofocus.
- HDR brackets do not pump focus between exposures.
- Changing ISO or shutter does not clear focus mode, and changing focus does not re-enable auto exposure.
- Focus labels remain honest when distance calibration is unavailable.

## Known limitations

- Lens support is vendor- and camera-specific.
- Reported physical distance can remain approximate even on calibrated hardware.
- Infinity is the camera's zero-diopter actuator position, not a guarantee of perfect optical calibration at every temperature.
- Focus peaking is not part of this cycle; it is a separate image-analysis feature rather than a focus mode.

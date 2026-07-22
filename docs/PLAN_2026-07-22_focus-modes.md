# Plan: first-class focus modes

**Date:** 2026-07-22  
**Branch:** `agent/hdr-imaging-pipeline`  
**Draft PR:** #25  
**Status:** implemented and Android-CI verified; physical-lens validation required

## 1. Objective

Add photographer-facing focus behavior without destabilizing the existing Standard, Computational HDR, True RAW HDR, Ultra HDR, Double Exposure, manual-exposure, or output-mode workflows.

The focus system must be honest about the active lens. It exposes only behavior reported by Camera2, falls back safely when the user changes lenses, and keeps one focus distance throughout an HDR bracket wherever the hardware permits.

## 2. Implemented modes

### Continuous AF

- Uses continuous-picture autofocus, or continuous-video where that is the only continuous mode reported.
- A viewfinder tap temporarily prioritizes the selected subject.
- The tap action automatically releases after three seconds so continuous tracking resumes.

### Tap & Lock

- A viewfinder tap runs one-shot autofocus.
- Focus remains locked because automatic cancellation is disabled.
- Exposure remains controlled independently by the user's Auto or Manual exposure setting.
- A visible Unlock focus control releases the lock.

### Macro AF

- Exposed only when the active lens reports macro autofocus and a movable focus range.
- A tap runs close-range autofocus and holds the result.
- Unlock behavior matches Tap & Lock.

### Manual Focus

- Exposed only when the lens reports a movable focus range and supports autofocus-off requests.
- The control runs from infinity to the nearest supported lens position.
- Camera2 focus distance is expressed in diopters: zero is infinity and the reported maximum is the nearest focus position.
- The normalized slider uses a squared mapping so the distant part of the range receives finer control.
- A physical-distance estimate is shown only when the camera reports approximate or calibrated focus-distance metadata. Uncalibrated lenses show a neutral percentage toward near instead of claiming false precision.

### Infinity

- Exposed only where manual lens positioning is supported.
- Sets autofocus off and requests a zero-diopter focus distance.
- Intended for distant landscapes, sky, and architecture.

### Fixed Focus

- Used when the active camera reports no movable focus mechanism.
- Other focus modes are disabled.
- Viewfinder taps may still meter automatic exposure, but the UI states that focus cannot change.

## 3. Capability detection and lens changes

For each bound physical lens, the controller reads:

- available autofocus modes;
- minimum focus distance;
- focus-distance calibration quality;
- continuous, auto, macro, and autofocus-off availability.

The capability model is surfaced to Compose. Unsupported controls are disabled. When the user switches to a lens that cannot perform the stored mode, the app selects the best supported fallback in this order:

1. Continuous AF;
2. Tap & Lock;
3. Macro AF;
4. Manual Focus;
5. Infinity;
6. Fixed Focus.

This prevents a front camera or secondary rear lens from inheriting a control it cannot execute.

## 4. Interaction with exposure

Camera2 interop request options replace the previous option bundle. Focus and exposure are therefore assembled into one request rather than being applied independently and accidentally overriding one another.

- Auto exposure remains active when only focus is manual.
- Manual exposure and Manual Focus can operate simultaneously.
- Tap & Lock and Macro hold focus only; they do not intentionally hold auto exposure.
- In Manual Focus, Infinity, and Fixed Focus, a viewfinder tap meters exposure when exposure is Auto.
- With both exposure and focus manual, a viewfinder tap is ignored and the UI explains why.

## 5. HDR and RAW HDR focus stability

An HDR bracket must not refocus between under, reference, and over frames.

Before JPEG or RAW HDR capture, the controller obtains the current lens focus distance where available. It then applies autofocus-off plus that distance while changing exposure values. Manual Focus and Infinity use their explicit requested positions. After the bracket, the complete user exposure/focus request bundle is restored.

This applies to:

- automatic JPEG exposure-compensation brackets;
- manual JPEG shutter brackets;
- fixed-ISO True RAW HDR shutter brackets.

On a device that reports autofocus but does not permit direct lens-distance control, the app cannot guarantee a hard manual hold. That limitation must be checked per lens.

## 6. User interface

The Live screen contains a separate collapsible Focus panel beside Exposure.

It shows:

- the selected focus mode in the persistent header;
- only supported mode chips;
- tap-result messages such as Focused, Focus locked, Focus failed, Exposure metered, or Unsupported;
- a manual focus slider and lens-position label;
- an Unlock focus control for Tap & Lock and Macro;
- a fixed-focus explanation on non-moving lenses.

Focus mode and manual position are persisted. MediaStore descriptions also record the selected focus mode for newly saved captures.

## 7. Automated verification

Android CI run 197 passed at head `e95bc8354e863710328e6f25bb102dfbf9e8e686`:

- JVM unit tests;
- debug APK build;
- unsigned release APK build;
- release-signing guard.

`FocusMathTest` covers:

- infinity mapping to zero diopters;
- nearest position mapping to the lens maximum;
- nonlinear slider response;
- calibrated/approximate distance labels;
- refusal to claim physical distance for uncalibrated lenses.

Camera focus motion cannot be validated in JVM CI.

## 8. Physical-device release gates

Test each available rear lens and the front camera.

### Continuous AF

1. Start on a distant detailed subject.
2. Move to a close detailed subject.
3. Confirm the preview follows both directions.
4. Tap a subject and confirm focus prioritizes it, then resumes continuous behavior.

### Tap & Lock

1. Tap a high-contrast subject.
2. Move the camera so nearer and farther objects enter the frame.
3. Confirm focus remains at the locked distance.
4. Take a Standard capture and confirm the saved image matches the lock.
5. Unlock and confirm normal autofocus resumes.

### Macro AF

1. Use a close textured object within the lens's practical range.
2. Confirm Macro is disabled on lenses that do not report it.
3. Confirm a successful macro lock survives a still capture.

### Manual Focus

1. Move the slider to infinity and inspect a distant subject.
2. Move progressively toward near and confirm the focus plane moves monotonically closer.
3. Test Auto Exposure + Manual Focus.
4. Test Manual Exposure + Manual Focus.
5. Confirm uncalibrated lenses show no misleading metre/centimetre value.

### Infinity

1. Photograph distant architecture or landscape detail.
2. Confirm the lens does not hunt after selection.
3. Confirm viewfinder taps affect only automatic exposure where applicable.

### HDR focus consistency

1. Lock focus on a static subject.
2. Capture Computational HDR and inspect high-contrast edges for focus breathing between bracket frames.
3. Repeat with True RAW HDR.
4. Repeat with Manual Focus and Infinity.
5. Confirm the user's focus mode is restored after success, cancellation, and capture error.

### Lens fallback

1. Select a specialist mode on the main rear camera.
2. Switch to every other lens and the front camera.
3. Confirm unsupported modes are disabled and a supported fallback is visibly selected.
4. Confirm returning to the original lens leaves the camera functional.

### Double Exposure

1. Capture frame 1 with one deliberate focus position.
2. Change focus and capture frame 2.
3. Confirm each source retains its intended plane of focus and the final composite renders normally.

## 9. Honest limitations

- Focus capability reporting varies by camera vendor and lens.
- CameraX one-shot focus behavior must be verified against Camera2 interop on the physical target device.
- Macro availability does not guarantee a genuinely short working distance.
- Reported physical distance may be approximate or uncalibrated.
- Focus peaking, magnified manual-focus assistance, subject recognition, face/eye AF, and per-lens remembered preferences are not part of this cycle.
- A hard HDR focus hold is possible only when the camera exposes direct lens-distance control.

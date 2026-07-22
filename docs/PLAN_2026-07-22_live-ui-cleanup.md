# Plan: compact Live-screen information and controls

**Date:** 2026-07-22  
**Branch:** `agent/hdr-imaging-pipeline`  
**Draft PR:** #25

## Change

The experimental physical-lens selector was reverted. Spectral Camera returns to the established rear/selfie camera switch while retaining all focus modes.

The duplicate top shortcuts for Gallery and Hardware were removed because those destinations remain permanently available in the bottom navigation. Presets moved into the same control row as Exposure and Focus.

The camera-information surface now fills the available width and responds to screen shape. Wide screens use a compact two-column arrangement: app/sensor identity on the left and preset/focus/capture state on the right. Narrow screens use three short lines. Exceptional Double Exposure and manual-exposure state appears only when active.

## Validation

1. Test portrait and landscape orientations.
2. Confirm no top Gallery, Hardware, Lens, or Presets shortcut remains.
3. Confirm Presets sits beside Exposure and Focus and still opens the preset sheet.
4. Confirm Gallery and Hardware remain accessible through bottom navigation.
5. Confirm the rear/selfie switch still works.
6. Confirm the header does not clip long preset, focus, or output labels.
7. Confirm Double Exposure frame-1 and manual-exposure warnings remain visible.

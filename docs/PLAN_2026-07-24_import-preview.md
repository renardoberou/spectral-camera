# Plan: Import Preview — apply chosen settings to imported photos

**Date:** 2026-07-24 · **Status:** executed this cycle
**Trigger:** "when I import a photo... it doesn't take into account the
settings the user sets."

## Diagnosis
Code audit of `importAndSave`/`processBitmap`/`drawQuad` confirms the plumbing
already threads a `CameraSettings` snapshot through correctly - imports are
not silently defaulting. The real gap is UX, not a wiring bug: Import (and
gallery Reprocess) fire-and-forget on whatever the LIVE camera happens to be
set to at that instant, with no chance to choose or preview a look for THAT
specific photo before it is processed and saved. That is what reads as
"settings aren't applied" - there is no place to apply them per-import.

## Fix: an Import Preview screen
New route, reached from both entry points (Import photo, gallery Reprocess):
1. Decode the picked/selected image once.
2. Seed local preview settings from the current live settings (same
   defaults as before - nothing regresses for someone who just taps through).
3. Show the SAME preset drawer and Look-intensity control used live, plus
   the decoded photo rendered through the real GPU pipeline at a capped
   preview resolution (long edge 1024) for responsiveness.
4. Every control change re-renders the preview (latest-wins: a monotonic
   token drops stale in-flight renders so quick taps never show an old frame
   arriving late).
5. Save runs the full-resolution pipeline with the settings chosen ON THIS
   SCREEN, not the live camera's settings - the literal fix.
6. Cancel discards; no capture, no gallery write.

## Architecture
- `SpectralViewModel`: `importAndSave`'s decode+prepare+render+save body
  extracted into `renderAndSave()` (shared, unchanged behaviour) and
  `decodeUri()`. New `ImportPreviewState` + `StateFlow<ImportPreviewState?>`;
  `beginImportPreview`, `updatePreviewSettings` (debounced via a request
  token), `confirmImportPreview`, `cancelImportPreview`.
- `ImportPreviewScreen.kt` (new): preview image + preset drawer + intensity
  chips + Save/Cancel, reusing `PresetSheet`/`SteppedControl` as-is.
- `SpectralCameraApp.kt`: new `Route.ImportPreview`; both `onImport` (Live)
  and `onReprocess` (Gallery) now navigate here instead of saving directly.
- Zero changes to any shader or `FilmLook.kt` value - purely additive UI/VM.

## Verification
Manual: import a photo, change preset + intensity, confirm the ON-SCREEN
preview changes before saving, confirm the SAVED file matches the preview
(not the live camera's own current preset); Cancel leaves no gallery item.

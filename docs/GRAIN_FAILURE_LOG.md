# Grain feature failure log

Status: the grain control and rendering feature are intentionally removed from the next build after repeated failures on the Motorola Edge 60 Fusion.

## Product decision

- Remove the Grain option buttons from **Look adjustments**.
- Disable photographic grain in both live preview and still/capture rendering.
- Keep the legacy persisted `grain` field and shader uniforms temporarily for settings/schema compatibility, but force the render strength to zero.
- Remove **Presets** from the More / Tools drawer because Presets already exists on the main camera control row.

## Failure record

### 1. Baseline grain was not truthful

The original implementation left preset `grainBase` behavior active while the user-facing Off state was expected to be clean. The selector was technically wired but the visible effect was weak and ambiguous.

### 2. Explicit Grain Policy did not solve live preview

An explicit Off/Fine/Medium/Coarse/Extreme policy was added. Full-resolution captures showed stronger Extreme texture, but the live preview still became visibly cleaner after several seconds.

### 3. Settings snapshot overwrite hypothesis

Delayed complete `ManualAdjustments` snapshots could overwrite a newer Grain selection when unrelated focus, exposure, white-balance, capability, or lifecycle updates arrived. A serialized/latest-wins settings coordinator and regression tests were added. The user reported that the failure persisted.

### 4. GL-thread handoff hypothesis

The renderer consumed state across the main/UI and OpenGL threads. GL-thread coalescing, a single handoff, field-level intents, and trace instrumentation were added. The user reported that the failure persisted.

### 5. Preview-versus-capture boundary

Live preview uses a CameraX OES `SurfaceTexture` stream at approximately 1920x1080 and a screen-sized render target. Still capture uses a separate high-resolution ImageCapture path and a 2D texture. A successful still A/B therefore did not prove live-preview behavior.

### 6. Animated preview seed

The preview shader changed its procedural grain seed every draw using the frame index. The first fixed-seed diagnostic build held the seed at `137.0`. On-device, Extreme remained visibly textured for a 20-second run while the seed and amplitude proxy stayed constant. This falsified the previous delayed-clean-collapse behavior for that diagnostic variant.

A production session-stable seed was then implemented and tested on-device. The user subsequently reported that grain still was not changing as expected. This means the temporal seed change was a contributing visual instability, but not a satisfactory product solution for the user’s desired control.

### 7. Camera/ISP and visibility uncertainty

The Motorola camera stream showed AE/AWB changes and vendor camera warnings during testing. Preview noise reduction, external-texture filtering, downsampling, luminance weighting, and camera-stream convergence could all alter perceived texture. The available trace proved policy state and a proxy amplitude, not a per-pixel final visual result.

## Verification evidence retained

- Multiple independent source/code investigations.
- Android/Khronos research covering SurfaceTexture, external OES sampling, CameraX SurfaceRequest, Camera2 noise reduction, GLSL precision, and preview scaling.
- Unit tests and CI builds for the attempted grain implementations.
- Full-resolution Off/Extreme capture evidence showing that the algorithm could produce texture in saved output.
- Fixed-scene 20-second device runs with UI Extreme selected and GL trace correlation.
- User observation remains authoritative: the feature did not meet the required visible behavior.

## Decision for the next version

Do not spend additional release cycles tuning or claiming the Grain control is reliable. Remove the user-facing control and disable the rendering stage. This log preserves the failed hypotheses and evidence so grain can be revisited later as a separate, test-first feature with a stronger acceptance method (matched locked-camera preview/capture evidence and deterministic visual thresholds).

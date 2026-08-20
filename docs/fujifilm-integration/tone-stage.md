# Shared tone stage contract

The shared tone stage is a scene-referred, monotonic mapping with explicit toe, midtone, and shoulder controls. Pure Kotlin reference math lives in `core/color/ToneMath.kt`; the GLSL implementation must remain generic and receive profile values through uniforms.

## Ordering

- Visible-spectrum profiles: working-space preparation -> tone -> density/protection -> texture -> display.
- Aerochrome: working-space preparation -> synthetic NIR/EIR transform -> shared tone/refinement -> protection/density -> texture -> display.
- Monochrome IR: working-space preparation -> IR luminance -> H&D curve -> shared refinement/protection -> texture -> display.

A visible-spectrum hue warp must not run before Aerochrome classification or after monochrome conversion.

## Verification

The Kotlin reference tests cover finite values, monotonicity, bounded output, and highlight slope behavior. GLSL contract tests must assert that the shader contains the shared-stage functions and that the family dispatch keeps the spectral front ends ahead of any shared visible-spectrum refinement.

Exact device equivalence remains `NOT RUN` until a physical capture comparison is completed.

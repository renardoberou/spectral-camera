# Plan: scene-to-film tonal, red, highlight, and sky refinement

**Date:** 2026-07-22  
**Branch:** `agent/aerochrome-tonal-refinement`  
**Trigger:** three real Aerochrome field frames covering saturated red foregrounds, direct-sun backlight, urban greenery, sky, architecture, and deep shadow.

## Review of the requested plan

The direction is valid, but the current engine already contains a reversal-film S-curve, headroom-limited saturation, tone-preserving foliage colourization, wide sky smoothing, and final dither. Adding another generic S-curve or a global red desaturation would double-process the image and regress the existing material model.

The implementation is therefore surgical:

1. A luminance-only scene curve is applied before synthetic NIR/material rendering. It anchors 0.5 exactly, slightly lifts shadows, compresses highlights, and preserves chromaticity.
2. The existing Aerochrome S-curve receives toe protection rather than being replaced.
3. Red-dominant output receives source-detail preservation and highlight-only saturation tapering. Neutrals, skin, water, and sky are excluded.
4. A final hue-preserving output shoulder replaces destructive highlight clipping after manual controls and bloom.
5. Sky classification receives an explicit foliage veto; clear-blue detail and grain are reduced while cloud structure remains.

## Why no RNI purchase is required

The target is not undocumented imitation of a proprietary preset. Validation is based on measurable photographic behavior: highlight continuity, shadow separation, red texture, material assignment, sky gradient stability, and consistency across Standard, JPEG HDR, and True RAW HDR.

## Device validation

Re-shoot or reprocess the same three diagnostic compositions and compare against the previous build:

- red flowers/foliage against a bright building and sky;
- direct sun and flare with deep foreground shadow;
- mixed architecture, clouds, red foliage, white fabric, and blue reflections.

Pass criteria:

- red petals/leaves keep visible internal density and do not form solid max-red areas;
- red highlights become slightly paler rather than clipping;
- deep shadows gain separation without a grey HDR wash;
- middle-grey architecture and fabric remain stable;
- clouds retain structure and clear blue sky becomes smoother;
- foliage is never partially recoloured as sky;
- extreme brightness rolls into the shoulder rather than forming a hard channel clip;
- Classic, Soft, Dense, Gold, and Faded retain their existing hierarchy;
- monochrome IR remains materially unchanged apart from the deliberately tiny shared input shaping.

## Honest limitation

No tone curve can reconstruct information that the camera clipped before Spectral Camera received it. Standard JPEG capture with the sun inside the frame can still contain irreversible sensor/ISP clipping and optical flare. Computational HDR or True RAW HDR remains the appropriate source mode for those scenes.

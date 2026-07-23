# Launcher icon recovery — 1.19.6

## Diagnosis

The icon problem was not Android launcher caching.

The debug APK produced by CI run 245 contained `ic_launcher_1195_artwork.webp`, but the packaged WebP could not be decoded by a standard WebP decoder. The manifest referenced an adaptive icon whose foreground ultimately depended on that corrupt asset. Android therefore displayed its generic application placeholder.

The temporary launcher setup also had no legacy raster fallback under the `ic_launcher_1195` resource name.

## Final strategy

1. Remove the temporary `1195` adaptive-icon resources and corrupt WebP.
2. Stop using adaptive foreground composition for this artwork.
3. Use the supplied dark-background image directly, without cropping or zooming.
4. Generate valid PNG launchers for mdpi, hdpi, xhdpi, xxhdpi, and xxxhdpi.
5. Provide a separate round-safe PNG set with the same artwork at 88% scale.
6. Point the manifest at a fresh `ic_launcher_1196` resource identity.
7. Validate both source resources and the final packaged APK in CI.

## Why raster PNG

The artwork is already a complete square icon with its own dark background. Treating it as an adaptive foreground adds unnecessary scaling, masking, density, and decoder failure points. Density-specific PNG mipmaps are the simplest deterministic representation and preserve the image exactly.

## Acceptance criteria

- Android no longer displays the generic blue robot placeholder.
- The normal launcher shows the full supplied composition without zooming.
- The upper-left viewfinder remains visible.
- Circular launchers use the padded round variant.
- All ten PNG resources pass signature, IHDR dimension, and CRC checks.
- The built debug APK contains all ten expected resources.
- No `ic_launcher_1195` resource remains in the APK.

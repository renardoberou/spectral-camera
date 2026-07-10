# Spectral Camera — Agent Handoff Report

**Date:** 2026-07-10  
**Repository:** `renardoberou/spectral-camera`  
**Repair branch:** `fix/signing-gallery-permissions`  
**Draft PR:** `#4 — Repair signing isolation and Android gallery permissions`  
**Target repair version:** `1.8.7` (`versionCode 29`)

---

## Executive summary

This document records the debugging and repair work performed after the repository's signing and gallery regressions around development version `1.8.6`.

The immediate failure was a Gradle signing configuration that referenced `app/signing/debug-only.keystore` before that file was committed. The next commit added the keystore, fixing the missing-file error but introducing a more serious architectural problem: the repository publicly contained the private key material, alias, and passwords used to sign both debug and ordinary release builds.

The repair branch removes the public keystore from the current tree, removes all hard-coded signing fallbacks, restores unsigned ordinary CI release builds, introduces stable-certificate verification for tagged releases, redesigns Android media permissions, repairs MediaStore compatibility from Android 8 through Android 16, adds focused JVM tests, and updates the release documentation.

Android CI passed on repair-branch head:

`4ec7741c28354d47c99e92786fb5e2a449ae3939`

CI verified:

- no tracked `.jks` or `.keystore` files;
- permission-policy unit tests pass;
- debug APK builds successfully;
- unsigned release APK builds successfully;
- ordinary CI does not produce a signed release APK;
- expected artifacts upload successfully.

The branch remains a draft and must not be merged or released until the stable signing lineage and physical-device migration tests are complete.

---

## Repository state before the repair

The two commits that triggered this investigation were:

### `db27145dcfd5d9c075743779b6ba71649ad8bc01`

Commit message:

`fix(ux): no selfie mirror anywhere; stable signing ends reinstalls; gallery permission`

This commit changed three independent areas:

1. Removed explicit horizontal flips for the front-camera preview.
2. Added runtime media permission handling to the Gallery screen.
3. Replaced the previous release-signing arrangement with a `stable` signing configuration that defaulted to `app/signing/debug-only.keystore` with hard-coded public credentials.

It also changed the default app version to `1.8.6`, version code `28`.

### `5178cc8875bf3391dccb652930a8c9e9a53800b0`

Commit message:

`fix(signing): include the stable throwaway keystore (gitignore excluded it)`

This commit force-added the previously missing binary keystore because `.gitignore` correctly excluded `*.keystore` files.

The immediate build failure was therefore fixed, but the repository then contained reusable signing private-key material.

---

## Problems identified

### 1. Missing keystore caused CI failure

The Gradle configuration referenced `app/signing/debug-only.keystore`, but `.gitignore` excluded `*.keystore`. CI failed at `:app:validateSigningDebug` with a missing-keystore error. GitHub issue `#3` records this original main-branch failure.

### 2. Public signing private key

The follow-up commit publicly exposed the keystore, alias, keystore password, and key password. Anyone with these values can produce APKs Android accepts as updates for installations using the same package name and certificate. This key is permanently compromised and unsuitable for trusted stable distribution.

### 3. Debug and release signing were coupled

The same public signing configuration was assigned to debug and release. This confused ephemeral development builds, repeatable internal tests, and trusted stable releases.

### 4. False promise of update compatibility

Android update compatibility depends on package name and signing certificate. Official `v1.8.2` can update in place only to an APK signed with the same private stable key. Builds signed with the retired public key or unrelated CI debug keys require a one-time uninstall before installing the repaired stable build.

### 5. Startup incorrectly required photo-library permission

The app shell still required media permission at startup, even after Gallery-specific permission handling was added. Camera access and historical-gallery access were incorrectly treated as one permission domain.

### 6. Android 14+ selected-photo access was not handled

The app did not explicitly model `READ_MEDIA_VISUAL_USER_SELECTED`, partial selected-photo access, app-owned-only access, or permission changes while backgrounded.

### 7. Gallery permission was requested automatically

The Gallery launched a permission request immediately when composed, rather than after a user explanation and explicit action.

### 8. Gallery state lacked error and loading semantics

The previous Gallery exposed only a list, without loading, permission-limited, error, empty, or unavailable-thumbnail states.

### 9. MediaStore query was too broad

The previous wildcard path selection could match unrelated similarly named folders and did not explicitly model migration between historical storage locations.

### 10. Historical captures existed in two locations

Different versions saved under `Pictures/SpectralCamera` and `DCIM/SpectralCamera`. A DCIM-only query would hide older captures.

### 11. Android 8–9 compatibility was incomplete

The project advertises `minSdk 26`, but used Android 10+ MediaStore fields (`RELATIVE_PATH`, `IS_PENDING`) unconditionally and lacked the required legacy write permission.

### 12. No automated coverage for permission decisions

Permission behaviour across Android versions was embedded only in UI code. The repair extracts it into a pure policy and adds JVM tests.

---

## Root-cause analysis

The signing regression came from solving CI update repeatability with a committed fallback key instead of separating build identities and trust levels. This made signatures repeatable, but exposed a reusable signing identity, mixed stable and test artifacts, and produced documentation inconsistent with actual outputs.

The media regression had a parallel cause: media access was treated as a global application prerequisite instead of an optional capability of the Gallery screen.

The repair changes architectural boundaries:

- production signing is isolated;
- ordinary CI release builds are unsigned;
- debug identity is explicitly ephemeral;
- Gallery access is optional and contextual;
- permission policy is unit-testable;
- storage behaviour is version-gated.

---

## Code modifications

### `app/build.gradle.kts`

- Removed the fallback to `signing/debug-only.keystore`.
- Removed hard-coded alias and passwords.
- Added environment-driven production signing through `KEYSTORE_FILE`, `KEYSTORE_PASS`, `KEY_ALIAS`, and `KEY_PASS`.
- Made signing all-or-nothing; partial configuration fails immediately.
- Restored Android's ordinary debug signing.
- Release builds remain unsigned when production signing variables are absent.
- Bumped defaults to `versionName 1.8.7`, `versionCode 29`.
- Added JUnit for JVM tests.

### `app/signing/debug-only.keystore`

Removed from the repair branch tree. It remains recoverable from Git history and must be treated as permanently public.

### `app/src/main/AndroidManifest.xml`

Added/adjusted:

- `CAMERA`;
- `READ_MEDIA_IMAGES`;
- `READ_MEDIA_VISUAL_USER_SELECTED`;
- `READ_EXTERNAL_STORAGE` through API 32;
- `WRITE_EXTERNAL_STORAGE` through API 28.

### `GalleryPermissionPolicy.kt`

New pure policy defining `FULL`, `PARTIAL`, `APP_OWNED_ONLY`, and `DENIED`, plus version-specific required permissions and access-state resolution.

### `MediaRepository.kt`

- New captures save to `DCIM/SpectralCamera`.
- Queries exact `DCIM/SpectralCamera` and historical `Pictures/SpectralCamera` paths.
- Android 10+ uses `RELATIVE_PATH` and `IS_PENDING`.
- Android 8–9 uses the legacy public-directory/`DATA` path.
- Invalid filenames are filtered out.
- Failed writes attempt to remove incomplete MediaStore rows.
- Broad wildcard folder matching was removed.

### `SpectralViewModel.kt`

Replaced the bare gallery list with `GalleryUiState` containing items, loading state, and recoverable error text. Reloading preserves current visible items.

### `SpectralCameraApp.kt`

- Startup no longer requires photo-library access.
- Android 10+ startup requests camera only.
- Android 8–9 also requests legacy write access for saving captures.
- Gallery count comes from `galleryState.items`.
- Permission-gate wording explains that historical photo access is separate.

### `GalleryScreen.kt`

- Removed automatic permission request.
- Added explicit explanation and user-triggered action.
- Handles full, partial, app-owned-only, and denied access.
- Rechecks state and refreshes MediaStore on `ON_RESUME`.
- Adds loading, empty, retryable error, and unavailable-thumbnail states.
- Uses the auto-mirrored back icon.

### `GalleryPermissionPolicyTest.kt`

Added JVM tests for Android 8, 12, 13, and 14 permission/access decisions. These tests passed in CI.

---

## CI and workflow changes

### `.github/workflows/android.yml`

Ordinary CI now:

1. Rejects tracked `.jks` and `.keystore` files.
2. Rejects references to retired signing credentials in build/app sources.
3. Runs `testDebugUnitTest`, `assembleDebug`, and `assembleRelease`.
4. Requires `app-release-unsigned.apk` to exist.
5. Fails if a signed `app-release.apk` unexpectedly appears.
6. Uploads `spectral-camera-debug-ephemeral` and `spectral-camera-release-unsigned`.

An early guard version self-matched its own regex; the scan was narrowed to application and build sources.

### `.github/workflows/release.yml`

Tagged stable releases now:

1. Reject tracked signing material.
2. Validate `vX.Y.Z` tags.
3. Require `KEYSTORE_B64`, `KEYSTORE_PASS`, `KEY_ALIAS`, `KEY_PASS`, and `RELEASE_CERT_SHA256`.
4. Decode the key only into runner temporary storage.
5. Compare keystore certificate SHA-256 with the pinned secret.
6. Run JVM tests.
7. Build signed APK and AAB.
8. Verify APK signature, certificate, and package ID `com.renardoberou.spectralcamera`.
9. Generate `CHECKSUMS.txt` and `RELEASE-MANIFEST.txt`.
10. Publish only if all checks pass.

The production release workflow has not yet been exercised with real secrets on this branch.

---

## Signing architecture

### Stable application

Package ID: `com.renardoberou.spectralcamera`

Stable releases must use the private key that signed official `v1.8.2`.

The official uploaded `v1.8.2` AAB certificate was analysed:

- Owner: `CN=Resonant Systems, O=Resonant Systems`
- Key: RSA 4096-bit
- Signature algorithm: SHA384withRSA
- Valid until: 2053-11-24

SHA-256 fingerprint:

`A2:4E:6C:D7:93:93:65:98:35:58:25:75:1F:85:64:F8:77:D2:2E:90:40:47:27:68:82:17:6F:A8:19:C5:14:C5`

Normalized value intended for `RELEASE_CERT_SHA256`:

`a24e6cd793936598355825751f8564f877d22e904047276882176fa819c514c5`

The private keystore has not been uploaded and should remain private. Its fingerprint must be checked locally.

### Ordinary debug builds

Use Android's standard debug identity. They are ephemeral and not guaranteed to update APKs from unrelated CI runners.

### Ordinary CI release builds

Unsigned compile-verification artifacts only. They are not stable releases.

### Retired public test lineage

The committed `debug-only.keystore` is permanently compromised and must never be used for trusted release signing.

### Recommended future nightly architecture

Not implemented. Recommended later: `.nightly` package suffix, separate label, separate private nightly key, independent versioning, and no access to stable release secrets.

---

## Gallery permission model

### Android 8–9 (API 26–28)

Startup requires camera and legacy write access. Full historical gallery access uses legacy read permission. Saving uses the legacy public-directory path.

### Android 10–12L (API 29–32)

Startup requires camera only. Current-install owned media may be visible without broad read access; historical captures require legacy read permission.

### Android 13 (API 33)

Full library access uses `READ_MEDIA_IMAGES`.

### Android 14+ (API 34+)

The Gallery requests `READ_MEDIA_IMAGES` and `READ_MEDIA_VISUAL_USER_SELECTED`, distinguishes full/partial/app-owned-only/denied, and rechecks on every resume.

---

## MediaStore compatibility

Current destination: `DCIM/SpectralCamera`  
Historical destination still queried: `Pictures/SpectralCamera`

Queries use exact known paths and filename validation. Query errors become recoverable UI state, unreadable thumbnails show `Preview unavailable`, and failed writes attempt cleanup.

---

## Front-camera orientation work

The `1.8.6` commit removed explicit front-camera horizontal scaling in `SpectralGlPipeline.kt`. No additional transform was added in this repair because correct behaviour depends on physical CameraX/SurfaceTexture output.

Required behaviour:

- front preview unmirrored;
- processed save matches preview;
- original save physically correct;
- repeated front/rear switching stable;
- relaunch with front camera selected stable.

This remains a physical-device release blocker.

---

## CI result

Successful run:

- Run: 56
- Head: `4ec7741c28354d47c99e92786fb5e2a449ae3939`
- Conclusion: success

Passed: signing guard, unit tests, debug build, unsigned release build, unsigned-release verification, and both artifact uploads.

Artifacts:

- `spectral-camera-debug-ephemeral`
- `spectral-camera-release-unsigned`

The branch is compile-tested and unit-tested, but not production-signed or physically validated.

---

## Documentation updates

### `README.md`

Updated for development version, actual tests, signing separation, unsigned CI artifacts, Android 14 partial access, historical capture recovery, migration implications, and current limitations.

### `docs/RELEASE.md`

Updated for stable lineage, retired public key, certificate comparison, required secrets, ordinary CI behaviour, tagged-release validation, post-release verification, and mandatory update-path tests.

Future agents must keep documentation aligned with actual Gradle/workflow behaviour.

---

## GitHub issue and PR status

### Draft PR `#4`

Open, draft, and mergeable. Must remain unmerged until release blockers are cleared.

### Issue `#3`

Records the original main-branch missing-keystore failure and remains open because `main` is not repaired until PR `#4` merges.

Temporary auto-filed CI issues created during repair iterations were closed after CI passed.

---

## Remaining release blockers

### 1. Verify private stable keystore

Run locally:

```bash
keytool -list -v \
  -keystore /path/to/spectral-camera-release.jks \
  -alias YOUR_ALIAS
```

The SHA-256 fingerprint must equal the official value above. Never upload the private keystore or passwords.

### 2. Configure `RELEASE_CERT_SHA256`

Set it to the normalized fingerprint only after independent private-keystore confirmation.

### 3. Physical orientation testing

On Motorola Edge 60 Fusion / Android 16, test rear, front, front→rear→front, and relaunch-with-front-selected. Verify preview, processed save, and original save using printed text and an asymmetric object.

### 4. Android 16 Gallery testing

Test full access, selected-photo access, denied access, Settings changes while backgrounded, process restart, both historical folders, inaccessible selected photos, and new capture after each state.

### 5. Stable update path

Install official `v1.8.2`, take captures, then install the stable-signed `1.8.7` candidate without uninstalling. Confirm data, Gallery, historical images, and new capture.

### 6. Incompatible test-build migration

Verify Android rejects incompatible-signature update; uninstall test build once; install stable; grant Gallery access; confirm shared-storage captures remain.

### 7. Exercise production release workflow

After secrets and device checks, verify signed APK/AAB build, package/certificate checks, checksums, manifest, and installation.

---

## Release checklist

### Before merge

- [ ] Private stable keystore fingerprint matches official `v1.8.2` certificate.
- [ ] `RELEASE_CERT_SHA256` configured.
- [ ] Front and rear physical tests pass.
- [ ] Processed and original orientations are correct.
- [ ] Android 16 full/partial/denied Gallery tests pass.
- [ ] Historical Pictures and DCIM captures appear.
- [ ] Official `v1.8.2 → 1.8.7` update succeeds without uninstall.
- [ ] Incompatible test-build migration behaves as documented.
- [ ] PR `#4` diff reviewed after final commits.

### Before stable tag

- [ ] Tag and `versionName` agree.
- [ ] `versionCode` exceeds every previous stable code.
- [ ] CI green on exact tagged commit.
- [ ] Production secrets present.
- [ ] Stable key backup verified.
- [ ] Release notes include migration warning for test-build users.

### After publication

- [ ] Verify checksums.
- [ ] Verify APK signature and certificate.
- [ ] Verify package ID.
- [ ] Install published APK over official `v1.8.2`.
- [ ] Confirm capture and Gallery on reference phone.
- [ ] Close issue `#3` only after merged `main` and published build are verified.

---

## Recommendations for future agents

Read, in order:

1. This handoff.
2. PR `#4` and final diff.
3. `docs/RELEASE.md`.
4. `app/build.gradle.kts`.
5. Both workflows.
6. `GalleryPermissionPolicy.kt` and tests.
7. `MediaRepository.kt`.
8. `GalleryScreen.kt`.
9. `SpectralGlPipeline.kt` before changing transforms.

Never commit signing material, hard-code passwords, use the production key in PR CI, call ephemeral APKs stable, bypass certificate verification, or rotate the stable key casually.

Keep camera operation independent from broad Gallery read access. Preserve historical `Pictures/SpectralCamera` support unless an explicit migration is implemented. Do not revert to broad `%SpectralCamera%` MediaStore matching.

Treat front-camera transforms carefully: CameraX, `SurfaceTexture`, sensor rotation, shader matrices, and still processing can each contribute transforms. Apply horizontal correction exactly once and test preview and saved output together.

Recommended next test work:

- extract and test filename parsing;
- test MediaStore query construction;
- Compose tests for full/partial/denied/empty/error Gallery states;
- instrumentation tests on Android 13, 14, and 16;
- release workflow dry-run verification without publication.

---

## Current authoritative state at report creation

- `main` still contains the problematic commits.
- Repair work is on `fix/signing-gallery-permissions`.
- Draft PR `#4` is open and mergeable.
- Repair-branch CI is green.
- Public keystore is removed from the repair branch tree.
- Stable certificate fingerprint is known from official `v1.8.2` AAB.
- Private stable keystore is not yet independently fingerprint-verified.
- Physical-device and update-migration testing remain incomplete.
- No stable `1.8.7` release has been published.

Future agents must verify these statements are still current before acting.
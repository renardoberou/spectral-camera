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
3. Replaced the previous release-signing arrangement with a `stable` signing configuration that defaulted to:

   `app/signing/debug-only.keystore`

   with hard-coded public credentials.

It also changed the default app version to `1.8.6`, version code `28`.

### `5178cc8875bf3391dccb652930a8c9e9a53800b0`

Commit message:

`fix(signing): include the stable throwaway keystore (gitignore excluded it)`

This commit force-added the previously missing binary keystore because `.gitignore` correctly excluded `*.keystore` files.

The immediate build failure was therefore fixed, but the repository then contained reusable signing private-key material.

---

## Problems identified

## 1. Missing keystore caused CI failure

The Gradle configuration referenced:

`app/signing/debug-only.keystore`

but the repository's `.gitignore` excluded `*.keystore` files. CI failed at:

`:app:validateSigningDebug`

with a missing-keystore error.

GitHub issue `#3` records this original main-branch failure.

---

## 2. Public signing private key

The follow-up commit placed the following in a public repository:

- the keystore;
- the key alias;
- the keystore password;
- the key password.

Anyone with those values can produce an APK signed by that certificate. Android would treat such an APK as a valid update for any installation using the same package name and that certificate.

The key must therefore be considered permanently compromised and unsuitable for trusted stable distribution. Removing it from the latest tree does not make it secret again.

---

## 3. Debug and release signing were coupled

The same public signing configuration was assigned to both:

- `debug`;
- `release`.

This caused ordinary CI to generate signed release artifacts and confused three different concepts:

- ephemeral development builds;
- repeatable internal test builds;
- trusted stable releases.

The repaired architecture separates these concerns.

---

## 4. False promise of update compatibility

The `1.8.6` commit claimed that stable signing would eliminate reinstalls. This is only true after a user is already on the same signing lineage.

Android update compatibility is determined by:

- package name;
- signing certificate.

Consequences:

- official `v1.8.2` can update in place only to an APK signed with the same private stable key;
- a build signed with the retired public key cannot update an official stable installation;
- an older CI build signed with an unrelated debug key also cannot update an official stable installation;
- users on incompatible test builds require one final uninstall before installing the repaired stable build.

Images in shared storage remain on the device after uninstall.

---

## 5. Startup incorrectly required photo-library permission

Even after Gallery-specific permission handling was added, the application shell still required media permission at startup.

This caused several problems:

- first launch was blocked by a permission not required for camera operation;
- the Gallery-specific permission explanation was bypassed;
- Android 14 selected-photo behaviour was not represented correctly;
- camera access and historical-gallery access were treated as one permission domain.

The repaired app requests only what is needed to start the camera. Gallery access is requested later, explicitly and contextually.

---

## 6. Android 14+ selected-photo access was not handled

The app requested `READ_MEDIA_IMAGES` but did not explicitly declare and model:

`READ_MEDIA_VISUAL_USER_SELECTED`

On Android 14 and newer, users can grant access to only selected photos. The previous implementation did not distinguish:

- full library access;
- partial selected-photo access;
- app-owned-only access;
- denied access.

It also did not re-evaluate access after the application returned from the background.

---

## 7. Gallery permission was requested automatically

The Gallery screen launched the permission request as soon as the composable entered composition.

The repaired flow is user-initiated and explains why access is useful before showing the platform dialog.

---

## 8. Gallery state lacked error and loading semantics

The previous Gallery exposed only a list of items. It did not model:

- loading;
- permission-limited state;
- query error;
- empty state;
- unavailable thumbnail.

A failed MediaStore read could therefore result in a blank or misleading interface.

---

## 9. MediaStore query was too broad

The previous query used a wildcard path selection containing `SpectralCamera`.

Problems:

- unrelated folders with similar names could match;
- path semantics were imprecise;
- historical folder migration was not explicit.

The repaired query uses exact current and historical folders and then filters results by valid Spectral Camera filenames.

---

## 10. Historical captures existed in two locations

Different versions saved captures under:

- `Pictures/SpectralCamera`;
- `DCIM/SpectralCamera`.

A DCIM-only exact query would hide older captures. The repaired implementation queries both exact paths.

New captures continue to use:

`DCIM/SpectralCamera`

because camera media appears more prominently in standard gallery applications.

---

## 11. Android 8–9 compatibility was incomplete

The project advertises `minSdk 26`, but the save path unconditionally used Android 10+ MediaStore fields:

- `RELATIVE_PATH`;
- `IS_PENDING`.

The application also lacked the required Android 8–9 write permission for the legacy shared-storage path.

The repaired implementation includes a version-gated legacy save/query path using `DATA` and `WRITE_EXTERNAL_STORAGE` only through API 28.

---

## 12. No automated coverage for permission decisions

The repository previously documented that no automated tests existed. Permission behaviour across Android versions was therefore encoded only inside UI code.

The repair extracts permission logic into a pure policy and adds JVM tests.

---

# Root-cause analysis

The main technical root cause was solving a distribution problem at the Gradle fallback level rather than defining separate build identities and trust levels.

The intended goal was understandable: CI APKs should update rather than require uninstalling on every build. The selected implementation committed a stable test key and applied it to both debug and release variants.

That solved repeatability but created these new risks:

- public possession of a reusable signing identity;
- confusion between test and stable artifacts;
- incompatible lineages between official stable and public test builds;
- documentation that no longer matched actual output;
- CI release artifacts that appeared more trustworthy than they were.

The media-permission regression had a similar architectural cause: media access was treated as a global app prerequisite instead of an optional capability of one screen.

The repair therefore changes boundaries, not only individual lines:

- production signing is isolated;
- ordinary CI remains unsigned for release builds;
- debug identity is explicitly ephemeral;
- Gallery permission is optional and contextual;
- permission policy is testable outside Compose;
- storage behaviour is version-gated.

---

# Code modifications

## `app/build.gradle.kts`

Changes:

- removed the fallback to `signing/debug-only.keystore`;
- removed hard-coded alias and passwords;
- added environment-driven production signing variables:
  - `KEYSTORE_FILE`;
  - `KEYSTORE_PASS`;
  - `KEY_ALIAS`;
  - `KEY_PASS`;
- made signing configuration all-or-nothing;
- partial signing configuration now fails immediately with a clear error;
- restored Android's ordinary debug signing behaviour;
- release builds remain unsigned when production signing variables are absent;
- bumped defaults to:
  - `versionName = 1.8.7`;
  - `versionCode = 29`;
- added JUnit dependency for JVM tests.

Design rule:

Production signing must never have a committed or hard-coded fallback.

---

## `app/signing/debug-only.keystore`

Status:

- removed from the repair branch tree.

Important:

The file remains recoverable from Git history and must be treated as permanently public. It must never be restored or used for trusted release signing.

---

## `app/src/main/AndroidManifest.xml`

Added or adjusted permissions:

- `CAMERA`;
- `READ_MEDIA_IMAGES`;
- `READ_MEDIA_VISUAL_USER_SELECTED`;
- `READ_EXTERNAL_STORAGE` with `maxSdkVersion="32"`;
- `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion="28"`.

This supports the permission model from Android 8 through Android 16 while keeping legacy permissions constrained to the OS versions that require them.

---

## `app/src/main/java/com/renardoberou/spectralcamera/core/media/GalleryPermissionPolicy.kt`

New file.

Contains:

- `GalleryAccessLevel`:
  - `FULL`;
  - `PARTIAL`;
  - `APP_OWNED_ONLY`;
  - `DENIED`;
- Android-version-specific required permissions;
- pure access-state resolution logic.

This policy is intentionally independent of Compose so it can be unit-tested.

---

## `app/src/main/java/com/renardoberou/spectralcamera/core/media/MediaRepository.kt`

Changes:

- new captures save to `DCIM/SpectralCamera`;
- gallery queries both exact paths:
  - `DCIM/SpectralCamera`;
  - `Pictures/SpectralCamera`;
- Android 10+ uses `RELATIVE_PATH` and `IS_PENDING`;
- Android 8–9 uses legacy public-directory and `DATA` handling;
- invalid or unrelated filenames are filtered out;
- failed writes attempt to delete the incomplete MediaStore row;
- broad wildcard folder matching was removed.

Filename format remains:

`spectral_(raw|proc)_(SIMULATED_IR|EXTERNAL_IR|THERMAL)_PRESET_YYYYMMDD_HHMMSS.jpg`

Future agents should preserve compatibility with existing filenames unless a migration is deliberately implemented.

---

## `app/src/main/java/com/renardoberou/spectralcamera/core/state/SpectralViewModel.kt`

Changes:

- replaced a bare gallery-item flow with `GalleryUiState`;
- state now contains:
  - items;
  - loading flag;
  - recoverable error message;
- Gallery refresh preserves current visible items while reloading;
- MediaStore failures are converted into UI state rather than escaping unchecked.

---

## `app/src/main/java/com/renardoberou/spectralcamera/ui/SpectralCameraApp.kt`

Changes:

- startup no longer requires photo-library permission;
- Android 10+ startup requires camera permission only;
- Android 8–9 startup also requests legacy storage write access because capture saving requires it;
- Gallery count now comes from `galleryState.items`;
- lifecycle owner import moved to `androidx.lifecycle.compose.LocalLifecycleOwner`;
- permission-gate wording now explains that historical photo access is separate and optional.

Architectural rule:

Camera operation must not depend on broad media-library read access.

---

## `app/src/main/java/com/renardoberou/spectralcamera/ui/screens/GalleryScreen.kt`

Major redesign.

Changes:

- no automatic permission dialog on entry;
- explicit user-facing permission explanation;
- uses `RequestMultiplePermissions`;
- detects full, partial, app-owned-only, and denied states;
- refreshes access state and MediaStore on `ON_RESUME`;
- explains partial selected-photo access;
- provides `Manage photo access` or `Show previous captures` actions;
- displays loading state;
- displays meaningful empty states;
- displays recoverable error state with retry;
- handles unavailable thumbnails without crashing or remaining indefinitely on `Loading`;
- replaces deprecated back icon with auto-mirrored version.

Physical Android 16 validation is still required for the exact system permission-dialog behaviour.

---

## `app/src/test/java/com/renardoberou/spectralcamera/core/media/GalleryPermissionPolicyTest.kt`

New JVM tests cover:

- Android 14 full access;
- Android 14 selected-photo partial access;
- Android 14 app-owned-only access;
- Android 13 full media access;
- Android 12 app-owned-only behaviour without legacy grant;
- Android 8 denied behaviour without legacy grant;
- required permission arrays for Android 12, 13, and 14.

These tests passed in CI.

---

# CI and workflow changes

## `.github/workflows/android.yml`

Ordinary CI now:

1. checks out the repository;
2. rejects tracked `.jks` and `.keystore` files;
3. rejects references to the retired public signing credentials in build or app sources;
4. sets up JDK 17 and Android SDK;
5. runs:

   `./gradlew testDebugUnitTest assembleDebug assembleRelease --stacktrace`

6. verifies that:
   - `app-release-unsigned.apk` exists;
   - `app-release.apk` does not exist;
7. uploads:
   - `spectral-camera-debug-ephemeral`;
   - `spectral-camera-release-unsigned`.

The first version of the security guard incorrectly matched the regex written inside its own workflow file. That self-match was corrected by limiting the scan to build and application sources.

Ordinary CI must not receive the production signing key.

---

## `.github/workflows/release.yml`

Tagged stable release workflow now:

1. rejects tracked signing material;
2. validates tags against `vX.Y.Z`;
3. requires these secrets:
   - `KEYSTORE_B64`;
   - `KEYSTORE_PASS`;
   - `KEY_ALIAS`;
   - `KEY_PASS`;
   - `RELEASE_CERT_SHA256`;
4. decodes the private release keystore into the runner's temporary directory;
5. extracts its SHA-256 certificate fingerprint;
6. compares it with `RELEASE_CERT_SHA256`;
7. derives `VERSION_NAME` from the tag;
8. runs JVM tests;
9. builds signed APK and AAB;
10. verifies the APK signature and certificate;
11. verifies package ID:

    `com.renardoberou.spectralcamera`

12. generates:
    - `CHECKSUMS.txt`;
    - `RELEASE-MANIFEST.txt`;
13. publishes only if all validation succeeds.

The release workflow has not yet been exercised with production secrets on this branch.

---

# Signing architecture

## Stable application

Package ID:

`com.renardoberou.spectralcamera`

Stable releases must be signed only with the private key used for official `v1.8.2`.

The uploaded official `v1.8.2` AAB was analysed. The certificate identity is:

- **Owner:** `CN=Resonant Systems, O=Resonant Systems`
- **Key:** RSA 4096-bit
- **Signature algorithm:** SHA384withRSA
- **Valid until:** 2053-11-24

Stable certificate SHA-256 fingerprint:

`A2:4E:6C:D7:93:93:65:98:35:58:25:75:1F:85:64:F8:77:D2:2E:90:40:47:27:68:82:17:6F:A8:19:C5:14:C5`

Normalized value intended for GitHub secret `RELEASE_CERT_SHA256`:

`a24e6cd793936598355825751f8564f877d22e904047276882176fa819c514c5`

Important:

The private stable keystore itself has not been uploaded and should remain private. Its certificate fingerprint must be checked locally and must exactly match the value above.

---

## Ordinary debug builds

Debug builds use Android's ordinary debug-signing behaviour.

These are ephemeral CI/testing artifacts and must not be described as stable or update-compatible across unrelated CI runners.

---

## Ordinary CI release builds

These are unsigned compile-verification artifacts.

They are not installable until signed and must not be distributed as stable releases.

---

## Retired public test lineage

The committed `debug-only.keystore` is permanently compromised.

Any APK signed with it belongs to a retired test lineage. Such builds may update each other, but they must not be trusted and cannot update the official stable lineage.

---

## Recommended future nightly architecture

Not implemented in this repair branch.

Recommended later:

- package suffix such as `.nightly`;
- visible app label `Spectral Camera Nightly`;
- separate private nightly key stored in GitHub Actions secrets;
- independent version-code strategy;
- separate capture folder if test images must not mix with stable captures.

Do not use the stable production key for ordinary nightly or pull-request builds.

---

# Gallery permission model

## Android 8–9 — API 26–28

Startup requires:

- `CAMERA`;
- `WRITE_EXTERNAL_STORAGE`.

Full historical gallery access requires legacy read permission.

MediaStore saving uses the legacy public-directory path.

---

## Android 10–12L — API 29–32

Startup requires camera permission only.

The app can display media attributed to the current installation without broad read access. Historical captures from prior installations require the user to grant legacy read access.

---

## Android 13 — API 33

Full image-library access uses:

`READ_MEDIA_IMAGES`

Without it, the app can still operate and may display current-install app-owned media.

---

## Android 14+ — API 34 and later

The Gallery requests:

- `READ_MEDIA_IMAGES`;
- `READ_MEDIA_VISUAL_USER_SELECTED`.

The app distinguishes:

- full access;
- partial selected-photo access;
- app-owned-only access;
- denied access.

Access is re-evaluated on every resume because the user can change the grant while the app is backgrounded.

---

# MediaStore compatibility

## Current save destination

`DCIM/SpectralCamera`

## Historical destination still queried

`Pictures/SpectralCamera`

## Query safety

The repository now uses exact paths rather than `LIKE %SpectralCamera%`.

Results are additionally filtered through the established filename parser.

## Failure handling

- query exceptions are surfaced as recoverable Gallery UI state;
- unreadable thumbnails produce `Preview unavailable`;
- failed writes attempt to clean up the incomplete MediaStore entry.

---

# Front-camera orientation work

The pre-repair `1.8.6` commit removed explicit front-camera horizontal scaling in `SpectralGlPipeline.kt`.

No additional front-camera transform was added during this repair pass because the diff did not show a clear code-level defect and the correct behaviour depends on physical CameraX/SurfaceTexture output.

Required product behaviour:

- front preview is unmirrored;
- processed save matches the preview;
- original save is physically correct;
- repeated front/rear switching does not introduce a double flip;
- relaunch with front camera selected remains correct.

This remains a physical-device release blocker.

---

# CI result

Successful Android CI run:

- **Run:** 56
- **Head:** `4ec7741c28354d47c99e92786fb5e2a449ae3939`
- **Conclusion:** success

Passed steps:

- repository checkout;
- tracked-signing-material guard;
- JDK setup;
- Android SDK setup;
- unit tests;
- debug build;
- unsigned release build;
- unsigned-release verification;
- debug artifact upload;
- unsigned release artifact upload.

Artifacts:

- `spectral-camera-debug-ephemeral`
- `spectral-camera-release-unsigned`

The repair branch is therefore compile-tested and unit-tested, but not production-signed or physically validated.

---

# Documentation updates

## `README.md`

Updated to describe:

- development version `1.8.7` / `29`;
- actual test coverage;
- signing separation;
- unsigned ordinary CI release artifacts;
- Android 14 selected-photo access;
- historical gallery recovery;
- migration implications of incompatible test signatures;
- current limitations.

## `docs/RELEASE.md`

Updated to describe:

- stable signing lineage;
- retired public key;
- certificate extraction and comparison;
- required GitHub secrets;
- ordinary CI behaviour;
- tagged release validation;
- release verification;
- mandatory update-path testing.

Future agents must keep these documents aligned with actual Gradle and workflow behaviour.

---

# GitHub issue and PR status

## Draft PR `#4`

Title:

`Repair signing isolation and Android gallery permissions`

Status:

- open;
- draft;
- mergeable;
- must remain unmerged until release blockers are cleared.

## Issue `#3`

Records the original main-branch missing-keystore failure.

It remains open because `main` still contains the problematic signing architecture until PR `#4` is merged.

## Temporary CI issues

Issues created automatically during repair iterations were closed after CI passed. These were implementation noise rather than unresolved production defects.

---

# Remaining release blockers

## 1. Verify the private stable keystore

Run locally against the private keystore:

```bash
keytool -list -v \
  -keystore /path/to/spectral-camera-release.jks \
  -alias YOUR_ALIAS
```

The SHA-256 fingerprint must equal:

`A2:4E:6C:D7:93:93:65:98:35:58:25:75:1F:85:64:F8:77:D2:2E:90:40:47:27:68:82:17:6F:A8:19:C5:14:C5`

Do not upload the private keystore or passwords to chat, issues, commits, or logs.

---

## 2. Configure `RELEASE_CERT_SHA256`

Set the GitHub Actions secret to:

`a24e6cd793936598355825751f8564f877d22e904047276882176fa819c514c5`

Only after the private keystore fingerprint has been independently confirmed.

---

## 3. Physical orientation testing

Reference device:

Motorola Edge 60 Fusion, Android 16.

Test matrix:

| Camera state | Preview | Processed save | Original save |
|---|---|---|---|
| Rear camera | Correct | Matches preview | Correct |
| Front camera | Unmirrored | Matches preview | Physically correct |
| Front → rear → front | Stable | Stable | Stable |
| Relaunch with front selected | Stable | Stable | Stable |

Use printed text and an asymmetric object so mirroring is obvious.

---

## 4. Android 16 gallery permission testing

Test:

- full photo access;
- selected-photo access;
- denied access;
- access changed in Settings while the app is backgrounded;
- process killed and reopened;
- historical captures in both `Pictures/SpectralCamera` and `DCIM/SpectralCamera`;
- inaccessible or removed selected photos;
- new capture after each permission state.

---

## 5. Stable update-path test

Required sequence:

1. Install official `v1.8.2`.
2. Take captures.
3. Confirm files exist in shared storage.
4. Build/sign the `1.8.7` candidate with the verified private stable key.
5. Install it over `v1.8.2` without uninstalling.
6. Confirm:
   - update succeeds;
   - application data remains;
   - Gallery remains usable;
   - historical captures are visible after appropriate permission;
   - new captures work.

---

## 6. Incompatible test-build migration

Required sequence:

1. Install a build signed by the retired public key or unrelated CI debug key.
2. Attempt to install the stable candidate.
3. Confirm Android rejects the update because the certificate differs.
4. Uninstall the test build.
5. Install the stable candidate.
6. Grant Gallery access.
7. Confirm shared-storage captures remain visible.

This one-time uninstall is expected and must be documented for affected testers.

---

## 7. Exercise the production release workflow

After secrets and physical validation are complete, create a controlled release candidate or final tag and verify:

- release workflow accepts the private keystore;
- certificate pin matches;
- signed APK and AAB build;
- package ID check passes;
- APK certificate matches the expected stable fingerprint;
- checksums and manifest are correct;
- release assets install as expected.

Do not create a public release solely to test whether secrets are configured correctly unless the release can be immediately removed and no users rely on it.

---

# Release checklist

## Before merge

- [ ] Private stable keystore fingerprint matches official `v1.8.2` certificate.
- [ ] `RELEASE_CERT_SHA256` secret configured.
- [ ] Front-camera physical tests pass.
- [ ] Rear-camera physical tests pass.
- [ ] Processed and original images have correct orientation.
- [ ] Android 16 full-access Gallery test passes.
- [ ] Android 16 selected-photo Gallery test passes.
- [ ] Android 16 denied-access Gallery test passes.
- [ ] Historical `Pictures/SpectralCamera` captures appear.
- [ ] Historical `DCIM/SpectralCamera` captures appear.
- [ ] Official `v1.8.2 → 1.8.7` update succeeds without uninstall.
- [ ] Incompatible test-build migration behaves as documented.
- [ ] PR `#4` diff reviewed again after all final commits.
- [ ] PR remains draft until all checks are complete.

## Before stable tag

- [ ] `versionName` and tag agree.
- [ ] `versionCode` is greater than every previous stable code.
- [ ] CI is green on the exact commit to be tagged.
- [ ] Production release secrets are present.
- [ ] Private stable key backup has been verified.
- [ ] Release notes include migration notice for test-build users.

## After publication

- [ ] Download published APK and AAB.
- [ ] Verify checksums.
- [ ] Verify APK signature.
- [ ] Verify certificate SHA-256.
- [ ] Verify package ID.
- [ ] Install published APK over official `v1.8.2`.
- [ ] Confirm capture and Gallery behaviour on the reference phone.
- [ ] Close issue `#3` only after the merged main branch and published build are verified.

---

# Recommendations for future agents

## Read before editing

Future agents should inspect, in this order:

1. this handoff document;
2. PR `#4` and its final diff;
3. `docs/RELEASE.md`;
4. `app/build.gradle.kts`;
5. `.github/workflows/android.yml`;
6. `.github/workflows/release.yml`;
7. `GalleryPermissionPolicy.kt` and its tests;
8. `MediaRepository.kt`;
9. `GalleryScreen.kt`;
10. `SpectralGlPipeline.kt` before touching camera transforms.

---

## Preserve signing boundaries

Never:

- commit `.jks` or `.keystore` files;
- hard-code signing passwords;
- use the production key in pull-request CI;
- describe ephemeral debug APKs as stable;
- bypass certificate verification;
- rotate the stable key casually.

---

## Preserve gallery optionality

The application must remain usable as a camera without broad photo-library read access.

Media permission is for discovering historical captures, not for enabling the camera itself on modern Android.

---

## Preserve historical folder support

Do not remove `Pictures/SpectralCamera` support without an explicit migration plan. Older users may still have valid captures there.

---

## Avoid broad MediaStore matching

Do not revert to `%SpectralCamera%` path matching. Use exact known paths and validated filenames.

---

## Treat front-camera transforms carefully

CameraX, `SurfaceTexture`, sensor rotation, shader position matrices, and still-capture processing can each contribute orientation transforms.

Apply a horizontal correction exactly once. Do not add separate compensating flips without testing preview and saved output together.

---

## Expand tests incrementally

Recommended next test work:

- move filename parsing into a testable public/internal utility;
- add unit tests for valid and invalid capture filenames;
- add Robolectric tests for MediaStore query construction;
- add Compose tests for full, partial, denied, empty, and error Gallery states;
- add instrumentation tests on Android 13, 14, and 16;
- add a release workflow dry-run mechanism that verifies signing without publishing.

---

## Consider a nightly application later

A properly isolated nightly build would improve phone-based development.

Recommended properties:

- package ID suffix `.nightly`;
- different app label and possibly icon badge;
- separate private nightly key;
- independent update lineage;
- no access to stable release secrets;
- optional separate capture folder.

Implement this only as a separate change after the stable repair is merged and released.

---

# Current authoritative state

At the time this report was written:

- `main` still contains the original problematic commits;
- repair work exists on `fix/signing-gallery-permissions`;
- draft PR `#4` is open and mergeable;
- repair-branch CI is green;
- the public keystore is removed from the repair branch tree;
- stable certificate fingerprint is known from official `v1.8.2` AAB;
- the private stable keystore has not yet been independently fingerprint-verified;
- physical-device and update-migration testing remain incomplete;
- no stable `1.8.7` release has been published.

Future agents must verify that these statements are still current before acting. Do not assume the branch, PR, CI, issue, or release state has remained unchanged.

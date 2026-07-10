# Release signing and publication

Spectral Camera has two distinct build paths:

1. ordinary CI compiles tests, an ephemeral debug APK, and an **unsigned** release APK;
2. the tag-triggered `release` workflow builds the only signed stable APK/AAB intended for distribution.

No keystore, private key, password, or fallback signing credential may be committed to this repository.

## Current stable lineage

The latest published stable release is [`v1.8.2`](https://github.com/renardoberou/spectral-camera/releases/tag/v1.8.2). The next stable release must use the same private signing key so Android can update an existing `v1.8.2` installation in place.

Before publishing another stable release, verify the certificate fingerprint of:

- the existing `v1.8.2` APK;
- the private release keystore stored offline and in GitHub Actions secrets.

They must match exactly.

## Retired public test key

Development build 1.8.6 briefly referenced a committed throwaway keystore. That key is public and permanently unsuitable for trusted distribution. It has been removed from the current tree and must not be restored.

Removing it from the repository does not make it secret again. Any APK signed with that key belongs to a retired test lineage.

Users who installed a build signed by the retired key or by an unrelated CI debug key may need to uninstall it once before installing the next stable release. Images in `DCIM/SpectralCamera` survive application uninstall.

## 1. Preserve the private stable key

Keep the stable key in encrypted offline storage with a tested backup. Do not create a new key if the private key used for `v1.8.2` still exists.

For a new application lineage only, a key can be generated with:

```bash
mkdir -p ~/.android-signing/spectral-camera
keytool -genkeypair \
  -v \
  -keystore ~/.android-signing/spectral-camera/spectral-camera-release.jks \
  -storetype JKS \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -alias spectral-camera \
  -dname "CN=Resonant Systems, O=Resonant Systems"
```

Do not use that command to replace the existing stable key unless intentionally starting an incompatible application lineage.

## 2. Obtain the stable certificate SHA-256 fingerprint

From the private keystore:

```bash
keytool -list -v \
  -keystore ~/.android-signing/spectral-camera/spectral-camera-release.jks \
  -alias spectral-camera
```

Record the value labelled `SHA256`. Colons and letter case do not matter to the workflow.

From an APK:

```bash
apksigner verify --verbose --print-certs spectral-camera-v1.8.2.apk
```

Compare the APK's `Signer #1 certificate SHA-256 digest` with the keystore fingerprint. Do not proceed if they differ.

## 3. Configure GitHub Actions secrets

The release workflow requires all five values:

| Secret | Meaning |
|---|---|
| `KEYSTORE_B64` | Base64-encoded private stable keystore |
| `KEYSTORE_PASS` | Keystore password |
| `KEY_ALIAS` | Stable key alias |
| `KEY_PASS` | Key password |
| `RELEASE_CERT_SHA256` | Expected stable certificate SHA-256 fingerprint |

Example using GitHub CLI:

```bash
gh secret set KEYSTORE_B64 \
  --repo renardoberou/spectral-camera \
  --body "$(base64 -w 0 ~/.android-signing/spectral-camera/spectral-camera-release.jks)"

gh secret set KEYSTORE_PASS \
  --repo renardoberou/spectral-camera \
  --body '<keystore-password>'

gh secret set KEY_ALIAS \
  --repo renardoberou/spectral-camera \
  --body 'spectral-camera'

gh secret set KEY_PASS \
  --repo renardoberou/spectral-camera \
  --body '<key-password>'

gh secret set RELEASE_CERT_SHA256 \
  --repo renardoberou/spectral-camera \
  --body '<stable-certificate-sha256>'
```

GitHub stores encrypted secret values. The repository receives only the temporary decoded keystore during the release job.

## 4. Ordinary CI behaviour

`.github/workflows/android.yml` runs on pull requests and pushes to `main`.

It must:

1. fail if any `.jks` or `.keystore` file is tracked;
2. run `testDebugUnitTest`;
3. build `app-debug.apk` with the runner's ordinary debug identity;
4. build `app-release-unsigned.apk` without release secrets;
5. fail if a signed `app-release.apk` unexpectedly appears.

The debug artifact is ephemeral and is not guaranteed to update a debug APK produced by another runner. The unsigned release artifact is compile verification only and cannot be installed until signed.

## 5. Cut a stable release

Before tagging:

- ensure pull-request CI is green;
- increment `versionCode` in `app/build.gradle.kts`;
- set the matching development `versionName`;
- physically test front/rear camera orientation, capture, gallery permissions, and update migration;
- confirm the tag version matches the intended `versionName`.

Create and push a strict semantic-version tag:

```bash
git tag v1.8.7
git push origin v1.8.7
```

The release workflow will:

1. reject tracked signing material;
2. validate the `vX.Y.Z` tag format;
3. decode the stable keystore from secrets;
4. compare its certificate with `RELEASE_CERT_SHA256`;
5. derive `VERSION_NAME` from the tag;
6. run JVM tests;
7. build the signed APK and AAB;
8. verify the APK signature and certificate;
9. verify the package ID is `com.renardoberou.spectralcamera`;
10. generate checksums and `RELEASE-MANIFEST.txt`;
11. publish the assets only if every check passes.

Published assets:

- `spectral-camera-vX.Y.Z.apk`
- `spectral-camera-vX.Y.Z.aab`
- `CHECKSUMS.txt`
- `RELEASE-MANIFEST.txt`

## 6. Verify the published release

After publication:

```bash
sha256sum -c CHECKSUMS.txt
apksigner verify --verbose --print-certs spectral-camera-v*.apk
```

Confirm:

- APK signature verification succeeds;
- certificate SHA-256 equals the stable fingerprint;
- package ID is correct;
- checksum matches;
- `RELEASE-MANIFEST.txt` identifies the intended tag and commit.

## 7. Mandatory update-path test

Before announcing the release:

1. install official `v1.8.2`;
2. take captures and confirm they appear in `DCIM/SpectralCamera`;
3. install the release candidate as an update without uninstalling;
4. confirm app data and gallery access remain intact;
5. verify new captures and front/rear orientation.

Separately verify the documented one-time migration for users of CI/public-test builds:

1. Android rejects an incompatible-signature update as expected;
2. uninstall the test build;
3. install the stable build;
4. grant photo access;
5. confirm historical images remain visible.

## Important rules

- Package name plus signing certificate determine Android update compatibility.
- Never commit a keystore, even if described as debug-only or throwaway.
- Never put signing passwords or private-key data in logs, issues, release notes, or documentation.
- Never publish an ordinary CI artifact as a stable release.
- Never bypass a certificate mismatch to make a release workflow pass.
- Back up the stable key and passwords in more than one secure location.

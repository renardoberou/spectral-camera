# Release signing

Spectral Camera uses environment-driven release signing. The normal CI workflow builds debug and unsigned release APKs for compile verification; the tag workflow builds the signed APK and AAB for distribution.

## 1. Generate or choose a release keystore

Do this once, then back up the `.jks` file and passwords somewhere private. Do **not** commit them.

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

Recommended secret names:

| Secret | Meaning |
|---|---|
| `KEYSTORE_B64` | Base64-encoded `.jks` file |
| `KEYSTORE_PASS` | Keystore password |
| `KEY_ALIAS` | Key alias, recommended `spectral-camera` |
| `KEY_PASS` | Key password |

## 2. Set GitHub Actions secrets

From a machine that has the keystore file:

```bash
gh secret set KEYSTORE_B64 --repo renardoberou/spectral-camera --body "$(base64 -w 0 ~/.android-signing/spectral-camera/spectral-camera-release.jks)"
gh secret set KEYSTORE_PASS --repo renardoberou/spectral-camera --body '<keystore-password>'
gh secret set KEY_ALIAS --repo renardoberou/spectral-camera --body 'spectral-camera'
gh secret set KEY_PASS --repo renardoberou/spectral-camera --body '<key-password>'
```

GitHub stores only the encrypted secret values; the repo never receives the keystore or passwords.

## 3. Cut a signed release

Push a `v*` tag. Example:

```bash
git tag v1.8.1
git push origin v1.8.1
```

The `release` workflow will:

1. decode the private keystore from GitHub Actions secrets;
2. set `VERSION_NAME` from the tag, e.g. `v1.8.1` → `1.8.1`;
3. use the `versionCode` committed in `app/build.gradle.kts`;
4. build `assembleRelease` and `bundleRelease`;
5. verify the APK signature with `apksigner`;
6. publish these release assets:
   - `spectral-camera-vX.Y.Z.apk`
   - `spectral-camera-vX.Y.Z.aab`
   - `CHECKSUMS.txt`

## 4. Verify after release

Download the release assets and run:

```bash
sha256sum -c CHECKSUMS.txt
apksigner verify --verbose spectral-camera-v*.apk
```

Expected signature check starts with:

```text
Verifies
Verified using v2 scheme: true
```

## Important signing notes

- The old CI release APK was debug-signed. This is no longer public-release safe.
- Package name + signing certificate determine update compatibility.
- Bump `versionCode` in `app/build.gradle.kts` before every Play/public release.
- If a debug-signed build is installed on the phone, Android will reject the release-signed APK as an update. Uninstall the old build first.
- For Google Play, upload the `.aab`. Keep the upload key backed up.

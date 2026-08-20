# Spectral Camera — Google Play launch checklist

Status: preparation in progress
Target launch price: **US$14.99 one-time purchase**
Current public release: `v1.21.5`
Package: `com.renardoberou.spectralcamera`

## Release evidence already complete

- Signed release: `v1.21.5`
- Release commit: `aac45ed07b12ff049f160018adff33bd65b8ffcb`
- Signed APK and AAB published on GitHub Releases
- APK/AAB checksums and release manifest published
- Android CI passed on the merge commit
- Official signed APK installed on Motorola Edge 60 Fusion
- Production smoke test passed:
  - camera permission and live preview;
  - Exposure, Focus, WB Auto, Presets, More controls;
  - More drawer without duplicate Presets;
  - Look adjustments without Grain;
  - Contrast and Saturation controls;
  - one full-resolution capture;
  - Gallery/Export reopening the captured image;
  - no fatal Android runtime error observed.

## Product and Play identity

- [ ] Create or select the final Google Play developer account.
- [ ] Complete Google identity, payments, tax, and developer-account verification.
- [ ] Create the app in Play Console with package name `com.renardoberou.spectralcamera`.
- [ ] Use Play App Signing. Treat the current GitHub release certificate as a separate distribution certificate; do not assume direct APK and Play installs can update each other.
- [ ] Upload the AAB from the signed release workflow, not the APK.
- [ ] Confirm Play accepts the AAB and displays version `1.21.5` / version code `58`.

## Store listing draft

Suggested title: `Spectral Camera`

Suggested short description:

> An on-device Android camera for infrared-inspired colour, monochrome IR, and film response.

Suggested full-description direction:

> Spectral Camera is a focused camera for exploring infrared-inspired photography with an ordinary phone camera. It processes the live view and saved image on the device, using scene-aware colour and monochrome responses rather than claiming true infrared or thermal capture.
>
> Capture, compare, and save photographic looks built around sky, foliage, water, architecture, shadow, and light. Keep the camera portrait-locked, frame the scene, choose a look, and capture the result.
>
> Spectral Camera does not require an account or cloud upload for its core experience. Camera access is used for the live preview and capture. Photo access is requested only when you choose to recover or import images.
>
> The app is a photographic interpretation, not official reproduction, calibration, endorsement, or scientific measurement of any commercial film stock or camera system.

Do not use manufacturer/emulsion trademarks as shipped preset names, store claims, accessibility labels, or exported metadata. Do not claim true infrared, thermal capture, official reproduction, or scientific calibration.

## Required store assets

Prepare and review at the actual Play dimensions:

- [ ] App icon: 512×512 PNG, no misleading badge or trademark imagery.
- [ ] Feature graphic: 1024×500 PNG/JPG.
- [ ] Phone screenshots: minimum required set, portrait, readable captions, no debug UI or personal notifications.
- [ ] Optional tablet/large-screen screenshots only if supported and honestly representative.
- [ ] Promo video only if it demonstrates the real shipped app.
- [ ] Content rating questionnaire.
- [ ] Target audience and children-related declarations.
- [ ] App access declaration: no login required for core functionality.
- [ ] Privacy policy URL: `https://renardoberou.github.io/spectral-camera/privacy.html`.

Never use screenshots containing phone notifications, private conversations, credentials, or unlicensed third-party images.

## Data Safety and permissions draft

This is a draft for Bernado's review, not a legal certification.

Observed manifest/runtime scope:

- Camera: required for live preview and capture.
- Photo-library access: requested only for Gallery recovery/import flows.
- No `INTERNET` permission in the manifest.
- No account, analytics, advertising, remote image upload, or cloud-processing path found in the inspected source.

Likely Play declarations, subject to the current Play Console form and final code review:

- No user data collected by the developer.
- No user data shared with third parties.
- Camera and selected/local photos are processed on-device.
- Photos captured by the app are saved to the user's device when the user chooses Capture.
- User may share files using Android's own sharing controls; that is user-directed platform behavior, not an app upload service.

Review this declaration against every future dependency and release before submission.

## Testing path

1. Upload the AAB to an internal test track.
2. Install the Play-delivered build on the Motorola and compare it with the signed release smoke test.
3. Test fresh install, update path, camera permission, capture, Gallery/Export, import, rotation/portrait lock, and offline operation.
4. If the developer account is a new personal account created after the applicable date, create a closed test with at least 12 opted-in testers continuously for 14 days, then apply for production access.
5. Record tester feedback and fix blocking issues before production submission.

## Pricing

- Product model: paid app, one-time purchase.
- Base price: **US$14.99**.
- Review Play's localized prices, taxes, and applicable service fee in Play Console before publishing.
- Do not add subscriptions or in-app purchases unless the product later gains continuing service value.

## GitHub distribution policy

Recommended policy: keep the GitHub repository public for source, documentation, issues, release notes, and provenance, but stop offering a free sideloadable APK once the Play production listing is live.

Do not remove the Git repository or erase release history. Before the Play listing is publicly available:

- keep `v1.21.5` available as the verified transition artifact;
- add a clear notice that Play will become the canonical customer distribution channel;
- do not publish future free APKs from GitHub once Play distribution is live.

After Play production launch and successful Play-delivered verification:

- remove or replace direct APK download assets from the public GitHub release page only if preserving the release manifest/checksum record is possible;
- retain source and documentation;
- redirect users to the Play listing;
- keep a private, auditable copy of release artifacts and checksums outside the public repository if needed for operations;
- do not distribute a separately signed APK that could confuse Play update compatibility.

## User-owned blockers

The agent can prepare code, artifacts, copy, policy drafts, and evidence. Bernado must complete or approve:

- Play developer account ownership and verification;
- payments, tax, and legal declarations;
- privacy-policy approval;
- final trademark/name clearance;
- store listing submission;
- tester recruitment and closed-test participation;
- final production rollout decision.

## Current next action

Create the Play app, upload the AAB to internal testing, invite testers, and keep the GitHub APK available only during this transition. Do not remove the GitHub APK before a Play-delivered build is available and verified.

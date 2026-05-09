## 2026-05-09 - GitHub Actions iOS Speed Patch

- [x] Remove unnecessary iOS simulator platform download while keeping iPhoneOS SDK validation.
- [x] Stabilize Kotlin Native cache key so version-only changes do not force a new Konan cache key.
- [x] Keep Android packaging behavior unchanged and make iOS IPA presence/structure fail fast.
- [x] Validate workflow syntax and inspect the final diff.
- [x] Record verification output and residual risk.

Review:
- Change: Kept Android packaging unchanged, removed the iOS simulator platform download, validated the existing iPhoneOS SDK path instead, and added a fast IPA zip/Payload structure check before artifact upload.
- Change: Removed `gradle.properties` from the Kotlin Native cache key so version-only bumps do not create a new Konan cache key.
- Security: Changed default workflow token permission to `contents: read` and scoped `contents: write` to the GitHub Release job.
- Verification: `ruby -e 'require "yaml"; YAML.load_file(".github/workflows/build-packages.yml")'` passed; inline bash steps passed `bash -n`; `bash -n scripts/ios/build-packages.sh` passed; `git diff --check` passed; local `xcrun --sdk iphoneos --show-sdk-version` and `--show-sdk-path` returned an iPhoneOS SDK.
- Residual risk: The actual speed improvement must be measured by a fresh GitHub Actions run after these workflow changes are pushed.

## 2026-05-09 - GitHub Actions iOS IPA Speed Review

- [x] Map the iOS unsigned IPA workflow path and define concrete success criteria.
- [x] Inspect build scripts, cache boundaries, artifact packaging, and release steps for avoidable latency.
- [x] Review workflow security posture for token/secret exposure and unsafe triggers.
- [x] Validate findings against local files and, if available, recent GitHub Actions run data.
- [x] Record review outcome with root cause, recommended changes, verification, and residual risk.

Review:
- Root cause: The iOS job spends most time in mandatory iOS platform verification/download plus the Xcode-triggered Kotlin Native framework build. Recent successful runs show the iOS job taking roughly 9-10 minutes, while Android finishes around 1-2 minutes.
- Evidence: Latest run 25593740231 had `Verify Xcode and iOS SDK` from 06:10:07 to 06:11:53, `Build iOS unsigned artifacts` from 06:11:53 to 06:17:32, and the script reported `iOS Release iosArm64` took 338 seconds.
- Findings: `.github/workflows/build-packages.yml` runs `xcodebuild -downloadPlatform iOS`, which downloaded an 8.52 GB iOS simulator runtime even though the job builds `iphoneos`; the Xcode shell phase always runs `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`; `~/.konan` cache key includes `gradle.properties`, so version bumps create new 345 MB cache saves.
- Security: Workflow grants `contents: write` globally and runs signed Android packaging on `pull_request`; release permissions should be scoped to the release job, and signing jobs should not run for PR code.
- Verification: Inspected local workflow/scripts and GitHub run/artifact/release data with `gh run list`, `gh run view`, and `gh api`.
- Residual risk: I did not edit workflow behavior in this pass because this was a review; speedups should be implemented in a follow-up patch and rechecked with a fresh Actions run.

- [x] Inspect GitHub Actions workflow and packaging scripts -> Verify: identify artifact path and package command used by CI.
- [x] Compare Android package configuration with produced artifacts -> Verify: explain why local Android is about 15 MB.
- [x] Trace likely 60 MB CI artifact source -> Verify: map size inflation to concrete files/settings, not guesswork.

## Review

Root cause found in workflow/script configuration:
- Android uploads the whole `scripts/artifacts/android` directory, while that directory contains tracked historical APKs. The Android script only deletes `piko-android-*.apk`, so non-matching APKs such as `android-release.apk` can remain in the artifact upload.
- iOS uploads the whole `scripts/artifacts/ios` directory. The iOS script places the Xcode `.app` output there, copies the same `.app` again into a temporary `Payload`, and then creates the final `.ipa`. The uploaded Actions artifact can therefore contain duplicate app payloads instead of only the installable IPA.

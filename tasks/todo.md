## 2026-05-10 - Dual Mobile Build Verification

Assumptions:
- Use the repository build script as the primary verification path.
- Android release APK should be signed by the project signing setup.
- iOS artifact should remain an unsigned IPA.

- [x] Reproduce the dual-platform script result.
- [x] Fix the script if it rejects the current valid local build environment.
- [x] Re-run the script and verify both artifacts.

Review:
- Root cause: `scripts/ios/build-packages.sh` rejected every iPhoneOS SDK except 26.5, while this machine has iPhoneOS SDK 26.4 and the iOS project builds successfully with it.
- Change: Relaxed the local iOS package script and GitHub Actions iOS SDK preflight to require an available iPhoneOS SDK instead of an exact 26.5 SDK.
- Verification: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home scripts/build-mobile.sh all` passed.
- Verification: Android script output verified the release APK with APK Signature Scheme v2 and one signer.
- Verification: `unzip -l scripts/artifacts/ios/piko-1.0.0-beta.9-ios-unsigned.ipa` shows a valid `Payload/Piko.app` structure.
- Verification: `codesign -dv build/tmp/piko-ios-packaging/payload-iosArm64-release/Payload/Piko.app` reported `code object is not signed at all`, confirming the IPA is unsigned.
- Verification: `ruby -e 'require "yaml"; YAML.load_file(".github/workflows/build-packages.yml")'` passed after updating the workflow SDK preflight.
- Artifact: `scripts/artifacts/android/release/piko-1.0.0-beta.9-android-arm64-v8a.apk`.
- Artifact: `scripts/artifacts/ios/piko-1.0.0-beta.9-ios-unsigned.ipa`.

## 2026-05-10 - LocalSend Compatibility Merge Plan

Assumptions:
- Keep the remote `origin/main` Android UI split and root-level native iOS SwiftUI refactor as the base.
- Make Piko compatible with LocalSend's LAN HTTP upload protocol without copying the old iOS monolith back into the app.
- Preserve legacy Piko transfer as fallback until both mobile clients are verified against LocalSend-compatible peers.

- [x] Confirm the merge strategy before implementation.
- [x] Keep Android LocalSend protocol modules and only tighten integration against the new remote UI files.
- [x] Port iOS LocalSend code from the stashed old Swift implementation into focused root-level Swift files.
- [x] Add/keep LAN HTTP upload endpoints: register/info, prepare-upload, upload, cancel.
- [x] Add UDP multicast discovery/respond path for real LocalSend discovery compatibility, while keeping current `_piko-share._tcp` discovery as Piko fallback.
- [x] Verify Android tests/build, iOS Swift compile/package gate, and record remaining manual/device validation.

Review:
- Change: Added LocalSend multicast announcement encode/decode on Android and covered it with unit tests before implementation.
- Change: Android now keeps Bonjour fallback but also listens/sends LocalSend UDP multicast announcements on `224.0.0.167:53317`, responds to `announce=true`, and inserts discovered LocalSend peers as LAN targets.
- Change: iOS LocalSend protocol, HTTP message parsing, fixed-port listener factory, and UDP multicast discovery were split into focused root-level Swift files and wired into `NativePikoModel`.
- Change: iOS send now attempts LocalSend `prepare-upload`/`upload` first and falls back to legacy Piko transfer; receive now routes LocalSend HTTP requests and legacy Piko streams through the same listener.
- Verification: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home sh ./gradlew :android:testDebugUnitTest --tests com.piko.app.LocalSendProtocolTest` failed before the Android announcement API existed and passed after implementation.
- Verification: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home sh ./gradlew :android:testDebugUnitTest` passed.
- Verification: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home sh ./gradlew :android:assembleDebug` passed.
- Verification: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home scripts/build-mobile.sh android` passed and verified the signed release APK.
- Verification: `xcrun swiftc ... ios/*.swift` passed as an iOS Swift syntax/type check against the locally available SDK.
- Verification: `xcodebuild build -project ios/Piko.xcodeproj -scheme Piko -configuration Debug -sdk iphoneos -destination 'generic/platform=iOS' -derivedDataPath build/DerivedData CODE_SIGNING_ALLOWED=NO` passed.
- Verification: `bash -n scripts/ios/build-packages.sh scripts/build-mobile.sh` and `git diff --check` passed.
- Residual risk: Full iOS IPA packaging still stops at the remote SDK gate because this machine has iPhoneOS SDK 26.4 while the script requires 26.5.
- Residual risk: Real LocalSend interoperability still needs a device/manual test with the official LocalSend app because multicast and local-network permission behavior cannot be proven by compile-only checks.

## 2026-05-10 - Merge Remote Main

Assumptions:
- Preserve current local feature work while bringing `main` up to `origin/main`.
- Resolve conflicts against the remote Android UI split and native iOS refactor.
- Do not commit or push unless explicitly requested.

- [x] Fetch remote state and compare local `main` with `origin/main`.
- [x] Fast-forward local `main` to the latest remote commit.
- [x] Reapply local feature changes and resolve merge conflicts.
- [x] Run Android and script verification, then record any remaining iOS build blocker.

Review:
- Change: Fetched `origin/main`, confirmed local `main` was behind remote, fast-forwarded to `3a7eacd` / `v1.0.0-beta.9`, and reapplied the local LocalSend/media-save/build-script work.
- Change: Resolved Android conflicts against the remote UI split and resolved iOS by moving the local save-location changes onto the new root SwiftUI files instead of the deleted `ios/PikoIOS` app.
- Change: Fixed the iOS package script's unbraced `${sdk_version}` expansion so macOS Bash 3.2 no longer treats the following Chinese punctuation as part of the variable name.
- Verification: `git diff --check` passed.
- Verification: `bash -n scripts/ios/build-packages.sh scripts/build-mobile.sh` passed.
- Verification: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home sh ./gradlew :android:testDebugUnitTest` passed.
- Verification: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home sh ./gradlew :android:assembleDebug` passed.
- Verification: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home scripts/build-mobile.sh android` passed and produced `scripts/artifacts/android/release/piko-1.0.0-beta.9-android-arm64-v8a.apk`.
- Verification: A direct `xcrun swiftc` compile of the root `ios/*.swift` sources passed against the locally available SDK as a syntax/type check.
- Residual risk: `scripts/build-mobile.sh ios` now reaches the intended SDK gate but stops because this machine has iPhoneOS SDK 26.4 while the remote script requires 26.5.

## 2026-05-10 - Mobile Build Script and Documentation

Assumptions:
- Add a macOS/Linux shell entrypoint for local mobile builds.
- Reuse existing project config and platform packaging paths.
- Keep Android signing secrets out of tracked files and documentation.

- [x] Add a reusable dual-platform build script.
- [x] Document Android signing, iOS unsigned IPA, commands, outputs, and troubleshooting.
- [x] Run the new script to build both mobile platforms and verify artifacts.

Review:
- Change: Added `scripts/build-mobile.sh` as the macOS/Linux local entrypoint for `all`, `android`, and `ios` mobile builds.
- Change: The script reads `.github/build-config.json`, auto-detects OpenJDK 21 when `JAVA_HOME` is unset, builds Android through `sh ./gradlew`, verifies release APKs with `apksigner`, delegates iOS packaging to `scripts/ios/build-packages.sh`, and rejects signed IPA output.
- Change: Added `MOBILE_BUILD.md` with the full local build flow, signing setup, artifact paths, verification behavior, and troubleshooting.
- Verification: `bash -n scripts/build-mobile.sh` passed.
- Verification: `scripts/build-mobile.sh --help` printed usage.
- Verification: `scripts/build-mobile.sh` built Android and iOS successfully.
- Artifact: `scripts/artifacts/android/release/piko-1.0.0-beta.5-android-arm64-v8a.apk`.
- Artifact: `scripts/artifacts/ios/piko-1.0.0-beta.5-ios-unsigned.ipa`.
- Residual risk: Android release signing still depends on local ignored signing material or environment variables; no signing secrets were written to tracked files.

## 2026-05-10 - Media Save Location Setting

Assumptions:
- Add a setting for received image/video files only: album or folder.
- Keep non-media files in the folder path.
- Use a fixed app folder rather than arbitrary user-selected directories in this pass.

- [x] Add Android preference model and tests for media destination decisions.
- [x] Add Android settings UI and route receive writes through the selected destination.
- [x] Add iOS settings UI, UserDefaults preference, and Photos/folder save branch.
- [x] Run targeted tests and mobile build verification.

Review:
- Change: Android settings now persists `图片视频保存位置` with `文件夹` as the default and `相册` as the alternate option.
- Change: Android receive writes image files to `Pictures/Piko`, video files to `Movies/Piko`, and all folder-mode or non-media files to `Downloads/Piko`.
- Change: iOS settings now persists the same choice with `UserDefaults`, adds the Photos add-only usage string, and saves received image/video files through Photos when `相册` is selected.
- Change: iOS legacy receive parsing now keeps the received file type so old Piko transfers can follow the same media destination rule.
- Verification: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home sh ./gradlew :android:testDebugUnitTest --tests com.piko.app.ReceiveMediaSaveLocationTest` failed before implementation and passed after adding the preference model.
- Verification: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home sh ./gradlew :android:testDebugUnitTest :android:assembleDebug` passed.
- Verification: `xcodebuild -project ios/PikoIOS/PikoIOS.xcodeproj -scheme PikoIOS -configuration Debug -sdk iphoneos -destination 'generic/platform=iOS' build CODE_SIGNING_ALLOWED=NO` passed.
- Artifact: Refreshed `scripts/artifacts/android/release/piko-1.0.0-beta.5-android-arm64-v8a.apk` and verified it with `apksigner`.
- Artifact: Refreshed `scripts/artifacts/ios/piko-1.0.0-beta.5-ios-unsigned.ipa` and verified `codesign -dv` reports it is unsigned.
- Residual risk: I verified compile-time behavior locally; actual Photos permission prompts and gallery appearance still need a device/manual receive flow.

## 2026-05-10 - Mobile Signed APK and Unsigned IPA Build

- [x] Confirm project build config, Android signing inputs, and output naming.
- [x] Produce Android release APK with project signing configuration.
- [x] Produce iOS release unsigned IPA.
- [x] Verify APK signature and IPA Payload structure.

Review:
- Change: Built Android release APK from the project release config with arm64-v8a output naming.
- Change: No existing Android signing file or signing environment variables were present, so a local ignored release keystore and `android/signing/release-signing.properties` were generated for this build.
- Artifact: `scripts/artifacts/android/release/piko-1.0.0-beta.5-android-arm64-v8a.apk`.
- Artifact: `scripts/artifacts/ios/piko-1.0.0-beta.5-ios-unsigned.ipa`.
- Verification: `apksigner verify --verbose --print-certs` passed for the Android APK using APK Signature Scheme v2.
- Verification: The IPA contains `Payload/PikoIOS.app`, and `codesign -dv` reports `code object is not signed at all`.
- Residual risk: The Android APK is signed with a newly generated local release key, not an official production distribution key, because none was available in the local project or environment.

## 2026-05-10 - LocalSend-Grade Transfer Core

Assumptions:
- Implement LocalSend-style transfer capability inside Piko instead of copying LocalSend source files verbatim.
- Keep the current Piko Bonjour/NSD LAN UX working while replacing the transfer core behind it.
- Reserve transport interfaces for future cross-network P2P without implementing NAT traversal in this pass.

Success criteria:
- Sender sends metadata first, receiver can accept/reject and returns a session plus per-file tokens.
- LAN transfer streams file bytes instead of buffering whole transfers in memory.
- Protocol metadata supports file id, name, MIME/type, size, sha256, preview, and relative path.
- Transport boundaries can later plug in LAN direct, relay, or P2P direct channels.
- Android unit tests cover protocol/session behavior and existing send-page state still passes.

- [x] Confirm implementation plan before touching transfer code.
- [x] Split transfer protocol models from platform socket code.
- [x] Add LocalSend-style prepare/upload/cancel session semantics.
- [x] Add transport abstraction for LAN direct now and future P2P/relay later.
- [x] Replace Android raw header send/receive with streaming session upload.
- [x] Replace iOS full-buffer decode with metadata-first streaming receive.
- [x] Add sha256 verification and receiver-side file finalization rules.
- [x] Run targeted protocol/state tests and platform builds where feasible.

Review:
- Change: Added Android LocalSend-compatible protocol models, prepare-upload response parsing, session/token store, route parser, HTTP receive server, and HTTP upload client.
- Change: Android receiver now opens a LocalSend-style HTTP endpoint on port 53317 when possible, keeps the existing Piko Bonjour service, and still accepts the legacy `PIKO` binary stream as a fallback.
- Change: Android sender now tries LocalSend HTTP `prepare-upload`/`upload` first and falls back to the legacy Piko socket transfer for older peers.
- Change: Added `TransferTransportKind`/`TransferTransport` with `lan-direct` implemented and `p2p-direct`/`relay` reserved for future cross-network transfer.
- Change: Receiver writes downloads through pending MediaStore entries, verifies `sha256` when provided, and deletes incomplete or failed files before publishing.
- Change: iOS now prefers port 53317, handles LocalSend-compatible HTTP `info`/`register`/`prepare-upload`/`upload`/`cancel`, streams upload bodies directly to `.part` files, verifies `sha256` when present, and keeps legacy Piko binary transfer fallback.
- Change: iOS sender now tries LocalSend HTTP over `NWConnection` first and falls back to the legacy binary transfer for older peers.
- Verification: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home sh ./gradlew :android:testDebugUnitTest` passed.
- Verification: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home sh ./gradlew :android:assembleDebug` passed.
- Verification: `xcodebuild -project ios/PikoIOS/PikoIOS.xcodeproj -scheme PikoIOS -configuration Debug -sdk iphoneos -destination 'generic/platform=iOS' build CODE_SIGNING_ALLOWED=NO` passed.
- Residual risk: No iOS simulator runtime is installed locally, so I verified iOS with a generic iPhoneOS build rather than simulator launch.

## 2026-05-09 - Local Unsigned IPA Build

- [ ] Confirm local iOS packaging path and success criteria.
- [ ] Run the repository iOS package script to produce an unsigned IPA.
- [ ] Verify the IPA exists, has Payload structure, and remains unsigned.

Review:
- Pending.

## 2026-05-09 - GitHub Actions iOS Speed Patch

- [x] Remove unnecessary iOS simulator platform download while keeping iPhoneOS SDK validation.
- [x] Keep iOS package cache keys stable so version-only changes do not force unnecessary cache churn.
- [x] Keep Android packaging behavior unchanged and make iOS IPA presence/structure fail fast.
- [x] Validate workflow syntax and inspect the final diff.
- [x] Record verification output and residual risk.
- [x] RCA the first optimized CI run failure and adjust Xcode selection.
- [x] Upload iOS package timing data for CI performance review.

Review:
- Change: Kept Android packaging unchanged, removed the iOS simulator platform download, validated the existing iPhoneOS SDK path instead, and added a fast IPA zip/Payload structure check before artifact upload.
- Change: Removed version-only inputs from the iOS package cache key so version bumps do not create unnecessary cache churn.
- Security: Changed default workflow token permission to `contents: read` and scoped `contents: write` to the GitHub Release job.
- Verification: `ruby -e 'require "yaml"; YAML.load_file(".github/workflows/build-packages.yml")'` passed; inline bash steps passed `bash -n`; `bash -n scripts/ios/build-packages.sh` passed; `git diff --check` passed; local `xcrun --sdk iphoneos --show-sdk-version` and `--show-sdk-path` returned an iPhoneOS SDK.
- Residual risk: The actual speed improvement must be measured by a fresh GitHub Actions run after these workflow changes are pushed.
- Follow-up: Run 25594601083 proved the beta Xcode can expose an iPhoneOS SDK while still lacking an installed iOS platform for `generic/platform=iOS`; the workflow now probes Xcode candidates and selects one whose destination is usable before falling back to `xcodebuild -downloadPlatform iOS`.
- Follow-up: Run 25594722573 narrowed the remaining cost to the Xcode package step; CI now uploads package timing data so the iOS build split is measurable.

## 2026-05-09 - GitHub Actions iOS IPA Speed Review

- [x] Map the iOS unsigned IPA workflow path and define concrete success criteria.
- [x] Inspect build scripts, cache boundaries, artifact packaging, and release steps for avoidable latency.
- [x] Review workflow security posture for token/secret exposure and unsafe triggers.
- [x] Validate findings against local files and, if available, recent GitHub Actions run data.
- [x] Record review outcome with root cause, recommended changes, verification, and residual risk.

Review:
- Root cause: The iOS job spends most time in mandatory iOS platform verification/download plus the Xcode package build. Recent successful runs show the iOS job taking roughly 9-10 minutes, while Android finishes around 1-2 minutes.
- Evidence: Latest run 25593740231 had `Verify Xcode and iOS SDK` from 06:10:07 to 06:11:53, `Build iOS unsigned artifacts` from 06:11:53 to 06:17:32, and the script reported `iOS Release iosArm64` took 338 seconds.
- Findings: `.github/workflows/build-packages.yml` runs `xcodebuild -downloadPlatform iOS`, which downloaded an 8.52 GB iOS simulator runtime even though the job builds `iphoneos`; the iOS package cache included version-only inputs, so version bumps created unnecessary cache saves.
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

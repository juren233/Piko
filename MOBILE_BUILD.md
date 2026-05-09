# Piko 移动端本地构建流程

本文档说明如何在本机一键构建 Android signed APK 和 iOS unsigned IPA。

## 一键构建

在仓库根目录运行：

```bash
scripts/build-mobile.sh
```

只构建某一端：

```bash
scripts/build-mobile.sh android
scripts/build-mobile.sh ios
```

脚本读取 `.github/build-config.json` 决定构建哪些平台、构建类型和架构。当前默认配置是：

- Android: release, `arm64-v8a`
- iOS: release, `iosArm64`

## 产物位置

Android signed APK:

```text
scripts/artifacts/android/release/piko-<version>-android-arm64-v8a.apk
```

iOS unsigned IPA:

```text
scripts/artifacts/ios/piko-<version>-ios-unsigned.ipa
```

版本号来自 `gradle.properties`：

```properties
piko.versionName=...
piko.versionCode=...
```

## 环境要求

Android:

- OpenJDK 21
- Android SDK Build Tools，需包含 `apksigner`
- Gradle Wrapper 使用仓库内的 `./gradlew`

iOS:

- macOS
- Xcode
- 可用的 iPhoneOS SDK
- `python3`, `zip`, `unzip`, `codesign`

脚本会优先使用已有 `JAVA_HOME`。如果未配置，会自动尝试常见的 Homebrew OpenJDK 21 路径：

```text
/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

## Android 签名配置

Release APK 必须签名。脚本不会生成密钥，也不会输出密码。

推荐在本机创建：

```text
android/signing/release-signing.properties
```

格式参考：

```properties
storeFile=keystores/piko-release.p12
storePassword=replace-with-store-password
keyAlias=piko-release
keyPassword=replace-with-key-password
```

`storeFile` 按 Android module 目录解析，所以示例路径实际对应：

```text
android/keystores/piko-release.p12
```

这些路径已被 `.gitignore` 忽略，不要提交真实签名材料。

也可以用环境变量：

```bash
export ANDROID_KEYSTORE_PATH=/absolute/path/to/piko-release.p12
export ANDROID_KEYSTORE_PASSWORD=...
export ANDROID_KEY_ALIAS=piko-release
export ANDROID_KEY_PASSWORD=...
```

构建后脚本会运行：

```bash
apksigner verify --verbose <apk>
```

如果 APK 未签名或签名无效，脚本会失败。

## iOS 未签名 IPA

iOS 构建复用：

```bash
scripts/ios/build-packages.sh
```

该脚本使用 iPhoneOS SDK 构建 release app，并关闭代码签名：

```text
CODE_SIGNING_ALLOWED=NO
CODE_SIGNING_REQUIRED=NO
CODE_SIGN_IDENTITY=
```

生成的 IPA 是未签名产物，不能直接给普通用户安装，需要后续重签名或走对应分发链路。

`scripts/build-mobile.sh` 会解压 IPA，检查：

- 存在 `Payload/*.app`
- `codesign -dv Payload/*.app` 返回未签名

如果 IPA 被签名，脚本会失败，因为当前本地交付目标是 unsigned IPA。

## 常见问题

### JAVA_HOME 未配置

如果脚本没有找到 OpenJDK 21，手动设置：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```

### 找不到 apksigner

确认 Android SDK Build Tools 已安装，并设置：

```bash
export ANDROID_HOME=/path/to/android/sdk
```

脚本会在 `$ANDROID_HOME`, `$ANDROID_SDK_ROOT` 和 Homebrew 常见路径下搜索 `build-tools/*/apksigner`。

### Android release 签名失败

检查：

- `android/signing/release-signing.properties` 是否存在
- `storeFile` 是否能解析到真实 keystore
- `storePassword`, `keyAlias`, `keyPassword` 是否正确

### iOS 构建失败

先确认 Xcode 的 iPhoneOS SDK 可用：

```bash
xcrun --sdk iphoneos --show-sdk-version
scripts/build-mobile.sh ios
```

## 推荐发布前检查

本地发版前建议按顺序运行：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home sh ./gradlew :android:testDebugUnitTest
scripts/build-mobile.sh
git diff --check
```

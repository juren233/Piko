#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CONFIG_PATH="$REPO_ROOT/.github/build-config.json"
VERSION_PATH="$REPO_ROOT/gradle.properties"
ARTIFACT_ROOT="$REPO_ROOT/scripts/artifacts/ios"
IOS_BUILD_ROOT="${RUNNER_TEMP:-$REPO_ROOT/build/tmp}/piko-ios-packaging"
XCODE_PROJECT="$REPO_ROOT/ios/Piko.xcodeproj"
XCODE_WORKSPACE="$REPO_ROOT/ios/Piko.xcworkspace"
SCHEME="Piko"
BUNDLE_IDENTIFIER="com.juren233.piko"
DEPLOYMENT_TARGET="16.0"

if [[ ! -f "$CONFIG_PATH" ]]; then
  echo "未找到构建配置：$CONFIG_PATH" >&2
  exit 1
fi
if [[ ! -f "$VERSION_PATH" ]]; then
  echo "未找到版本配置：$VERSION_PATH" >&2
  exit 1
fi

read_config() {
  python3 - "$CONFIG_PATH" "$1" <<'PY'
import json
import sys

path, query = sys.argv[1], sys.argv[2].split(".")
value = json.load(open(path, encoding="utf-8"))
for key in query:
    value = value[key]
if isinstance(value, bool):
    print(str(value).lower())
else:
    print(value)
PY
}

read_enabled_keys() {
  python3 - "$CONFIG_PATH" "$1" <<'PY'
import json
import sys

path, query = sys.argv[1], sys.argv[2].split(".")
value = json.load(open(path, encoding="utf-8"))
for key in query:
    value = value[key]
print(" ".join(key for key, enabled in value.items() if enabled is True))
PY
}

read_property() {
  local key="$1"
  awk -F= -v key="$key" '
    $0 !~ /^[[:space:]]*[#!]/ && $1 == key {
      value = substr($0, index($0, "=") + 1)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      print value
      exit
    }
  ' "$VERSION_PATH"
}

if [[ "$(read_config build.enabled)" != "true" || "$(read_config ios.enabled)" != "true" ]]; then
  echo "iOS 构建已在配置中关闭。"
  exit 0
fi

variants="$(read_enabled_keys ios.variants)"
targets="$(read_enabled_keys ios.architectures)"
if [[ -z "$variants" ]]; then
  echo "iOS 至少需要开启 debug 或 release 中的一个构建类型。" >&2
  exit 1
fi
if [[ -z "$targets" ]]; then
  echo "iOS 至少需要开启一个预设架构。" >&2
  exit 1
fi

version_name="$(read_property piko.versionName)"
version_code="$(read_property piko.versionCode)"
if [[ -z "$version_name" ]]; then
  echo "gradle.properties 缺少 piko.versionName。" >&2
  exit 1
fi
if [[ ! "$version_code" =~ ^[0-9]+$ ]]; then
  echo "piko.versionCode 必须是整数，当前值：$version_code" >&2
  exit 1
fi

rm -rf "$ARTIFACT_ROOT"
rm -rf "$IOS_BUILD_ROOT"
mkdir -p "$ARTIFACT_ROOT"

cd "$REPO_ROOT"

sdk_version="$(xcrun --sdk iphoneos --show-sdk-version)"
if [[ -z "$sdk_version" ]]; then
  echo "未找到可用的 iPhoneOS SDK。" >&2
  exit 1
fi

echo "::group::iOS 构建配置"
echo "variants=$variants"
echo "targets=$targets"
echo "version_name=$version_name"
echo "version_code=$version_code"
echo "ios_sdk_version=$sdk_version"
echo "ios_compile_target=arm64-apple-ios${DEPLOYMENT_TARGET}"
echo "xcode_workspace=$XCODE_WORKSPACE"
echo "xcode_project=$XCODE_PROJECT"
echo "ios_build_root=$IOS_BUILD_ROOT"
echo "::endgroup::"

if [[ -d "$REPO_ROOT/ios" ]]; then
  swift_sources=()
  while IFS= read -r source; do
    swift_sources+=("$source")
  done < <(find "$REPO_ROOT/ios" -maxdepth 1 -type f -name "*.swift" | sort)

  if [[ "${#swift_sources[@]}" -eq 0 ]]; then
    echo "未找到 iOS Swift 源文件，无法生成 iPhone IPA。" >&2
    exit 1
  fi

  sdk_path="$(xcrun --sdk iphoneos --show-sdk-path)"
  if [[ -z "$sdk_path" || ! -d "$sdk_path" ]]; then
    echo "未找到 iPhoneOS SDK 路径：$sdk_path" >&2
    exit 1
  fi

  for target in $targets; do
    if [[ "$target" != "iosArm64" ]]; then
      echo "当前 iOS 发布只支持 iPhone 真机架构：iosArm64。" >&2
      exit 1
    fi

    for variant in $variants; do
      started_at="$(date +%s)"
      configuration="$(tr '[:lower:]' '[:upper:]' <<< "${variant:0:1}")${variant:1}"
      output_dir="$IOS_BUILD_ROOT/products/$target/$variant"
      temp_dir="$IOS_BUILD_ROOT/intermediates/$target/$variant"
      app="$output_dir/Piko.app"
      executable="$app/Piko"
      plist_path="$app/Info.plist"
      rm -rf "$output_dir" "$temp_dir"
      mkdir -p "$app" "$temp_dir"

      compile_flags=("-O")
      if [[ "$variant" != "release" ]]; then
        compile_flags=("-Onone" "-g" "-D" "DEBUG")
      fi

      echo "::group::swiftc $configuration $target"
      xcrun swiftc \
        -target "arm64-apple-ios${DEPLOYMENT_TARGET}" \
        -sdk "$sdk_path" \
        -parse-as-library \
        -module-name "$SCHEME" \
        "${compile_flags[@]}" \
        "${swift_sources[@]}" \
        -o "$executable"

      python3 - "$REPO_ROOT/ios/Info.plist" "$plist_path" "$BUNDLE_IDENTIFIER" "$version_name" "$version_code" "$DEPLOYMENT_TARGET" <<'PY'
import plistlib
import sys

source_path, output_path, bundle_id, version_name, version_code, deployment_target = sys.argv[1:7]
with open(source_path, "rb") as file:
    plist = plistlib.load(file)

plist.update({
    "CFBundleDevelopmentRegion": "zh_CN",
    "CFBundleDisplayName": "Piko",
    "CFBundleExecutable": "Piko",
    "CFBundleIdentifier": bundle_id,
    "CFBundleInfoDictionaryVersion": "6.0",
    "CFBundleName": "Piko",
    "CFBundlePackageType": "APPL",
    "CFBundleShortVersionString": version_name,
    "CFBundleVersion": version_code,
    "LSApplicationCategoryType": "public.app-category.productivity",
    "MinimumOSVersion": deployment_target,
    "UIApplicationSceneManifest": {
        "UIApplicationSupportsMultipleScenes": False,
    },
    "UIApplicationSupportsIndirectInputEvents": True,
    "UILaunchScreen": {},
    "UIDeviceFamily": [1],
    "UISupportedInterfaceOrientations": [
        "UIInterfaceOrientationPortrait",
        "UIInterfaceOrientationLandscapeLeft",
        "UIInterfaceOrientationLandscapeRight",
    ],
})

if plist.get("CADisableMinimumFrameDurationOnPhone") is not True:
    print(f"{source_path} 缺少 CADisableMinimumFrameDurationOnPhone=true，iOS 真机高刷新显示配置不完整。", file=sys.stderr)
    sys.exit(1)

with open(output_path, "wb") as file:
    plistlib.dump(plist, file, sort_keys=True)
PY
      echo "APPL" > "$app/PkgInfo"
      chmod +x "$executable"
      echo "::endgroup::"

      suffix=""
      if [[ "$variant" != "release" ]]; then
        suffix="-$variant"
      fi
      ipa_name="$ARTIFACT_ROOT/piko-${version_name}-ios-unsigned${suffix}.ipa"
      payload_dir="$IOS_BUILD_ROOT/payload-${target}-${variant}/Payload"
      rm -rf "$(dirname "$payload_dir")"
      mkdir -p "$payload_dir"
      cp -R "$app" "$payload_dir/"
      (cd "$(dirname "$payload_dir")" && zip -qry "$ipa_name" "Payload")
      echo "::warning::已生成未签名 iPhone IPA：${ipa_name}。未签名 IPA 需要后续重签名或通过对应分发链路处理，不能直接给普通用户安装。"
      finished_at="$(date +%s)"
      echo "iOS $configuration $target 构建耗时 $((finished_at - started_at)) 秒。"
    done
  done
else
  echo "::warning::未找到 ios 目录，已跳过 iOS IPA 构建。当前仓库没有可打包 iPhone IPA 的 Swift 源码。"
  exit 0
fi

find "$ARTIFACT_ROOT" -maxdepth 1 -type f -name "*.ipa"

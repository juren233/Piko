#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CONFIG_PATH="$REPO_ROOT/.github/build-config.json"
VERSION_PATH="$REPO_ROOT/gradle.properties"
ARTIFACT_ROOT="$REPO_ROOT/scripts/artifacts/ios"
IOS_BUILD_ROOT="${RUNNER_TEMP:-$REPO_ROOT/build/tmp}/piko-ios-packaging"
XCODE_PROJECT="$REPO_ROOT/ios/PikoIOS/PikoIOS.xcodeproj"
XCODE_WORKSPACE="$REPO_ROOT/ios/PikoIOS/PikoIOS.xcworkspace"
SCHEME="PikoIOS"

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

echo "::group::iOS 构建配置"
echo "variants=$variants"
echo "targets=$targets"
echo "version_name=$version_name"
echo "version_code=$version_code"
echo "xcode_workspace=$XCODE_WORKSPACE"
echo "xcode_project=$XCODE_PROJECT"
echo "ios_build_root=$IOS_BUILD_ROOT"
echo "::endgroup::"

if [[ -d "$XCODE_WORKSPACE" || -d "$XCODE_PROJECT" ]]; then
  for target in $targets; do
    if [[ "$target" != "iosArm64" ]]; then
      echo "当前 iOS 发布只支持 iPhone 真机架构：iosArm64。" >&2
      exit 1
    fi
    sdk="iphoneos"
    arch="arm64"

    for variant in $variants; do
      started_at="$(date +%s)"
      configuration="$(tr '[:lower:]' '[:upper:]' <<< "${variant:0:1}")${variant:1}"
      output_dir="$IOS_BUILD_ROOT/products/$target/$variant"
      temp_dir="$IOS_BUILD_ROOT/intermediates/$target/$variant"
      mkdir -p "$output_dir"
      echo "::group::xcodebuild $configuration $target"
      if [[ -d "$XCODE_WORKSPACE" ]]; then
        xcodebuild \
          -workspace "$XCODE_WORKSPACE" \
          -scheme "$SCHEME" \
          -configuration "$configuration" \
          -sdk "$sdk" \
          ARCHS="$arch" \
          MARKETING_VERSION="$version_name" \
          CURRENT_PROJECT_VERSION="$version_code" \
          CODE_SIGNING_ALLOWED=NO \
          CODE_SIGNING_REQUIRED=NO \
          CODE_SIGN_IDENTITY="" \
          COMPILER_INDEX_STORE_ENABLE=NO \
          DEBUG_INFORMATION_FORMAT=dwarf \
          GCC_GENERATE_DEBUGGING_SYMBOLS=NO \
          COPY_PHASE_STRIP=YES \
          STRIP_INSTALLED_PRODUCT=YES \
          DEPLOYMENT_POSTPROCESSING=YES \
          VALIDATE_PRODUCT=NO \
          DWARF_DSYM_FILE_SHOULD_ACCOMPANY_PRODUCT=NO \
          OBJROOT="$temp_dir/obj" \
          SYMROOT="$temp_dir/sym" \
          SHARED_PRECOMPS_DIR="$temp_dir/precomp" \
          CONFIGURATION_BUILD_DIR="$output_dir" \
          build
      else
        xcodebuild \
          -project "$XCODE_PROJECT" \
          -scheme "$SCHEME" \
          -configuration "$configuration" \
          -sdk "$sdk" \
          ARCHS="$arch" \
          MARKETING_VERSION="$version_name" \
          CURRENT_PROJECT_VERSION="$version_code" \
          CODE_SIGNING_ALLOWED=NO \
          CODE_SIGNING_REQUIRED=NO \
          CODE_SIGN_IDENTITY="" \
          COMPILER_INDEX_STORE_ENABLE=NO \
          DEBUG_INFORMATION_FORMAT=dwarf \
          GCC_GENERATE_DEBUGGING_SYMBOLS=NO \
          COPY_PHASE_STRIP=YES \
          STRIP_INSTALLED_PRODUCT=YES \
          DEPLOYMENT_POSTPROCESSING=YES \
          VALIDATE_PRODUCT=NO \
          DWARF_DSYM_FILE_SHOULD_ACCOMPANY_PRODUCT=NO \
          OBJROOT="$temp_dir/obj" \
          SYMROOT="$temp_dir/sym" \
          SHARED_PRECOMPS_DIR="$temp_dir/precomp" \
          CONFIGURATION_BUILD_DIR="$output_dir" \
          build
      fi
      echo "::endgroup::"
      find "$output_dir" -maxdepth 1 -name "*.app" -print0 |
        while IFS= read -r -d '' app; do
          plist_path="$app/Info.plist"
          python3 - "$plist_path" <<'PY'
import plistlib
import sys

path = sys.argv[1]
with open(path, "rb") as file:
    plist = plistlib.load(file)

if plist.get("CADisableMinimumFrameDurationOnPhone") is not True:
    print(f"{path} 缺少 CADisableMinimumFrameDurationOnPhone=true，Compose iOS 真机会在启动时崩溃。", file=sys.stderr)
    sys.exit(1)
PY
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
        done
      finished_at="$(date +%s)"
      echo "iOS $configuration $target 构建耗时 $((finished_at - started_at)) 秒。"
    done
  done
else
  echo "::warning::未找到 ios/PikoIOS/PikoIOS.xcodeproj 或 .xcworkspace，已跳过 iOS IPA 构建。当前仓库没有可打包 iPhone IPA 的 Xcode 工程。"
  exit 0
fi

find "$ARTIFACT_ROOT" -maxdepth 1 -type f -name "*.ipa"

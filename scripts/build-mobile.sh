#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CONFIG_PATH="$REPO_ROOT/.github/build-config.json"
VERSION_PATH="$REPO_ROOT/gradle.properties"
GRADLE_WRAPPER_PROPERTIES="$REPO_ROOT/gradle/wrapper/gradle-wrapper.properties"
ANDROID_ARTIFACT_ROOT="$REPO_ROOT/scripts/artifacts/android"
IOS_ARTIFACT_ROOT="$REPO_ROOT/scripts/artifacts/ios"

PLATFORM="${1:-all}"
ANDROID_BUILD_USED=false

usage() {
  cat <<'EOF'
Usage: scripts/build-mobile.sh [all|android|ios]

Builds local mobile artifacts from .github/build-config.json:
  all      Build Android signed APKs and iOS unsigned IPAs.
  android  Build Android signed APKs only.
  ios      Build iOS unsigned IPAs only.
EOF
}

case "$PLATFORM" in
  all|android|ios)
    ;;
  -h|--help|help)
    usage
    exit 0
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac

require_file() {
  local path="$1"
  if [[ ! -f "$path" ]]; then
    echo "未找到文件：$path" >&2
    exit 1
  fi
}

read_config() {
  python3 - "$CONFIG_PATH" "$1" <<'PY'
import json
import sys

path, query = sys.argv[1], sys.argv[2].split(".")
with open(path, encoding="utf-8") as file:
    value = json.load(file)
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
with open(path, encoding="utf-8") as file:
    value = json.load(file)
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

read_version_value() {
  local property_key="$1"
  local environment_key="$2"
  local environment_value="${!environment_key:-}"
  if [[ -n "$environment_value" ]]; then
    printf '%s\n' "$environment_value"
    return 0
  fi

  read_property "$property_key"
}

read_gradle_wrapper_version() {
  local distribution_url
  distribution_url="$(awk -F= '$1 == "distributionUrl" { print substr($0, index($0, "=") + 1); exit }' "$GRADLE_WRAPPER_PROPERTIES")"
  if [[ "$distribution_url" =~ gradle-([0-9.]+)-bin\.zip ]]; then
    echo "${BASH_REMATCH[1]}"
    return 0
  fi

  echo "无法从 $GRADLE_WRAPPER_PROPERTIES 读取 Gradle Wrapper 版本。" >&2
  exit 1
}

run_gradle() {
  require_file "$REPO_ROOT/gradlew"
  require_file "$GRADLE_WRAPPER_PROPERTIES"

  local gradle_version
  gradle_version="$(read_gradle_wrapper_version)"
  echo "使用项目 Gradle Wrapper：Gradle $gradle_version"
  sh "$REPO_ROOT/gradlew" "$@"
}

detect_java_home() {
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    export PATH="$JAVA_HOME/bin:$PATH"
    return 0
  fi

  local candidates=(
    "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
    "/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
    "/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home"
  )
  local candidate
  for candidate in "${candidates[@]}"; do
    if [[ -x "$candidate/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      export PATH="$JAVA_HOME/bin:$PATH"
      return 0
    fi
  done

  echo "JAVA_HOME 未配置，且未找到 OpenJDK 21。请先安装或导出 JAVA_HOME。" >&2
  exit 1
}

cleanup_build_processes() {
  if [[ "$ANDROID_BUILD_USED" == "true" && -f "$REPO_ROOT/gradlew" ]]; then
    echo "清理 Gradle/Java 构建后台进程..."
    run_gradle --stop >/dev/null 2>&1 || true
  fi
}

trap cleanup_build_processes EXIT

find_apksigner() {
  local roots=()
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    roots+=("$ANDROID_HOME")
  fi
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    roots+=("$ANDROID_SDK_ROOT")
  fi
  roots+=("/opt/homebrew/share/android-commandlinetools" "/usr/local/share/android-commandlinetools")

  local root
  for root in "${roots[@]}"; do
    if [[ -d "$root" ]]; then
      find "$root" -path "*/build-tools/*/apksigner" -type f 2>/dev/null | sort | tail -1
    fi
  done | tail -1
}

android_signing_configured() {
  local signing_file="$REPO_ROOT/android/signing/release-signing.properties"
  if [[ -f "$signing_file" ]]; then
    return 0
  fi
  [[ -n "${ANDROID_KEYSTORE_PATH:-}" &&
     -n "${ANDROID_KEYSTORE_PASSWORD:-}" &&
     -n "${ANDROID_KEY_ALIAS:-}" &&
     -n "${ANDROID_KEY_PASSWORD:-}" ]]
}

copy_apk() {
  local source="$1"
  local destination="$2"
  rm -f "$destination"
  cp "$source" "$destination"
}

build_android() {
  if [[ "$(read_config build.enabled)" != "true" || "$(read_config android.enabled)" != "true" ]]; then
    echo "Android 构建已在配置中关闭。"
    return 0
  fi

  detect_java_home
  ANDROID_BUILD_USED=true

  local version_name version_code
  version_name="$(read_version_value piko.versionName PIKO_VERSION_NAME)"
  version_code="$(read_version_value piko.versionCode PIKO_VERSION_CODE)"
  if [[ -z "$version_name" ]]; then
    echo "gradle.properties 缺少 piko.versionName。" >&2
    exit 1
  fi
  if [[ ! "$version_code" =~ ^[0-9]+$ ]]; then
    echo "piko.versionCode 必须是整数，当前值：$version_code" >&2
    exit 1
  fi

  local abis variants
  abis="$(read_enabled_keys android.architectures)"
  variants="$(read_enabled_keys android.variants)"
  if [[ -z "$abis" || -z "$variants" ]]; then
    echo "Android 至少需要开启一个架构和一个构建类型。" >&2
    exit 1
  fi

  if [[ " $variants " == *" release "* ]] && ! android_signing_configured; then
    cat >&2 <<'EOF'
Android release 需要签名配置。
请创建 android/signing/release-signing.properties，或导出 ANDROID_KEYSTORE_PATH /
ANDROID_KEYSTORE_PASSWORD / ANDROID_KEY_ALIAS / ANDROID_KEY_PASSWORD。
EOF
    exit 1
  fi

  mkdir -p "$ANDROID_ARTIFACT_ROOT"
  find "$ANDROID_ARTIFACT_ROOT" -type f -name "*.apk" -delete

  local abi variant tasks source target_dir suffix target apksigner
  for abi in $abis; do
    tasks=()
    for variant in $variants; do
      case "$variant" in
        debug) tasks+=(":android:assembleDebug") ;;
        release) tasks+=(":android:assembleRelease") ;;
        *) echo "未知 Android 构建类型：$variant" >&2; exit 1 ;;
      esac
    done

    echo "Android 构建：abi=$abi variants=$variants"
    if [[ " $variants " == *" release "* ]]; then
      run_gradle "${tasks[@]}" -x lintVitalRelease "-PpikoAndroidAbis=$abi"
    else
      run_gradle "${tasks[@]}" "-PpikoAndroidAbis=$abi"
    fi

    for variant in $variants; do
      source="$(find "$REPO_ROOT/android/build/outputs/apk/$variant" -maxdepth 1 -type f -name "*.apk" | sort | tail -1)"
      if [[ -z "$source" ]]; then
        echo "未找到 Android $variant APK。" >&2
        exit 1
      fi
      target_dir="$ANDROID_ARTIFACT_ROOT/$variant"
      mkdir -p "$target_dir"
      suffix=""
      if [[ "$variant" == "debug" ]]; then
        suffix="-debug"
      fi
      target="$target_dir/piko-$version_name-android-$abi$suffix.apk"
      copy_apk "$source" "$target"
      echo "$target"

      if [[ "$variant" == "release" ]]; then
        apksigner="$(find_apksigner)"
        if [[ -z "$apksigner" ]]; then
          echo "未找到 apksigner，无法验证 APK 签名。" >&2
          exit 1
        fi
        "$apksigner" verify --verbose "$target"
      fi
    done
  done
}

verify_ios_ipa() {
  local ipa="$1"
  local tmp
  tmp="$(mktemp -d)"
  unzip -q "$ipa" -d "$tmp"
  if ! find "$tmp/Payload" -maxdepth 1 -type d -name "*.app" | grep -q .; then
    rm -rf "$tmp"
    echo "IPA 缺少 Payload/*.app：$ipa" >&2
    exit 1
  fi
  if codesign -dv "$tmp"/Payload/*.app >/dev/null 2>&1; then
    rm -rf "$tmp"
    echo "IPA 不是未签名产物：$ipa" >&2
    exit 1
  fi
  rm -rf "$tmp"
}

build_ios() {
  if [[ "$(read_config build.enabled)" != "true" || "$(read_config ios.enabled)" != "true" ]]; then
    echo "iOS 构建已在配置中关闭。"
    return 0
  fi

  bash "$REPO_ROOT/scripts/ios/build-packages.sh"

  local ipa_count=0
  local ipa
  while IFS= read -r ipa; do
    verify_ios_ipa "$ipa"
    ipa_count=$((ipa_count + 1))
  done < <(find "$IOS_ARTIFACT_ROOT" -maxdepth 1 -type f -name "*.ipa" | sort)

  if [[ "$ipa_count" -eq 0 ]]; then
    echo "未找到 iOS IPA 产物。" >&2
    exit 1
  fi
}

main() {
  require_file "$CONFIG_PATH"
  require_file "$VERSION_PATH"

  cd "$REPO_ROOT"
  case "$PLATFORM" in
    all)
      build_android
      build_ios
      ;;
    android)
      build_android
      ;;
    ios)
      build_ios
      ;;
  esac

  echo "移动端构建完成。"
  find "$REPO_ROOT/scripts/artifacts" -maxdepth 5 -type f \( -name "*.apk" -o -name "*.ipa" \) | sort
}

main "$@"

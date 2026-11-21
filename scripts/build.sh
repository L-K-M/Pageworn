#!/usr/bin/env bash
# Build Pageworn from any directory inside the checkout.
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/build.sh
       scripts/build.sh [--debug | --release X.Y.Z] [--clean] [--install] [--check]

The default (also --debug) builds the debug APK and runs JVM tests.
  --clean     Clear Gradle build output before building.
  --install   Install and launch the APK on a connected device or emulator.
              Set ANDROID_SERIAL to choose a device when several are attached.
  --check     Print the Gradle command and APK path without building.

A release build needs PAGEWORN_KEYSTORE_FILE, PAGEWORN_KEYSTORE_PASSWORD,
PAGEWORN_KEY_ALIAS and PAGEWORN_KEY_PASSWORD. See CICD.md.
USAGE
}

usage_error() {
  usage >&2
  exit 2
}

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."

debug=false
clean=false
install=false
check=false
release_version=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --debug) debug=true ;;
    --clean) clean=true ;;
    --install) install=true ;;
    --check) check=true ;;
    --release)
      [[ $# -ge 2 && -z "$release_version" ]] || usage_error
      release_version="$2"
      scripts/release-version.sh "$release_version" >/dev/null
      shift
      ;;
    --help|-h) usage; exit 0 ;;
    *) usage_error ;;
  esac
  shift
done
if $debug && [[ -n "$release_version" ]]; then
  usage_error
fi

if [[ ! -x ./gradlew || ! -f app/build.gradle.kts ]]; then
  echo "error: the Android Gradle project is missing from this checkout. See the M0 baseline in docs/06-delivery-and-tests.md." >&2
  exit 1
fi

gradle=(./gradlew --console=plain)
if $clean; then
  gradle+=(clean)
fi
if [[ -n "$release_version" ]]; then
  # Signing inputs must stay out of Gradle's persistent configuration cache.
  gradle+=(--no-daemon --no-configuration-cache "-Ppageworn.versionName=$release_version" :app:assembleRelease)
  apk=app/build/outputs/apk/release/app-release.apk
else
  gradle+=(:app:assembleDebug :app:testDebugUnitTest)
  apk=app/build/outputs/apk/debug/app-debug.apk
fi

if $check; then
  printf '%s' 'Gradle:'
  printf ' %q' "${gradle[@]}"
  printf '\nAPK: %s\nInstall and launch: %s\n' "$apk" "$install"
  exit 0
fi

if ! $install; then
  exec "${gradle[@]}"
fi

android_adb() {
  local sdk_dir='' sdk
  if [[ -f local.properties ]]; then
    sdk_dir="$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*[=:][[:space:]]*//p' local.properties \
      | tail -n 1 | tr -d '\r' | sed 's/\\\(.\)/\1/g')"
  fi
  for sdk in "$sdk_dir" "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    if [[ -n "$sdk" && -x "$sdk/platform-tools/adb" ]]; then
      printf '%s\n' "$sdk/platform-tools/adb"
      return
    fi
  done
  command -v adb
}

if ! adb="$(android_adb)"; then
  echo 'error: adb not found. Install Android SDK platform-tools and set ANDROID_HOME or add adb to PATH.' >&2
  exit 1
fi

if ! serial="$("$adb" get-serialno)" || [[ -z "$serial" || "$serial" == unknown ]] ||
  [[ "$("$adb" -s "$serial" get-state)" != device ]]; then
  echo 'error: connect an authorized device or emulator. Set ANDROID_SERIAL when several are attached.' >&2
  exit 1
fi

"${gradle[@]}"
"$adb" -s "$serial" install -r "$apk"
"$adb" -s "$serial" shell am start -W -n ch.lkmc.pageworn/.MainActivity

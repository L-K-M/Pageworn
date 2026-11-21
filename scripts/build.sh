#!/usr/bin/env bash
# Build Pageworn from any directory inside the checkout.
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/build.sh
       scripts/build.sh --release X.Y.Z

The default builds the debug APK and runs JVM tests. A release build needs
PAGEWORN_KEYSTORE_FILE, PAGEWORN_KEYSTORE_PASSWORD, PAGEWORN_KEY_ALIAS and
PAGEWORN_KEY_PASSWORD. See CICD.md.
USAGE
}

release_version=''
case "$#:${1:-}" in
  0:) ;;
  1:--help|1:-h) usage; exit 0 ;;
  2:--release) release_version="$2"; "$(dirname -- "${BASH_SOURCE[0]}")/release-version.sh" "$release_version" >/dev/null ;;
  *) usage >&2; exit 2 ;;
esac

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
if [[ ! -x ./gradlew || ! -f app/build.gradle.kts ]]; then
  echo "error: the Android Gradle project is missing from this checkout. See the M0 baseline in docs/06-delivery-and-tests.md." >&2
  exit 1
fi

if [[ -n "$release_version" ]]; then
  # Signing inputs must stay out of Gradle's persistent configuration cache.
  exec ./gradlew --console=plain --no-daemon --no-configuration-cache \
    "-Ppageworn.versionName=$release_version" :app:assembleRelease
fi
exec ./gradlew --console=plain :app:assembleDebug :app:testDebugUnitTest

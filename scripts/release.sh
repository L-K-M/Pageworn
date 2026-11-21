#!/usr/bin/env bash
# Cut a local version tag, or pass --push to trigger the GitHub release.
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."

usage() {
  cat <<'USAGE'
Usage: scripts/release.sh X.Y.Z [--push]
       scripts/release.sh --check | --help | --version | --capabilities

Create a local version tag. Pass --push to publish it through CI.
USAGE
}

usage_error() {
  usage >&2
  exit 2
}

inspect=false
case "$#:${1:-}" in
  1:--help|1:-h) usage; exit 0 ;;
  1:--check|1:--version|1:--capabilities) inspect=true ;;
  1:-*) usage_error ;;
  1:*) version="$1" ;;
  2:--push) version="$2" ;;
  2:*) [[ "$1" != -* && "$2" == --push ]] || usage_error; version="$1" ;;
  *) usage_error ;;
esac

export RELEASE_APP_NAME=Pageworn
export RELEASE_KIND=tag-only
export RELEASE_INVOKED_AS=scripts/release.sh
export RELEASE_CI_NOTE='CI (release.yml) will build, sign and publish the Pageworn APK for <tag>.'

BIN="${LKM_RELEASE_BIN:-lkm-release}"
if ! command -v "$BIN" >/dev/null 2>&1; then
  echo 'error: install lkm-release from https://github.com/L-K-M/release-tool or set LKM_RELEASE_BIN.' >&2
  exit 1
fi

if $inspect; then
  exec "$BIN" "$@"
fi

code="$(scripts/release-version.sh "$version")"
if [[ ! -x ./gradlew || ! -f app/build.gradle.kts ]]; then
  echo 'error: the Android Gradle project is required to cut a release.' >&2
  exit 1
fi

if [[ "$(git branch --show-current)" != main ]]; then
  echo 'error: cut releases from a clean main checkout.' >&2
  exit 1
fi
remote_main="$(git ls-remote --exit-code origin refs/heads/main)"
if [[ "$(git rev-parse HEAD)" != "${remote_main%%[[:space:]]*}" ]]; then
  echo 'error: update local main to match origin/main before cutting a release.' >&2
  exit 1
fi

# Compare against remote tags even when the local checkout has never fetched them.
# shellcheck source=scripts/release-version.sh
source scripts/release-version.sh
remote_tags="$(git ls-remote --refs --tags origin 'v*')"
local_tags="$(git tag --list 'v*')"
require_newer_release "$version" "$code" < <(
  printf '%s\n' "$local_tags"
  awk '{sub(/^refs\/tags\//, "", $2); print $2}' <<< "$remote_tags"
)

exec "$BIN" "$@"

#!/usr/bin/env bash
# Cut a local version tag, or pass --push to trigger the GitHub release.
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."

export RELEASE_APP_NAME=Pageworn
export RELEASE_KIND=tag-only
export RELEASE_INVOKED_AS=scripts/release.sh
export RELEASE_CI_NOTE='CI (release.yml) will build, sign and publish the Pageworn APK for <tag>.'

if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then
  printf '%s\n' 'Usage: scripts/release.sh X.Y.Z [--push] | --check'
  exit 0
fi

BIN="${LKM_RELEASE_BIN:-lkm-release}"
if ! command -v "$BIN" >/dev/null 2>&1; then
  echo 'error: install lkm-release from https://github.com/L-K-M/release-tool or set LKM_RELEASE_BIN.' >&2
  exit 1
fi

if [[ "${1:-}" == --check ]]; then
  exec "$BIN" "$@"
fi
if [[ $# -lt 1 || $# -gt 2 || ( $# -eq 2 && "$2" != --push ) ]]; then
  echo 'Usage: scripts/release.sh X.Y.Z [--push] | --check' >&2
  exit 2
fi

version="$1"
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
while read -r tag; do
  existing_code="$(android_version_code "${tag#v}")" || continue
  if ((existing_code >= code)); then
    echo "error: v$version must be newer than $tag to upgrade an installed APK." >&2
    exit 1
  fi
done < <(printf '%s\n' "$local_tags"; awk '{sub(/^refs\/tags\//, "", $2); print $2}' <<< "$remote_tags")

exec "$BIN" "$@"

#!/usr/bin/env bash
# Translate a release version into its Android versionCode.
set -euo pipefail

version_pattern='^(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})$'

android_version_code() {
  local version="$1"
  [[ "$version" =~ $version_pattern ]] || return 1
  local major="${BASH_REMATCH[1]}" minor="${BASH_REMATCH[2]}" patch="${BASH_REMATCH[3]}"
  local code=$((major * 1000000 + minor * 1000 + patch))
  ((major <= 2099 && code > 0)) || return 1
  printf '%s\n' "$code"
}

require_newer_release() {
  local version="$1" code="$2" tag existing_code
  while read -r tag; do
    existing_code="$(android_version_code "${tag#v}")" || continue
    if ((existing_code >= code)); then
      echo "error: v$version must be newer than $tag to upgrade an installed APK." >&2
      return 1
    fi
  done
}

main() {
  local version="${1:-}" code
  if ! code="$(android_version_code "$version")"; then
    echo "error: use X.Y.Z without leading zeroes, above 0.0.0 and below 2100.0.0 (Y and Z at most 999)." >&2
    return 1
  fi
  if [[ "${2:-}" == --check-tag ]]; then
    # GitHub checks out all release tags, including the one it is building.
    local other_tags
    other_tags="$(git for-each-ref --format='%(refname:short)' --exclude="refs/tags/v$version" 'refs/tags/v*')"
    require_newer_release "$version" "$code" <<< "$other_tags"
  fi
  printf '%s\n' "$code"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi

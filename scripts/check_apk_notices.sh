#!/usr/bin/env bash
# Verify that a Pageworn APK carries its generated dependency notices and the
# bundled asset license texts.
#
# Usage: scripts/check_apk_notices.sh <apk> [label]
set -euo pipefail

if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then
  awk 'NR==1 && /^#!/ {next} /^#/ {sub(/^# ?/,""); print; next} {exit}' "$0"
  exit 0
fi
[[ $# -ge 1 && $# -le 2 ]] || {
  echo 'usage: scripts/check_apk_notices.sh <apk> [label]' >&2
  exit 2
}

apk=$1
label=${2:-$apk}
[[ -f $apk ]] || {
  echo "::error::$apk not found; did a previous step build the APK?"
  exit 1
}
for entry in res/raw/aboutlibraries.json res/raw/spectral_ofl.txt res/raw/pw_sound_credits.txt; do
  unzip -l "$apk" "$entry" | grep -q "$entry" || {
    echo "::error::$entry missing from $label"
    exit 1
  }
done

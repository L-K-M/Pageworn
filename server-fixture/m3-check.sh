#!/usr/bin/env bash
# Runs the M3 exit check (DownloadIntegrationTest) on one emulator against the local reference
# instance, through two fault proxies, changing the world between phases as the test's
# documentation describes.
#
#   server-fixture/m3-check.sh <emulator-serial>
#
# Needs: server-fixture/abs.sh up + seed (accounts in .state/accounts.json), the debug and
# androidTest APKs built (./gradlew :app:assembleDebug :app:assembleDebugAndroidTest).
# Refuses any serial that is not an emulator. The credentials are the disposable fixture
# account's and only travel as instrumentation arguments.

set -euo pipefail
SERIAL="${1:?emulator serial}"
case "$SERIAL" in emulator-*) ;; *) echo "refusing non-emulator serial $SERIAL" >&2; exit 2;; esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
STATE="$ROOT/server-fixture/.state"
PACKAGE="ch.lkmc.pageworn"
RUNNER="$PACKAGE.test/androidx.test.runner.AndroidJUnitRunner"
CLASS="$PACKAGE.transfer.DownloadIntegrationTest"
adb_shell() { "$ADB" -s "$SERIAL" shell "$@"; }

account() { python3 -c "import json,sys; print(json.load(open('$STATE/accounts.json'))[sys.argv[1]][sys.argv[2]])" "$1" "$2"; }

# "Home" and "Remote": two origins of the one server, each with its own fault rules and log.
PIDS=()
start_proxy() { # listen control
  python3 "$ROOT/server-fixture/fault_proxy.py" --listen "$1" --backend 13378 --control "$2" >/dev/null 2>&1 &
  PIDS+=($!)
}
cleanup() {
  for p in "${PIDS[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null || true; done
  adb_shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
  adb_shell svc wifi enable >/dev/null 2>&1 || true
  adb_shell run-as "$PACKAGE" rm -f files/filler.bin >/dev/null 2>&1 || true
}
trap cleanup EXIT

# ONLY="phase8 phase10" runs just the phases whose names start with those words, on top of the
# state an earlier full run left.
selected() { [ -z "${ONLY:-}" ] && return 0; for p in $ONLY; do case "$1" in "$p"*) return 0;; esac; done; return 1; }

run_phase() { # method [extra instrumentation arguments...]
  local method="$1"; shift
  selected "$method" || return 0
  local raw
  raw="$("$ADB" -s "$SERIAL" shell am instrument -w -r \
    -e class "$CLASS#$method" \
    -e absListener "$(account listener username)" -e absListenerPassword "$(account listener password)" \
    "$@" "$RUNNER")"
  echo "$raw" | grep -E "observation=" | sed 's/^.*observation=/  observation: /' || true
  if echo "$raw" | grep -q "INSTRUMENTATION_STATUS_CODE: -2\|INSTRUMENTATION_STATUS_CODE: -1\|shortMsg=Process crashed"; then
    echo "FAIL $method"; echo "$raw" | grep -A12 "stack=" | head -30; return 1
  fi
  if echo "$raw" | grep -q "INSTRUMENTATION_STATUS_CODE: -4"; then echo "SKIPPED $method"; return 1; fi
  echo "ok   $method"
}

app_pid() { adb_shell pidof "$PACKAGE" | tr -d '\r'; }
start_app() { adb_shell am start -W -n "$PACKAGE/.MainActivity" >/dev/null; }

"$ADB" -s "$SERIAL" install -r -t -g "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
"$ADB" -s "$SERIAL" install -r -t "$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null
API="$(adb_shell getprop ro.build.version.sdk | tr -d '\r')"
echo "$SERIAL (API $API)"

# Sign-in and pairing spend the server's auth budget (40 per 10 minutes from one client, shared
# with the host); one run needs about 6. Wait for the window to reset when fewer remain.
AUTH_NEEDED=10
header() { tr -d '\r' | awk -F': ' -v name="$1" 'tolower($1) == name { print $2 }'; }
headers="$(curl -s -D - -o /dev/null -X POST http://127.0.0.1:13378/auth/refresh)"
remaining="$(echo "$headers" | header ratelimit-remaining)"
reset="$(echo "$headers" | header ratelimit-reset)"
if [ -n "$remaining" ] && [ "$remaining" -lt "$AUTH_NEEDED" ]; then
  echo "  waiting ${reset}s for the server's sign-in limit ($remaining left)"
  sleep "$(( ${reset:-600} + 2 ))"
fi

start_proxy 13380 13390
start_proxy 13381 13391
sleep 1
STATUS=0

run_phase phase1DownloadPublishesOnceThroughDroppedConnectionsAndIgnoredRanges || STATUS=1

await_host() { for _ in $(seq 1 30); do adb_shell ping -c 1 -W 1 10.0.2.2 >/dev/null 2>&1 && return 0; sleep 1; done; return 1; }

# Phase 2: no network at all.
if selected phase2; then
  adb_shell cmd connectivity airplane-mode enable
  sleep 3
  run_phase phase2PlaysAcrossTheFileBoundaryWithoutAnyNetwork -e offline true || STATUS=1
  adb_shell cmd connectivity airplane-mode disable
  await_host || true
fi

run_phase phase3PauseResumeAndCancelAndRemoveHaveDistinctOutcomes || STATUS=1
run_phase phase4RemovedServingAddressAndOriginChange || STATUS=1

# Phase 5: the end of phase 5a's instrumentation stops the process mid-file. Start the app, let
# the transfer resume, kill the process the way the low-memory killer does, and start it again.
if selected phase5; then
run_phase phase5aLeaveATransferMidFile || STATUS=1
start_app
sleep 8
before="$(app_pid)"
if [ -n "$before" ]; then
  adb_shell run-as "$PACKAGE" kill -9 "$before" || STATUS=1
  sleep 2
  after="$(app_pid)"
  echo "  killed process $before; running now: ${after:-none}"
else
  echo "FAIL the app was not running before the kill"; STATUS=1
fi
start_app
sleep 5
run_phase phase5bRecoversAfterProcessDeath -e killed true || STATUS=1
fi

run_phase phase6StorageMarginAndAFullDisk || STATUS=1
run_phase phase7UnsupportedFormatAndExcludedFile || STATUS=1
run_phase phase8SignOutDuringATransferThenSignInContinuesIt || STATUS=1

# Phase 9 (Android 17): the local-network permission is revoked, which kills the app. The device
# cannot reach the proxies' control ports then, so the host checks that they received nothing.
if [ "$API" -ge 37 ] && selected phase9; then
  for port in 13390 13391; do curl -s -X POST http://127.0.0.1:$port/reset >/dev/null; done
  adb_shell pm revoke "$PACKAGE" android.permission.ACCESS_LOCAL_NETWORK
  run_phase phase9LocalNetworkRevokedDownloadTapStartsNothing || STATUS=1
  adb_shell pm grant "$PACKAGE" android.permission.ACCESS_LOCAL_NETWORK
  for port in 13390 13391; do
    seen="$(curl -s http://127.0.0.1:$port/log | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["requests"]))')"
    if [ "$seen" != "0" ]; then echo "FAIL phase9: proxy $port received $seen requests"; STATUS=1; fi
  done
fi

# Phase 10: Wi-Fi off, so only the emulator's metered mobile network remains.
if selected phase10; then
  adb_shell svc wifi disable
  sleep 5
  if await_host; then
    run_phase phase10MeteredMobileDataAloneDownloadsAtOnce -e metered true || STATUS=1
  else
    echo "FAIL phase10: the host is unreachable without Wi-Fi"; STATUS=1
  fi
  adb_shell svc wifi enable
  await_host || true
fi
exit $STATUS

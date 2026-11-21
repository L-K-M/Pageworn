#!/usr/bin/env bash
# Runs the host-driven parts of the M4 exit check on one emulator:
#
#   a16-noisy   A16 headphone removal: AUDIO_BECOMING_NOISY, sent twice from a root shell while
#               SystemControlsTest plays. Android lets neither apps nor the shell user send it.
#   a16e-kill   A16e and A16c: the book paused at 115 s at 1.5x with a timer armed, the app
#               opened and sent to the background, its process killed with `am kill` (never
#               force-stop), then a media-key Play. The host reads the outcome from dumpsys,
#               because a new instrumentation run would restart the process it checks.
#   restart     A10, A10b and A29: separate instrumentation runs verify that saved places
#               survive a process restart and that the ordinary rewind still applies.
#
#   repair      A20 and A10c through the local reference instance (RepairIntegrationTest), behind
#               the "Home" fault proxy: a browse-only account, a shortened track, a failed and a
#               successful repair, a zeroed track met during playback, and the relaunch and
#               repair after it. Each phase is its own instrumentation run, so the last one runs
#               in a new process.
#   notifications  A16b: clears the disposable emulator app data, downloads with notifications
#               denied, then grants permission and verifies a completion notification opens Downloaded.
#
#   server-fixture/m4-check.sh <emulator-serial>
#
# Needs the debug and androidTest APKs built (./gradlew :app:assembleDebug
# :app:assembleDebugAndroidTest), an emulator image that allows `adb root` (Google APIs, not
# Google Play), and for the repair phases server-fixture/abs.sh up and seeded (accounts in
# .state/accounts.json). Refuses any serial that is not an emulator. ONLY="a16e" runs only the
# phases whose names start with those words. The credentials are the disposable fixture
# accounts' and only travel as instrumentation arguments.

set -euo pipefail
SERIAL="${1:?emulator serial}"
case "$SERIAL" in emulator-*) ;; *) echo "refusing non-emulator serial $SERIAL" >&2; exit 2;; esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
PACKAGE="ch.lkmc.pageworn"
RUNNER="$PACKAGE.test/androidx.test.runner.AndroidJUnitRunner"
adb_() { "$ADB" -s "$SERIAL" "$@"; }
adb_shell() { adb_ shell "$@"; }

STATE="$ROOT/server-fixture/.state"
PIDS=()
WAS_ROOT=0
cleanup() {
  for p in "${PIDS[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null || true; done
  adb_shell cmd media_session dispatch pause >/dev/null 2>&1 || true
  adb_shell run-as "$PACKAGE" rm -f files/restart-marker.json >/dev/null 2>&1 || true
  if [ "$WAS_ROOT" = 0 ]; then adb_ unroot >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

selected() { [ -z "${ONLY:-}" ] && return 0; for p in $ONLY; do case "$1" in "$p"*) return 0;; esac; done; return 1; }

FAILED=0
report() { # raw method
  local raw="$1" method="$2"
  echo "$raw" | grep -E "observation=" | sed 's/^.*observation=/  observation: /' || true
  if [[ "$raw" == *"INSTRUMENTATION_STATUS_CODE: -4"* ]]; then
    echo "SKIPPED $method"
  elif [[ "$raw" == *"OK (1 test)"* && "$raw" == *"INSTRUMENTATION_CODE: -1"* ]]; then
    echo "ok   $method"
    return 0
  else
    echo "FAIL $method"
    echo "$raw" | grep -A12 -E "stack=|shortMsg=|INSTRUMENTATION_FAILED" | head -30 || true
  fi
  FAILED=1
  return 1
}

instrument() { # class#method [extra arguments...]
  local target="$1"; shift
  adb_shell am instrument -w -r -e class "$PACKAGE.$target" "$@" "$RUNNER"
}

check() { # description command...
  local description="$1"; shift
  if "$@"; then echo "  ok   $description"; else echo "  FAIL $description"; FAILED=1; fi
}
now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }
our_state() { # the playback state line of this app's platform session
  adb_shell dumpsys media_session | awk -v pkg="package=$PACKAGE" 'index($0, pkg) {p=1} p && /state=PlaybackState/ {print; exit}'
}
no_process() { [ -z "$(adb_shell pidof "$PACKAGE" | tr -d '\r')" ]; }
matches() { echo "$1" | grep -Eq "$2"; }
lacks() { ! echo "$1" | grep -q "$2"; }
between() { [ "${1:-0}" -ge "$2" ] && [ "${1:-0}" -le "$3" ]; }

adb_ install -r -t -g "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
adb_ install -r -t "$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null
API="$(adb_shell getprop ro.build.version.sdk | tr -d '\r')"
echo "$SERIAL (API $API)"

# --- A16, headphone removal ------------------------------------------------------------------------
if selected a16-noisy; then
  if [ "$(adb_shell id -u | tr -d '\r')" = 0 ]; then WAS_ROOT=1; else adb_ root >/dev/null; sleep 2; adb_ wait-for-device; fi
  adb_ logcat -c
  out="$(mktemp)"
  instrument playback.SystemControlsTest#a16HeadphoneRemovalPausesOnceAndEverySurfaceAgrees -e noisyFromHost true >"$out" 2>&1 &
  run=$!
  ready=0
  for _ in $(seq 1 120); do
    if adb_ logcat -d -s PagewornCheck:I | grep -q "ready: noisy"; then ready=1; break; fi
    sleep 0.5
  done
  if [ "$ready" = 1 ]; then
    adb_shell am broadcast -a android.media.AUDIO_BECOMING_NOISY >/dev/null
    sleep 2
    adb_shell am broadcast -a android.media.AUDIO_BECOMING_NOISY >/dev/null
  else
    echo "  the test never reported that it was playing"
  fi
  wait "$run" || true
  report "$(cat "$out")" a16-noisy || true
  rm -f "$out"
fi

# --- A16e and A16c, a media-key Play after the system killed the process ----------------------------
if selected a16e-kill; then
  report "$(instrument playback.RestartTest#a16ePart1PauseAt115At1_5xWithATimer)" a16e-kill-arrange || true
  adb_shell run-as "$PACKAGE" rm -f files/restart-marker.json
  adb_shell am start -W -n "$PACKAGE/.MainActivity" >/dev/null
  sleep 2
  adb_shell input keyevent KEYCODE_HOME
  sleep 2
  for _ in $(seq 1 20); do
    adb_shell am kill "$PACKAGE"
    [ -z "$(adb_shell pidof "$PACKAGE" | tr -d '\r')" ] && break
    sleep 1
  done
  check "am kill ended the background process" no_process
  played_at="$(now_ms)"
  adb_shell cmd media_session dispatch play
  sleep 4
  ours="$(our_state)"
  echo "  platform session while playing: $(echo "$ours" | sed -E 's/.*(state=[^,]*).*(speed=[0-9.]+).*/\1, \2/')"
  check "a media-key Play resumed the book" matches "$ours" "state=(PLAYING\()?3"
  check "at 1.5x" matches "$ours" "speed=1\.5"
  activities="$(adb_shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" || true)"
  check "without opening the activity" lacks "$activities" "$PACKAGE"
  services="$(adb_shell dumpsys activity services "$PACKAGE/.playback.PlaybackService")"
  check "with the service in the foreground" matches "$services" "isForeground=true"
  adb_shell cmd media_session dispatch pause
  paused_at="$(now_ms)"
  sleep 1
  position="$(our_state | sed -E 's/.*PlaybackState \{state=[^,]*, position=([0-9]+).*/\1/')"
  # Started at 115 s, so paused no later than 115 s plus the time it can have played at 1.5x.
  latest=$(( 115000 + (paused_at - played_at) * 3 / 2 + 500 ))
  check "started at its place, 115 s without a rewind (paused at ${position:-none} ms, at most $latest ms)" between "$position" 114700 "$latest"
fi

# --- A10, A10b and A29, progress after a process restart -------------------------------------------
if selected restart; then
  adb_shell run-as "$PACKAGE" rm -f files/restart-marker.json
  for method in a10Part1PauseALateThenListenToB a10Part2BothPlacesSurviveTheRestart \
      a10bPart1HeldSampleAfterSeekBack a10bPart2RestoresThenDiesBeforeACheckpoint \
      a10bPart3LossIsAtMostFiveSeconds a29Part1PauseAt115 a29Part2HeadsetPlayAfterTenMinutes; do
    report "$(instrument "playback.RestartTest#$method")" "$method" || break
  done
fi

# --- A20 and A10c, repair through the server --------------------------------------------------------
if selected repair || selected notifications; then
  account() { python3 -c "import json,sys; print(json.load(open('$STATE/accounts.json'))[sys.argv[1]][sys.argv[2]])" "$1" "$2"; }
  python3 "$ROOT/server-fixture/fault_proxy.py" --listen 13380 --backend 13378 --control 13390 >/dev/null 2>&1 &
  PIDS+=($!)
  sleep 1
  # Each phase that signs in spends the server's auth budget (40 per 10 minutes from one client).
  header() { tr -d '\r' | awk -F': ' -v name="$1" 'tolower($1) == name { print $2 }'; }
  headers="$(curl -s -D - -o /dev/null -X POST http://127.0.0.1:13378/auth/refresh)"
  remaining="$(echo "$headers" | header ratelimit-remaining)"
  reset="$(echo "$headers" | header ratelimit-reset)"
  if [ -n "$remaining" ] && [ "$remaining" -lt 6 ]; then
    echo "  waiting ${reset}s for the server's sign-in limit ($remaining left)"
    sleep "$(( ${reset:-600} + 2 ))"
  fi
fi

server_test() {
  local method="$1"
    report "$(instrument "transfer.RepairIntegrationTest#$method" \
      -e absListener "$(account listener username)" -e absListenerPassword "$(account listener password)" \
      -e absBrowser "$(account browser username)" -e absBrowserPassword "$(account browser password)")" "$method" || true
}

if selected repair; then
  for method in repair1BrowseOnlyAccountStartsNoTransfer repair2AShortTrackIsADamagedCopyAndOnlyASuccessfulRepairReplacesIt \
      repair3AZeroedTrackFailsWhenPlaybackReachesIt repair4AfterARelaunchTheRepairRestoresPlaybackFromThePlace; do
    server_test "$method"
  done
fi

if selected notifications; then
  adb_shell pm clear "$PACKAGE" >/dev/null
  if [ "$API" -ge 37 ]; then
    adb_shell pm grant "$PACKAGE" android.permission.ACCESS_LOCAL_NETWORK
  fi
  adb_shell pm revoke "$PACKAGE" android.permission.POST_NOTIFICATIONS
  server_test a16bDownloadsAndPlaybackWorkWithNotificationsDenied
  adb_shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS
fi

exit "$FAILED"

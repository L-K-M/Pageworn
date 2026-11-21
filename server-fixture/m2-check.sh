#!/usr/bin/env bash
# Runs the M2 exit check (ServerIntegrationTest) on one emulator against the local reference
# instance, changing the world between phases as the test's documentation describes.
#
#   server-fixture/m2-check.sh <emulator-serial>
#
# Needs: server-fixture/abs.sh up + seed (accounts in .state/accounts.json), the debug and
# androidTest APKs built (./gradlew :app:assembleDebug :app:assembleDebugAndroidTest).
# Refuses any serial that is not an emulator. The credentials are the disposable fixture
# accounts and only travel as instrumentation arguments.

set -euo pipefail
SERIAL="${1:?emulator serial}"
case "$SERIAL" in emulator-*) ;; *) echo "refusing non-emulator serial $SERIAL" >&2; exit 2;; esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
STATE="$ROOT/server-fixture/.state"
RUNNER="ch.lkmc.pageworn.test/androidx.test.runner.AndroidJUnitRunner"
CLASS="ch.lkmc.pageworn.account.ServerIntegrationTest"
PERMISSION="android.permission.ACCESS_LOCAL_NETWORK"

account() { python3 -c "import json,sys; print(json.load(open('$STATE/accounts.json'))[sys.argv[1]][sys.argv[2]])" "$1" "$2"; }
fingerprint() { openssl x509 -noout -fingerprint -sha256 -in "$STATE/tls/$1.pem" | cut -d= -f2; }

PIDS=()
start_front() { # name port [--issued]
  python3 "$ROOT/server-fixture/tls_proxy.py" --listen "$2" --cert "$1" ${3:-} >/dev/null 2>&1 &
  PIDS+=($!)
}

# The user-added authority store; Android reads it in each new app process.
USER_CA_DIR=/data/misc/user/0/cacerts-added
authority_file() { echo "$USER_CA_DIR/$(openssl x509 -subject_hash_old -noout -in "$STATE/tls/authority.pem").0"; }
as_root() { "$ADB" -s "$SERIAL" root >/dev/null 2>&1 && "$ADB" -s "$SERIAL" wait-for-device; }
install_authority() {
  as_root || return 1
  local target; target="$(authority_file)"
  "$ADB" -s "$SERIAL" shell "mkdir -p $USER_CA_DIR && chown system:system $USER_CA_DIR && chmod 755 $USER_CA_DIR" || return 1
  "$ADB" -s "$SERIAL" push "$STATE/tls/authority.pem" "$target" >/dev/null 2>&1 || return 1
  "$ADB" -s "$SERIAL" shell "chown system:system $target && chmod 644 $target && restorecon -R $USER_CA_DIR" >/dev/null 2>&1 || return 1
  "$ADB" -s "$SERIAL" unroot >/dev/null 2>&1; "$ADB" -s "$SERIAL" wait-for-device
}
remove_authority() {
  as_root && "$ADB" -s "$SERIAL" shell "rm -f $(authority_file)" >/dev/null
  "$ADB" -s "$SERIAL" unroot >/dev/null 2>&1; "$ADB" -s "$SERIAL" wait-for-device
}
stop_fronts() { for p in "${PIDS[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null || true; done; PIDS=(); sleep 1; }
trap stop_fronts EXIT

run_phase() { # method [class]
  local raw
  raw="$("$ADB" -s "$SERIAL" shell am instrument -w -r \
    -e class "${2:-$CLASS}#$1" \
    -e absListener "$(account listener username)" -e absListenerPassword "$(account listener password)" \
    -e absBrowser "$(account browser username)" -e absBrowserPassword "$(account browser password)" \
    -e tlsSha256 "$TLS_SHA256" -e privateAuthority "${PRIVATE_AUTHORITY:-none}" "$RUNNER")"
  echo "$raw" | grep -E "observation=" | sed 's/^.*observation=/  observation: /' || true
  if echo "$raw" | grep -q "INSTRUMENTATION_STATUS_CODE: -2\|INSTRUMENTATION_STATUS_CODE: -1\|shortMsg=Process crashed"; then
    echo "FAIL $1"; echo "$raw" | grep -A12 "stack=" | head -30; return 1
  fi
  if echo "$raw" | grep -q "INSTRUMENTATION_STATUS_CODE: -4"; then echo "SKIPPED $1"; return 1; fi
  echo "ok   $1"
}

"$ADB" -s "$SERIAL" install -r -t -g "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
"$ADB" -s "$SERIAL" install -r -t "$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null
API="$("$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
echo "$SERIAL (API $API)"

# Phase 1 with the first self-signed certificate.
python3 "$ROOT/server-fixture/tls_proxy.py" --cert first --generate-only >/dev/null
python3 "$ROOT/server-fixture/tls_proxy.py" --cert second --generate-only >/dev/null
start_front first 13443; sleep 1
STATUS=0
TLS_SHA256="$(fingerprint first)" run_phase phase1SignInBrowseEveryPageAndPairTwoAddresses || STATUS=1

# Phase 2: the server's certificate changed; a second origin presents it too.
stop_fronts; start_front second 13443; start_front second 13444; sleep 1
TLS_SHA256="$(fingerprint second)" run_phase phase2ChangedCertificate || STATUS=1

# Phase 3: sign out and in again.
TLS_SHA256="$(fingerprint second)" run_phase phase3SignOutThenSignIn || STATUS=1

# Phase 4: the local-network permission is revoked (Android 17); revoking kills the app.
if [ "$API" -ge 37 ]; then "$ADB" -s "$SERIAL" shell pm revoke ch.lkmc.pageworn "$PERMISSION"; fi
TLS_SHA256="$(fingerprint second)" run_phase phase4LocalNetworkDenied || STATUS=1
if [ "$API" -ge 37 ]; then "$ADB" -s "$SERIAL" shell pm grant ch.lkmc.pageworn "$PERMISSION"; fi

# Phase 5: an address whose certificate comes from an authority installed on the phone. The
# fixture authority goes into the emulator's user store as root, and leaves it afterwards.
python3 "$ROOT/server-fixture/tls_proxy.py" --cert office --issued --generate-only >/dev/null
if install_authority; then
  start_front office 13445 --issued; sleep 1
  PRIVATE_AUTHORITY=installed TLS_SHA256="$(fingerprint second)" run_phase phase5InstalledAuthority || STATUS=1
  remove_authority
else
  echo "FAIL phase5InstalledAuthority: could not install the fixture authority (needs a rootable image)"; STATUS=1
fi

# Phase 6: add, rename, reorder, test and remove addresses (A19).
TLS_SHA256="$(fingerprint second)" run_phase phase6ManageAddresses || STATUS=1

# The same flow through the screens: connection setup, Library, sign-out and sign-in.
TLS_SHA256="$(fingerprint second)" run_phase connectBrowseSignOutAndSignInAgain ch.lkmc.pageworn.ui.ConnectFlowTest || STATUS=1
exit $STATUS

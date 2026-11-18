#!/usr/bin/env bash
# The player after process death (heyarr-kmp#232), on the emulator, from outside the app's
# process. An instrumented test runs inside that process, so killing it ends the test; this
# runs ProcessDeathTest twice instead. Phase `leave` plays a film through the app's own graph
# and presses HOME, and the process then ends with that instrumentation, the way a kill in the
# background ends it: no Stop, nothing after the activity's stop. Phase `restore` starts a
# fresh process and checks that the film comes back paused where it was.
#
# Needs the debug app and its test APK installed (`:androidApp:installDebug
# :androidApp:installDebugAndroidTest`) and one device on adb. Run by instrumented.yml.
set -euo pipefail

APP=one.rarebit.heyarr.mobile
RUNNER="$APP.test/androidx.test.runner.AndroidJUnitRunner"
CLASS="$APP.ProcessDeathTest"

# One test, one phase. Every status line must be a pass: an assumption that skipped the test
# (no phase reached it) is a failure here, not a quiet "OK".
phase() {
  local test=$1 name=$2 out
  out=$(adb shell am instrument -w -r -e phase "$name" -e class "$CLASS#$test" "$RUNNER" | tr -d '\r')
  echo "$out" | grep -E "INSTRUMENTATION_STATUS: (test|stack)=|INSTRUMENTATION_STATUS_CODE|INSTRUMENTATION_CODE" || true
  if ! grep -q "^INSTRUMENTATION_STATUS_CODE: 0$" <<<"$out" || grep -q "^INSTRUMENTATION_STATUS_CODE: -" <<<"$out"; then
    echo "$out"
    echo "::error::ProcessDeathTest#$test (phase $name) did not pass"
    exit 1
  fi
  echo "ProcessDeathTest#$test passed (phase $name)"
}

adb shell pm clear "$APP" >/dev/null
phase leaveAFilmPlaying leave

# The instrumentation ends the process with it; make sure, as `am kill` does in the background.
adb shell am kill "$APP" || true
for _ in $(seq 1 20); do
  adb shell pidof "$APP" >/dev/null 2>&1 || break
  sleep 0.5
done
if adb shell pidof "$APP" >/dev/null 2>&1; then
  echo "::error::$APP is still running; the restore would not be in a fresh process"
  exit 1
fi

phase restoreItPaused restore

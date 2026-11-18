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

# shellcheck source=instrument-lib.sh
source "$(dirname "$0")/instrument-lib.sh"

adb shell pm clear "$APP" >/dev/null
instrument ProcessDeathTest#leaveAFilmPlaying leave

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

instrument ProcessDeathTest#restoreItPaused restore

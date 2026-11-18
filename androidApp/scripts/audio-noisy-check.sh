#!/usr/bin/env bash
# Headphones or Bluetooth going away (heyarr-kmp#232), on the emulator: both players pause on
# ACTION_AUDIO_BECOMING_NOISY (Media3's setHandleAudioBecomingNoisy). It is a protected
# broadcast only the system may send, so an instrumented test cannot send it to itself: this
# runs AudioInterruptionTest's noisy cases (phase `noisy`) and, once each says it is playing
# and ready (a log line), sends the broadcast from a root shell, as the audio stack would.
#
# Needs the debug app and its test APK installed and an emulator image that allows `adb root`
# (google_apis does). Run by instrumented.yml.
set -euo pipefail
# shellcheck source=instrument-lib.sh
source "$(dirname "$0")/instrument-lib.sh"

adb root >/dev/null
adb wait-for-device
for _ in $(seq 1 30); do
  [ "$(adb shell id -u | tr -d '\r')" = "0" ] && break
  sleep 1
done

for test in headphonesGoingAwayPauseTheFilm headphonesGoingAwayPauseTheMusic; do
  adb logcat -c
  out=$(mktemp)
  adb shell am instrument -w -r -e phase noisy -e class "$APP.AudioInterruptionTest#$test" "$RUNNER" >"$out" &
  run=$!
  ready=""
  for _ in $(seq 1 120); do
    if adb logcat -d -s HeyarrNoisy:I | grep -q "ready for the headphones to go"; then
      ready=yes
      break
    fi
    sleep 0.5
  done
  if [ -z "$ready" ]; then
    wait "$run" || true
    instrument_passed <"$out" || true
    echo "::error::$test never got to playing"
    exit 1
  fi
  adb shell am broadcast -a android.media.AUDIO_BECOMING_NOISY
  wait "$run" || true
  if ! instrument_passed <"$out"; then
    echo "::error::AudioInterruptionTest#$test (phase noisy) did not pass"
    exit 1
  fi
  echo "AudioInterruptionTest#$test passed (phase noisy)"
done

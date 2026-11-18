# Shared by the emulator checks that drive the device from outside the app's process
# (process-death-check.sh, audio-noisy-check.sh): run one instrumented test with a phase
# argument, and judge its raw output. Sourced, not run.

APP=one.rarebit.heyarr.mobile
RUNNER="$APP.test/androidx.test.runner.AndroidJUnitRunner"

# Every status line must be a pass: an assumption that skipped the test (no phase reached it)
# is a failure here, not a quiet "OK". Reads the raw (`-r`) output on stdin.
instrument_passed() {
  local out
  out=$(tr -d '\r')
  echo "$out" | grep -E "INSTRUMENTATION_STATUS: (test|stack)=|INSTRUMENTATION_STATUS_CODE|INSTRUMENTATION_CODE" || true
  if ! grep -q "^INSTRUMENTATION_STATUS_CODE: 0$" <<<"$out" || grep -q "^INSTRUMENTATION_STATUS_CODE: -" <<<"$out"; then
    echo "$out"
    return 1
  fi
}

# instrument <Class#test> <phase>: run it and wait.
instrument() {
  local test=$1 phase=$2
  if ! adb shell am instrument -w -r -e phase "$phase" -e class "$APP.$test" "$RUNNER" | instrument_passed; then
    echo "::error::$test (phase $phase) did not pass"
    exit 1
  fi
  echo "$test passed (phase $phase)"
}

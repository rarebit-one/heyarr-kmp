#!/usr/bin/env bash
# A real network outage longer than the buffer (heyarr-kmp#232), on the emulator: the film is
# served from this host (the emulator reaches it as 10.0.2.2), and once it is playing with its
# buffer full, the emulator's Wi-Fi and mobile data are turned off for longer than the buffer
# lasts, then back on. NetworkResilienceTest's `wifi` phase checks that the film stalled with a
# message, held its place with no error, and carried on from there.
#
# The clip is the test's own, encoded on the device (phase `clip`) and copied off with run-as,
# which the debug build allows. Needs the debug app and its test APK installed. Run by
# instrumented.yml.
set -euo pipefail
# shellcheck source=instrument-lib.sh
source "$(dirname "$0")/instrument-lib.sh"

PORT=8765
OFF_SECONDS=75
work=$(mktemp -d)
server=""
cleanup() {
  adb shell svc wifi enable >/dev/null 2>&1 || true
  adb shell svc data enable >/dev/null 2>&1 || true
  [ -n "$server" ] && kill "$server" 2>/dev/null || true
}
trap cleanup EXIT

instrument NetworkResilienceTest#writeTheClip clip
adb exec-out run-as "$APP" cat cache/smoke/net-clip.mp4 >"$work/clip.mp4"
[ -s "$work/clip.mp4" ] || { echo "::error::could not copy the clip off the device"; exit 1; }

# A file server with Range, as the blob endpoint is (python's http.server has none), sending at
# three times the clip's own pace: a small clip would otherwise arrive whole in one read, and a
# player that holds the whole film cannot be starved by an outage.
CLIP_SECONDS=120
python3 - "$work/clip.mp4" "$PORT" "$CLIP_SECONDS" <<'PY' &
import http.server, os, sys, time
path, port, seconds = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
size = os.path.getsize(path)
rate = size * 3 / seconds
class Clip(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        start, end = 0, size - 1
        rng = self.headers.get("Range")
        if rng and rng.startswith("bytes="):
            a, _, b = rng[6:].partition("-")
            start = int(a or 0)
            end = int(b) if b else size - 1
        self.send_response(206 if rng else 200)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if rng:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        with open(path, "rb") as f:
            f.seek(start)
            left = end - start + 1
            sent, began = 0, time.monotonic()
            try:
                while left > 0:
                    chunk = f.read(min(16384, left))
                    self.wfile.write(chunk)
                    left -= len(chunk)
                    sent += len(chunk)
                    ahead = sent / rate - (time.monotonic() - began)
                    if ahead > 0:
                        time.sleep(ahead)
            except (BrokenPipeError, ConnectionResetError, OSError):
                pass
    def log_message(self, *args):
        pass
http.server.ThreadingHTTPServer(("0.0.0.0", port), Clip).serve_forever()
PY
server=$!
sleep 1

adb logcat -c
out="$work/instrument.txt"
adb shell am instrument -w -r -e phase wifi -e url "http://10.0.2.2:$PORT/clip.mp4" \
  -e class "$APP.NetworkResilienceTest#aWifiOutageLongerThanTheBufferIsRiddenOut" "$RUNNER" >"$out" &
run=$!
ready=""
for _ in $(seq 1 180); do
  if adb logcat -d -s HeyarrNetwork:I | grep -q "ready for the network to go"; then
    ready=yes
    break
  fi
  sleep 0.5
done
if [ -z "$ready" ]; then
  wait "$run" || true
  instrument_passed <"$out" || true
  echo "::error::the film never got to playing with its buffer full"
  exit 1
fi
echo "network off for ${OFF_SECONDS}s"
adb shell svc wifi disable
adb shell svc data disable
sleep "$OFF_SECONDS"
adb shell svc wifi enable
adb shell svc data enable
echo "network back on"
wait "$run" || true
if ! instrument_passed <"$out"; then
  echo "::error::NetworkResilienceTest#aWifiOutageLongerThanTheBufferIsRiddenOut (phase wifi) did not pass"
  exit 1
fi
echo "NetworkResilienceTest#aWifiOutageLongerThanTheBufferIsRiddenOut passed (phase wifi)"

#!/usr/bin/env bash
# The EPUB reader after a real process death (heyarr-kmp#240), on the emulator, driven wholly
# from outside the app. On a phone: open a book, HOME, `am kill`, relaunch from the launcher,
# and the reader came back showing Readium's error object instead of the book. Android brought
# back only the reader, without the shell that signs the process in.
#
# No instrumentation runs here, unlike process-death-check.sh, because an instrumentation ends
# with a force-stop of the app, which also throws away its activities, and so the very restore
# under test. Instead the app is started as the launcher starts it, the reader is opened on top
# of the shell from a root shell (it is not exported), and what the reader kept and showed is
# read with run-as (the debug build allows it) and uiautomator. The book is the same two-chapter
# EPUB as TestEpub, served from this host (the emulator reaches it as 10.0.2.2) with Range, as
# the blob route serves it.
#
# 1. Opened at chapter two (the page kept for it), HOME, `am kill`, relaunch: the reader hands
#    the book to the shell, which reopens it at chapter two; Back lands in the shell.
# 2. The same, with the server refusing (401): a message in words with Retry and Back, nothing
#    of the error object; Retry, once it serves again, opens the book at chapter two.
#
# Needs the debug app installed and an emulator image that allows `adb root` (google_apis does).
# Run by instrumented.yml.
set -euo pipefail
# shellcheck source=instrument-lib.sh
source "$(dirname "$0")/instrument-lib.sh"

PORT=8766
ASSET=reader-death-asset
REFUSED_TEXT="let this phone open the book" # ReaderProblem.REFUSED.title, past its apostrophe
work=$(mktemp -d)
server=""
cleanup() {
  [ -n "$server" ] && kill "$server" 2>/dev/null || true
}
trap cleanup EXIT

adb root >/dev/null
adb wait-for-device
for _ in $(seq 1 30); do
  [ "$(adb shell id -u | tr -d '\r')" = "0" ] && break
  sleep 1
done

# The book, entry for entry as TestEpub.bytes() builds it: `mimetype` first and stored.
python3 - "$work/book.epub" <<'PY'
import sys, zipfile
para = "".join(f"<p>Words on a page of chapter {{n}}.</p>" for _ in range(40))
def chapter(n):
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter %d</title></head>\n'
            '  <body><h1>Chapter %d</h1>%s</body>\n</html>' % (n, n, para.replace("{n}", str(n))))
files = {
    "META-INF/container.xml": '<?xml version="1.0"?>\n<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">\n  <rootfiles>\n    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>\n  </rootfiles>\n</container>',
    "OEBPS/content.opf": '<?xml version="1.0" encoding="UTF-8"?>\n<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">\n  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">\n    <dc:identifier id="id">urn:uuid:3b7c2e9a-heyarr-reader-death</dc:identifier>\n    <dc:title>Book</dc:title>\n    <dc:language>en</dc:language>\n    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>\n  </metadata>\n  <manifest>\n    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>\n    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>\n    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>\n  </manifest>\n  <spine><itemref idref="ch1"/><itemref idref="ch2"/></spine>\n</package>',
    "OEBPS/nav.xhtml": '<?xml version="1.0" encoding="UTF-8"?>\n<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">\n  <body><nav epub:type="toc"><ol>\n    <li><a href="ch1.xhtml">One</a></li><li><a href="ch2.xhtml">Two</a></li>\n  </ol></nav></body>\n</html>',
    "OEBPS/ch1.xhtml": chapter(1),
    "OEBPS/ch2.xhtml": chapter(2),
}
with zipfile.ZipFile(sys.argv[1], "w") as z:
    z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", compress_type=zipfile.ZIP_STORED)
    for name, text in files.items():
        z.writestr(name, text, compress_type=zipfile.ZIP_DEFLATED)
PY

# A file server with Range, as the blob endpoint is (python's http.server has none). While the
# `refuse` file exists it answers 401, as the node answers a request with no credential.
python3 - "$work/book.epub" "$PORT" "$work/refuse" <<'PY' &
import http.server, os, sys
path, port, refuse = sys.argv[1], int(sys.argv[2]), sys.argv[3]
data = open(path, "rb").read()
class Book(http.server.BaseHTTPRequestHandler):
    def reply(self, body):
        if os.path.exists(refuse):
            self.send_response(401)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        start, end = 0, len(data) - 1
        rng = self.headers.get("Range")
        if rng and rng.startswith("bytes="):
            a, _, b = rng[6:].partition("-")
            start = int(a or 0)
            end = min(int(b), len(data) - 1) if b else len(data) - 1
        self.send_response(206 if rng else 200)
        self.send_header("Content-Type", "application/epub+zip")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if rng:
            self.send_header("Content-Range", f"bytes {start}-{end}/{len(data)}")
        self.end_headers()
        if body:
            try:
                self.wfile.write(data[start:end + 1])
            except (BrokenPipeError, ConnectionResetError, OSError):
                pass
    def do_GET(self):
        self.reply(True)
    def do_HEAD(self):
        self.reply(False)
    def log_message(self, *args):
        pass
http.server.ThreadingHTTPServer(("0.0.0.0", port), Book).serve_forever()
PY
server=$!
sleep 1

# What the reader keeps for the book: chapter two with no position, one the navigator has not
# made, so its next report shows where it opened. Written only while the app is not running.
seed() {
  local xml b64
  xml="<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name=\"locator.$ASSET\">{&quot;href&quot;:&quot;OEBPS/ch2.xhtml&quot;,&quot;type&quot;:&quot;application/xhtml+xml&quot;,&quot;locations&quot;:{&quot;progression&quot;:0.0}}</string></map>"
  b64=$(printf '%s' "$xml" | base64 | tr -d '\n')
  adb shell "run-as $APP mkdir -p shared_prefs && echo $b64 | base64 -d | run-as $APP sh -c 'cat > shared_prefs/heyarr-reading.xml'"
}

# The navigator reported a place of its own (Readium adds the position) in chapter two.
await_opened() {
  local kept=""
  for _ in $(seq 1 60); do
    kept=$(adb shell run-as "$APP" cat shared_prefs/heyarr-reading.xml 2>/dev/null | tr -d '\r' || true)
    if grep -q "ch2.xhtml" <<<"$kept" && grep -q "position" <<<"$kept"; then
      echo "opened at chapter two: $1"
      return 0
    fi
    sleep 1
  done
  echo "::error::the reader did not open at chapter two: $1 (kept: $kept)"
  ui | grep -o 'text="[^"]*"' | grep -v 'text=""' || true
  exit 1
}

resumed() { adb shell dumpsys activity activities | tr -d '\r' | grep -m1 -E "topResumedActivity|mResumedActivity" || true; }

ui() {
  for _ in 1 2 3; do
    adb shell uiautomator dump /sdcard/reader-ui.xml >/dev/null 2>&1 && break
    sleep 1
  done
  adb shell cat /sdcard/reader-ui.xml | tr -d '\r'
}

# The shell as the launcher starts it, then the book on top of it, as Read opens it.
open_book() {
  adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$APP/.MainActivity" >/dev/null
  sleep 3
  adb shell am start -W -n "$APP/.reader.ReaderActivity" \
    --es asset_id "$ASSET" --es url "http://10.0.2.2:$PORT/book.epub" --es title Book --es format EPUB >/dev/null
  adb shell dumpsys activity activities | tr -d '\r' | grep -E "^ *\* (Task|Hist)|ActivityRecord" | head -8 || true
}

# HOME, then the process dies in the background, as Android kills it for memory.
die_in_background() {
  adb shell input keyevent KEYCODE_HOME
  sleep 2 # the reader's stop, and the saved state Android keeps for it
  adb shell am kill "$APP" || true
  for _ in $(seq 1 20); do
    adb shell pidof "$APP" >/dev/null 2>&1 || break
    sleep 0.5
  done
  if adb shell pidof "$APP" >/dev/null 2>&1; then
    echo "::error::$APP is still running after am kill"
    exit 1
  fi
}

# Back from the launcher, as the person taps the app's icon.
relaunch() {
  adb logcat -c
  adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
    -f 0x10200000 -n "$APP/.MainActivity" >/dev/null
}

handed_to_shell() {
  for _ in $(seq 1 20); do
    if adb logcat -d -s HeyarrReader:I | grep -q "restored after process death"; then
      echo "the restored reader handed the book to the shell"
      return 0
    fi
    sleep 0.5
  done
  echo "::error::no reader was restored after the process died (was it on top of the shell?)"
  adb shell dumpsys activity activities | tr -d '\r' | grep -E "^ *\* (Task|Hist)|ActivityRecord" | head -8 || true
  exit 1
}

# ── 1. back at its page ──────────────────────────────────────────────────────────────────────
adb shell pm clear "$APP" >/dev/null
seed
open_book
await_opened "first open"
die_in_background
seed # forget its report, so the next one is the restored reader's own
relaunch
handed_to_shell
await_opened "after the process died"
resumed | grep -q "reader.ReaderActivity" || { echo "::error::the reader is not in front: $(resumed)"; exit 1; }
adb shell input keyevent KEYCODE_BACK
sleep 2
resumed | grep -q "MainActivity" || { echo "::error::Back did not land in the shell: $(resumed)"; exit 1; }
echo "Back from the restored reader lands in the shell"

# ── 2. refused: words, Retry and Back; Retry opens it ───────────────────────────────────────
adb shell am force-stop "$APP"
seed
open_book
await_opened "first open, before the refusal"
die_in_background
seed
touch "$work/refuse"
relaunch
handed_to_shell
shown=""
for _ in $(seq 1 30); do
  shown=$(ui)
  grep -q "$REFUSED_TEXT" <<<"$shown" && break
  sleep 1
done
grep -q "$REFUSED_TEXT" <<<"$shown" || { echo "::error::no refusal message"; grep -o 'text="[^"]*"' <<<"$shown" | grep -v 'text=""' || true; exit 1; }
# The shared buttons set their labels in capitals.
for want in 'text="RETRY"' 'text="BACK"'; do
  grep -q "$want" <<<"$shown" || { echo "::error::the refusal has no $want"; exit 1; }
done
if grep -qE "org\.readium|RetrieveUrlError|Exception" <<<"$shown"; then
  echo "::error::the error object reached the screen"
  grep -o 'text="[^"]*"' <<<"$shown" | grep -v 'text=""' || true
  exit 1
fi
echo "the refusal is shown in words, with Retry and Back"
rm "$work/refuse"
# The centre of the RETRY button, from the dump's own bounds (parsed as XML, not grepped).
tap=$(printf '%s' "$shown" | python3 -c '
import re, sys, xml.etree.ElementTree as ET
xml = sys.stdin.read()
root = ET.fromstring(xml[xml.find("<"):])
for node in root.iter("node"):
    if node.get("text") == "RETRY" or node.get("content-desc") == "Retry":
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
' || true)
if [ -z "$tap" ]; then
  echo "::error::no RETRY button to tap in the dump"
  grep -o 'text="[^"]*"' <<<"$shown" | grep -v 'text=""' || true
  exit 1
fi
read -r x y <<<"$tap"
echo "tapping RETRY at $x,$y"
adb shell input tap "$x" "$y"
await_opened "after Retry"
echo "reader-process-death-check passed"

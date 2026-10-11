#!/usr/bin/env bash
set -Eeuo pipefail

PKG=ru.pauza.app
ACTIVITY="$PKG/.MainActivity"
ARTIFACT_DIR=qa-artifacts/responsive-setup
mkdir -p "$ARTIFACT_DIR"
fail() {
  echo "FAIL: $*" | tee "$ARTIFACT_DIR/summary.txt"
  adb exec-out screencap -p > "$ARTIFACT_DIR/failure.png" || true
  adb logcat -d > "$ARTIFACT_DIR/logcat.txt" || true
  exit 1
}
echo "Install and reset test app"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm clear "$PKG" >/dev/null
echo "Use narrow display and 150% Android font size"
adb shell wm size 720x1600
adb shell wm density 360
adb shell settings put system font_scale 1.5
adb shell am start -W -n "$ACTIVITY" || fail 'Unable to launch Pause'
READY=0
for i in $(seq 1 18); do
  adb shell uiautomator dump /sdcard/pauza-responsive.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/pauza-responsive.xml > "$ARTIFACT_DIR/narrow.xml" 2>/dev/null || true
  if grep -Fq 'Открыть специальные возможности' "$ARTIFACT_DIR/narrow.xml"; then
    READY=1
    break
  fi
  sleep 1
done
[ "$READY" -eq 1 ] || fail 'First-setup accessibility button missing'
adb exec-out screencap -p > "$ARTIFACT_DIR/narrow-320dp-font150.png"
python3 - <<'PY' || fail 'Accessibility button failed bounds validation'
import re
import xml.etree.ElementTree as ET
root=ET.parse("qa-artifacts/responsive-setup/narrow.xml").getroot()
matches=[node for node in root.iter("node") if node.attrib.get("text") == "Открыть специальные возможности"]
assert len(matches) == 1, f"Expected one button, got {len(matches)}"
b=matches[0].attrib.get("bounds","")
m=re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",b)
assert m, b
x1,y1,x2,y2=map(int,m.groups())
assert 0<=x1<x2<=720 and 0<=y1<y2<=1600, f"Outside screen: {b}"
assert y2-y1>=72, f"Unexpectedly short button at 150% font scale: {b}"
print(f"PASS: permission button readable and in viewport at 320dp/150%: {b}")
PY
echo "PASS: permission button is visible at 320dp with 150% font size" | tee "$ARTIFACT_DIR/summary.txt"
adb shell wm size reset
adb shell wm density reset
adb shell settings put system font_scale 1.0
adb logcat -d > "$ARTIFACT_DIR/logcat.txt" || true

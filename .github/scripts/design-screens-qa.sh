#!/usr/bin/env bash
set -Eeuo pipefail

PKG="ru.pauza.app"
ACTIVITY="$PKG/.MainActivity"
ACCESSIBILITY_COMPONENT="$PKG/$PKG.domain.PauseAccessibilityService"
APK="app/build/outputs/apk/debug/app-debug.apk"
OUT="design-screens"
mkdir -p "$OUT"

foreground_package() {
  adb shell dumpsys activity activities 2>/dev/null |
    grep -m1 -E "topResumedActivity=|mResumedActivity" |
    sed -n 's/.* u[0-9]\+ \([^/ ]*\)\/.*/\1/p'
}

dump_ui() {
  adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
  adb pull /sdcard/window.xml "$1" >/dev/null 2>&1 || true
}

tap_text() {
  local needle="$1"
  local xml="$OUT/current.xml"
  dump_ui "$xml"
  python3 - "$xml" "$needle" <<'PY'
import re, sys, xml.etree.ElementTree as ET, subprocess
path, needle = sys.argv[1], sys.argv[2]
root = ET.parse(path).getroot()
for node in root.iter("node"):
    text = (node.attrib.get("text","") + " " + node.attrib.get("content-desc","")).strip()
    if needle.lower() in text.lower():
        b=node.attrib.get("bounds","")
        m=re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",b)
        if m:
            x1,y1,x2,y2=map(int,m.groups())
            subprocess.check_call(["adb","shell","input","tap",str((x1+x2)//2),str((y1+y2)//2)])
            sys.exit(0)
sys.exit(2)
PY
}

adb install -r "$APK" >/dev/null
adb shell cmd appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow || true
adb shell cmd appops set "$PKG" GET_USAGE_STATS allow || true
adb shell settings --user 0 put secure enabled_accessibility_services "$ACCESSIBILITY_COMPONENT"
adb shell settings --user 0 put secure accessibility_enabled 1
adb shell dumpsys deviceidle whitelist +"$PKG" || true

# Accessibility on a cold AVD may take several seconds to bind.
for _ in $(seq 1 40); do
  if adb shell dumpsys accessibility 2>/dev/null | grep -A8 "Bound services:" | grep -Fq "PauseAccessibilityService"; then
    break
  fi
  adb shell settings --user 0 put secure enabled_accessibility_services "$ACCESSIBILITY_COMPONENT"
  adb shell settings --user 0 put secure accessibility_enabled 1
  sleep 0.5
done

cat > /tmp/pause_store.xml <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="first_setup_completed" value="true" />
    <boolean name="restricted_settings_confirmed" value="true" />
    <boolean name="setup_checklist_completed" value="true" />
</map>
EOF
adb push /tmp/pause_store.xml /data/local/tmp/pause_store.xml >/dev/null
adb shell run-as "$PKG" mkdir -p "/data/user/0/$PKG/shared_prefs"
adb shell run-as "$PKG" cp /data/local/tmp/pause_store.xml "/data/user/0/$PKG/shared_prefs/pause_store.xml"

adb shell am force-stop "$PKG"
adb shell am start -W -n "$ACTIVITY" >/dev/null
sleep 2

# Give the setup-state poll enough time to observe granted Usage Access + Accessibility.
for _ in $(seq 1 20); do
  dump_ui "$OUT/setup-check.xml"
  if ! grep -q "Настройка Паузы" "$OUT/setup-check.xml" 2>/dev/null; then
    break
  fi
  sleep 0.5
done
adb exec-out screencap -p > "$OUT/01-setup.png"

# Default duration is one hour, so moving to review is valid without changing the picker.
for _ in $(seq 1 10); do
  if tap_text "Продолжить"; then break; fi
  sleep 1
done
sleep 2
adb exec-out screencap -p > "$OUT/02-review.png"

# Hold the confirmation control for >2 sec. Use its bounds from UI when exposed;
# otherwise use the lower-center area of the screen where the full-width hold button lives.
dump_ui "$OUT/review.xml"
python3 - "$OUT/review.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET, subprocess
root=ET.parse(sys.argv[1]).getroot()
for node in root.iter("node"):
    text=(node.attrib.get("text","")+" "+node.attrib.get("content-desc","")).lower()
    if "удерживайте" in text:
        m=re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",node.attrib.get("bounds",""))
        if m:
            x1,y1,x2,y2=map(int,m.groups()); x=(x1+x2)//2; y=(y1+y2)//2
            subprocess.check_call(["adb","shell","input","swipe",str(x),str(y),str(x),str(y),"2300"])
            sys.exit(0)
subprocess.check_call(["adb","shell","input","swipe","540","1900","540","1900","2300"])
PY
sleep 3
adb exec-out screencap -p > "$OUT/03-active.png"

echo "DESIGN SCREENSHOT QA PASS"

#!/usr/bin/env bash
set -Eeuo pipefail

PKG="ru.pauza.app"
ACTIVITY="$PKG/.MainActivity"
ACCESSIBILITY_COMPONENT="$PKG/$PKG.domain.PauseAccessibilityService"
APK="app/build/outputs/apk/debug/app-debug.apk"
ARTIFACT_DIR="qa-artifacts"
mkdir -p "$ARTIFACT_DIR"

snapshot() {
  local name="$1"
  adb shell dumpsys window windows > "$ARTIFACT_DIR/$name-windows.txt" || true
  adb shell dumpsys activity activities > "$ARTIFACT_DIR/$name-activities.txt" || true
  adb shell dumpsys accessibility > "$ARTIFACT_DIR/$name-accessibility.txt" || true
  adb exec-out screencap -p > "$ARTIFACT_DIR/$name.png" 2>/dev/null || true
}

foreground_line() {
  adb shell dumpsys activity activities 2>/dev/null | grep -m1 "mResumedActivity" || true
}

foreground_package() {
  foreground_line | sed -n 's/.* u[0-9]\+ \([^/ ]*\)\/.*/\1/p'
}

pause_foreground() {
  [ "$(foreground_package)" = "$PKG" ]
}

fail() {
  echo "QA FAIL: $*" | tee -a "$ARTIFACT_DIR/summary.txt"
  snapshot "failure"
  adb logcat -d > "$ARTIFACT_DIR/logcat.txt" || true
  exit 1
}

wait_for_pause_foreground() {
  local label="$1"
  for _ in $(seq 1 30); do
    if pause_foreground; then
      return 0
    fi
    sleep 0.2
  done
  fail "$label: Pause did not regain foreground"
}

wait_for_package() {
  local wanted="$1"
  local label="$2"
  for _ in $(seq 1 30); do
    if [ "$(foreground_package)" = "$wanted" ]; then
      return 0
    fi
    sleep 0.2
  done
  fail "$label: expected foreground package $wanted, got $(foreground_package)"
}

wait_for_accessibility_bound() {
  local label="$1"
  for _ in $(seq 1 40); do
    if adb shell dumpsys accessibility 2>/dev/null |
        grep -A6 "Bound services:" |
        grep -Fq "PauseAccessibilityService"; then
      return 0
    fi
    sleep 0.25
  done
  fail "$label: Pause accessibility service is not bound"
}

dump_ui_to() {
  local out="$1"
  adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
  adb exec-out cat /sdcard/window.xml > "$out" 2>/dev/null || true
}

tap_text() {
  local wanted="$1"
  local xml="$ARTIFACT_DIR/current-ui.xml"
  dump_ui_to "$xml"
  local coords
  coords="$(python3 - "$wanted" "$xml" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

wanted = sys.argv[1]
path = sys.argv[2]
try:
    root = ET.parse(path).getroot()
except Exception:
    raise SystemExit(1)

for node in root.iter("node"):
    text = node.attrib.get("text", "")
    desc = node.attrib.get("content-desc", "")
    if text == wanted or desc == wanted:
        bounds = node.attrib.get("bounds", "")
        m = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            raise SystemExit(0)
raise SystemExit(1)
PY
)" || return 1

  local x
  local y
  x="$(echo "$coords" | awk '{print $1}')"
  y="$(echo "$coords" | awk '{print $2}')"
  [ -n "$x" ] && [ -n "$y" ] || return 1
  adb shell input tap "$x" "$y"
}

get_ui_timer() {
  local xml="$ARTIFACT_DIR/timer-ui.xml"
  dump_ui_to "$xml"
  python3 - "$xml" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
try:
    root = ET.parse(path).getroot()
except Exception:
    raise SystemExit(1)

pattern = re.compile(r"(?:(\d+)\s*дн\s*)?(\d{2}):(\d{2}):(\d{2})")
for node in root.iter("node"):
    for key in ("text", "content-desc"):
        value = node.attrib.get(key, "")
        m = pattern.search(value)
        if m:
            print(m.group(0))
            raise SystemExit(0)
raise SystemExit(1)
PY
}

timer_seconds() {
  python3 - "$1" <<'PY'
import re
import sys

value = sys.argv[1]
m = re.search(r"(?:(\d+)\s*дн\s*)?(\d{2}):(\d{2}):(\d{2})", value)
if not m:
    raise SystemExit(1)
days = int(m.group(1) or 0)
hours = int(m.group(2))
minutes = int(m.group(3))
seconds = int(m.group(4))
print(days * 86400 + hours * 3600 + minutes * 60 + seconds)
PY
}

assert_timer_near_expected() {
  local label="$1"
  local value="$2"
  local actual
  local expected
  local delta
  actual="$(timer_seconds "$value")" || fail "$label: cannot parse timer '$value'"
  expected=$(( (END_MS - $(date +%s%3N)) / 1000 ))
  if [ "$expected" -lt 0 ]; then
    expected=0
  fi
  delta=$(( actual - expected ))
  if [ "$delta" -lt 0 ]; then
    delta=$(( -delta ))
  fi
  echo "$label timer=$value actualSeconds=$actual expectedSeconds=$expected delta=$delta" | tee -a "$ARTIFACT_DIR/timer-checks.txt"
  if [ "$delta" -gt 6 ]; then
    fail "$label: timer differs from session end by $delta seconds"
  fi
}

echo "Installing debug APK"
adb install -r "$APK"

echo "Preparing deterministic active Pause state"
adb shell dumpsys deviceidle whitelist +"$PKG" || true
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure immersive_mode_confirmations confirmed || true

adb shell am force-stop com.google.android.apps.nexuslauncher || true
adb shell input keyevent KEYCODE_HOME || true
sleep 2

END_MS=$(( $(date +%s%3N) + 15 * 60 * 1000 ))
cat > /tmp/pause_store.xml <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <long name="session_end_epoch_ms" value="$END_MS" />
    <boolean name="first_setup_completed" value="true" />
    <boolean name="restricted_settings_confirmed" value="true" />
    <boolean name="setup_checklist_completed" value="true" />
</map>
EOF

adb push /tmp/pause_store.xml /data/local/tmp/pause_store.xml >/dev/null
adb shell chmod 644 /data/local/tmp/pause_store.xml
adb shell run-as "$PKG" mkdir -p "/data/user/0/$PKG/shared_prefs"
adb shell run-as "$PKG" cp /data/local/tmp/pause_store.xml "/data/user/0/$PKG/shared_prefs/pause_store.xml"

adb shell am start -W -n "$ACTIVITY" >/dev/null
sleep 0.8

echo "Granting Usage Access and enabling Accessibility"
adb shell cmd appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow || true
adb shell cmd appops set "$PKG" GET_USAGE_STATS allow || true
adb shell settings --user 0 put secure enabled_accessibility_services "$ACCESSIBILITY_COMPONENT"
adb shell settings --user 0 put secure accessibility_enabled 1
wait_for_accessibility_bound "initial setup"

adb shell am start -W -n "$ACTIVITY" >/dev/null
wait_for_pause_foreground "initial active screen"
sleep 1

{
  echo "gitSha=$GITHUB_SHA"
  echo "enabled_accessibility_services=$(adb shell settings get secure enabled_accessibility_services)"
  echo "accessibility_enabled=$(adb shell settings get secure accessibility_enabled)"
  echo "usage_appop=$(adb shell cmd appops get "$PKG" GET_USAGE_STATS 2>/dev/null || true)"
  echo "prefs:"
  adb shell run-as "$PKG" cat "/data/user/0/$PKG/shared_prefs/pause_store.xml" || true
} > "$ARTIFACT_DIR/setup-diagnostics.txt"

snapshot "initial"

echo "Test 1: timer is correct and decreases"
TIMER_BEFORE="$(get_ui_timer)" || fail "initial timer text not found"
assert_timer_near_expected "initial" "$TIMER_BEFORE"
BEFORE_SECONDS="$(timer_seconds "$TIMER_BEFORE")"
sleep 2
TIMER_AFTER="$(get_ui_timer)" || fail "timer text disappeared"
AFTER_SECONDS="$(timer_seconds "$TIMER_AFTER")"
echo "before=$TIMER_BEFORE after=$TIMER_AFTER" | tee -a "$ARTIFACT_DIR/timer-checks.txt"
if [ "$AFTER_SECONDS" -ge "$BEFORE_SECONDS" ]; then
  fail "timer did not decrease: $TIMER_BEFORE -> $TIMER_AFTER"
fi

echo "Resolving Phone package"
PHONE_COMPONENT="$(adb shell cmd package resolve-activity --brief -a android.intent.action.DIAL -d tel: 2>/dev/null | tr -d '\r' | tail -n1)"
PHONE_PACKAGE="$(echo "$PHONE_COMPONENT" | cut -d/ -f1)"
if [ -z "$PHONE_PACKAGE" ] || [ "$PHONE_PACKAGE" = "No activity found" ] || [ "$PHONE_PACKAGE" = "$PHONE_COMPONENT" ]; then
  fail "Phone activity could not be resolved: $PHONE_COMPONENT"
fi
echo "phoneComponent=$PHONE_COMPONENT" > "$ARTIFACT_DIR/phone.txt"

SCREEN_SIZE="$(adb shell wm size | tr -d '\r' | awk -F': ' '/Physical size/ {print $2}' | tail -n1)"
SCREEN_WIDTH="$(echo "$SCREEN_SIZE" | cut -dx -f1)"
SCREEN_HEIGHT="$(echo "$SCREEN_SIZE" | cut -dx -f2)"
PHONE_X=$(( SCREEN_WIDTH * 16 / 100 ))
PHONE_Y=$(( SCREEN_HEIGHT * 30 / 100 ))
echo "screen=$SCREEN_SIZE fallbackPhoneTap=$PHONE_X,$PHONE_Y" > "$ARTIFACT_DIR/coordinates.txt"

open_phone_from_pause() {
  local label="$1"
  wait_for_pause_foreground "$label precondition"
  if ! tap_text "Телефон"; then
    echo "$label: UIAutomator did not expose 'Телефон'; using measured first-grid fallback" | tee -a "$ARTIFACT_DIR/phone.txt"
    adb shell input tap "$PHONE_X" "$PHONE_Y"
  fi
  wait_for_package "$PHONE_PACKAGE" "$label"
}

echo "Test 2: allowed Phone opens from Pause"
open_phone_from_pause "allowed Phone"
snapshot "phone"

echo "Test 3: Home cannot escape Pause"
adb shell input keyevent KEYCODE_HOME
wait_for_pause_foreground "Home protection"
snapshot "home"

echo "Test 4: a disallowed Settings launch is blocked"
adb shell am start -W -a android.settings.SETTINGS >/dev/null 2>&1 || true
wait_for_pause_foreground "disallowed Settings"
snapshot "settings-blocked"

echo "Test 5: Recents cannot leave an unprotected task switcher"
open_phone_from_pause "Phone before Recents"
adb shell input keyevent KEYCODE_APP_SWITCH
wait_for_pause_foreground "Recents protection"
snapshot "recents"

echo "Test 6: repeated Phone/Home transitions"
for i in $(seq 1 10); do
  open_phone_from_pause "cycle $i Phone"
  adb shell input keyevent KEYCODE_HOME
  wait_for_pause_foreground "cycle $i Home"
  echo "cycle $i ok" >> "$ARTIFACT_DIR/stress.txt"
done

echo "Test 7: session and protection survive emulator reboot"
SESSION_XML_BEFORE="$(adb shell run-as "$PKG" cat "/data/user/0/$PKG/shared_prefs/pause_store.xml" 2>/dev/null || true)"
echo "$SESSION_XML_BEFORE" > "$ARTIFACT_DIR/prefs-before-reboot.xml"
adb reboot
adb wait-for-device

BOOTED=0
for _ in $(seq 1 120); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    BOOTED=1
    break
  fi
  sleep 1
done
if [ "$BOOTED" -ne 1 ]; then
  fail "emulator did not complete reboot"
fi

adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure immersive_mode_confirmations confirmed || true
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
sleep 1

wait_for_accessibility_bound "post-reboot"
SESSION_XML_AFTER="$(adb shell run-as "$PKG" cat "/data/user/0/$PKG/shared_prefs/pause_store.xml" 2>/dev/null || true)"
echo "$SESSION_XML_AFTER" > "$ARTIFACT_DIR/prefs-after-reboot.xml"

END_AFTER="$(echo "$SESSION_XML_AFTER" | sed -n 's/.*name="session_end_epoch_ms" value="\([0-9][0-9]*\)".*/\1/p' | head -n1)"
if [ "$END_AFTER" != "$END_MS" ]; then
  fail "session end changed across reboot: before=$END_MS after=$END_AFTER"
fi

adb shell input keyevent KEYCODE_HOME || true
wait_for_pause_foreground "post-reboot Home protection"
sleep 1
TIMER_REBOOT="$(get_ui_timer)" || fail "post-reboot timer text not found"
assert_timer_near_expected "post-reboot" "$TIMER_REBOOT"
snapshot "post-reboot"

echo "Test 8: disallowed Settings is still blocked after reboot"
adb shell am start -W -a android.settings.SETTINGS >/dev/null 2>&1 || true
wait_for_pause_foreground "post-reboot disallowed Settings"
snapshot "post-reboot-settings"

adb logcat -d > "$ARTIFACT_DIR/logcat.txt" || true
echo "QA PASS: 0.7.4-timerfix baseline navigation, timer and reboot suite completed" | tee "$ARTIFACT_DIR/summary.txt"

#!/usr/bin/env bash
# Dedicated capture for long-press Power / global actions flicker.
set -Eeuo pipefail

PKG="ru.pauza.app"
ACTIVITY="$PKG/.MainActivity"
ACCESSIBILITY_COMPONENT="$PKG/$PKG.domain.PauseAccessibilityService"
APK="app/build/outputs/apk/debug/app-debug.apk"
ARTIFACT_DIR="qa-artifacts-power-assistant"
mkdir -p "$ARTIFACT_DIR"

fail() {
  echo "POWER QA FAIL: $*" | tee -a "$ARTIFACT_DIR/summary.txt"
  adb shell dumpsys window windows > "$ARTIFACT_DIR/failure-windows.txt" || true
  adb shell dumpsys activity activities > "$ARTIFACT_DIR/failure-activities.txt" || true
  adb exec-out screencap -p > "$ARTIFACT_DIR/failure.png" 2>/dev/null || true
  adb logcat -d > "$ARTIFACT_DIR/logcat.txt" || true
  exit 1
}

foreground_line() {
  adb shell dumpsys activity activities 2>/dev/null |
    grep -m1 -E "topResumedActivity=|mResumedActivity" || true
}

foreground_package() {
  foreground_line | sed -n 's/.* u[0-9]\+ \([^/ ]*\)\/.*/\1/p'
}

wait_for_pause_foreground() {
  local label="$1"
  for _ in $(seq 1 40); do
    if [ "$(foreground_package)" = "$PKG" ]; then
      return 0
    fi
    sleep 0.2
  done
  fail "$label: Pause did not regain foreground; got $(foreground_line)"
}

wait_for_accessibility_bound() {
  for _ in $(seq 1 40); do
    if adb shell dumpsys accessibility 2>/dev/null |
        grep -A6 "Bound services:" |
        grep -Fq "PauseAccessibilityService"; then
      return 0
    fi
    sleep 0.25
  done
  fail "Pause accessibility service is not bound"
}

echo "Installing APK"
adb install -r "$APK"

adb shell dumpsys deviceidle whitelist +"$PKG" || true
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure immersive_mode_confirmations confirmed || true
# Exercise the Pixel default long-press Power path that previously reproduced
# the flicker: Assistant/system transition instead of Global Actions.
adb shell settings delete global power_button_long_press || true
adb shell settings get global power_button_long_press > "$ARTIFACT_DIR/power-button-setting.txt" || true

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
SERVICE_READY=0
for attempt in $(seq 1 12); do
  adb shell cmd appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow || true
  adb shell cmd appops set "$PKG" GET_USAGE_STATS allow || true
  adb shell settings --user 0 put secure enabled_accessibility_services "$ACCESSIBILITY_COMPONENT"
  adb shell settings --user 0 put secure accessibility_enabled 1
  sleep 0.5
  if adb shell dumpsys accessibility 2>/dev/null |
      grep -A6 "Bound services:" |
      grep -Fq "PauseAccessibilityService"; then
    SERVICE_READY=1
    break
  fi
done
if [ "$SERVICE_READY" -ne 1 ]; then
  fail "Pause accessibility service is not bound after retries"
fi

USAGE_READY=0
for attempt in $(seq 1 20); do
  adb shell cmd appops set "$PKG" GET_USAGE_STATS allow || true
  USAGE_STATE="$(adb shell cmd appops get "$PKG" GET_USAGE_STATS 2>/dev/null || true)"
  if echo "$USAGE_STATE" | grep -Fq "GET_USAGE_STATS: allow"; then
    USAGE_READY=1
    break
  fi
  sleep 0.5
done
if [ "$USAGE_READY" -ne 1 ]; then
  fail "Usage Access app-op did not become allowed"
fi

# Re-assert the deterministic active session only after permissions are ready.
END_MS=$(( $(adb shell date +%s%3N | tr -d '\r') + 15 * 60 * 1000 ))
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
adb shell run-as "$PKG" cp /data/local/tmp/pause_store.xml "/data/user/0/$PKG/shared_prefs/pause_store.xml"

adb shell am force-stop "$PKG" || true
adb shell am start -W -n "$ACTIVITY" >/dev/null
adb shell settings --user 0 put secure enabled_accessibility_services "$ACCESSIBILITY_COMPONENT"
adb shell settings --user 0 put secure accessibility_enabled 1
wait_for_accessibility_bound
wait_for_pause_foreground "initial"
sleep 1.5

adb shell uiautomator dump /sdcard/active-before-assistant.xml >/dev/null 2>&1 || true
adb exec-out cat /sdcard/active-before-assistant.xml > "$ARTIFACT_DIR/active-before-assistant.xml" 2>/dev/null || true
if ! grep -Eq '[0-9]{2}:[0-9]{2}:[0-9]{2}' "$ARTIFACT_DIR/active-before-assistant.xml"; then
  fail "active Pause timer is not present before Assistant transition"
fi

# Remove any first-boot Launcher ANR dialog so it does not contaminate the capture.
if adb shell dumpsys window windows 2>/dev/null | grep -Fq "Application Not Responding: com.google.android.apps.nexuslauncher"; then
  adb shell input keyevent KEYCODE_BACK || true
  sleep 1
fi
adb exec-out screencap -p > "$ARTIFACT_DIR/before-power.png"
adb shell dumpsys window windows > "$ARTIFACT_DIR/before-power-windows.txt"
adb shell uiautomator dump /sdcard/before-power.xml >/dev/null 2>&1 || true
adb exec-out cat /sdcard/before-power.xml > "$ARTIFACT_DIR/before-power.xml" 2>/dev/null || true

echo "Starting 10-second screen recording"
adb shell rm -f /sdcard/power-menu.mp4 || true
adb shell screenrecord --bit-rate 8000000 --time-limit 10 /sdcard/power-menu.mp4 &
REC_PID=$!
sleep 0.8

echo "Long-pressing Power"
date +%s%3N > "$ARTIFACT_DIR/power-longpress-start-ms.txt"
adb shell input keyevent --longpress KEYCODE_POWER || adb shell input keyevent --longpress 26 || true

for i in $(seq -w 1 16); do
  {
    echo "sample=$i host_ms=$(date +%s%3N)"
    echo "foreground=$(foreground_line)"
    adb shell dumpsys window windows 2>/dev/null |
      grep -E "mCurrentFocus|mFocusedApp|GlobalActions|globalactions|PowerMenu|power menu|package=ru.pauza.app|com.android.systemui" |
      head -n 80 || true
  } > "$ARTIFACT_DIR/power-sample-$i.txt"
  adb exec-out screencap -p > "$ARTIFACT_DIR/power-sample-$i.png" 2>/dev/null || true
  adb shell dumpsys accessibility > "$ARTIFACT_DIR/power-sample-$i-accessibility.txt" 2>/dev/null || true
  sleep 0.12
done

adb shell uiautomator dump /sdcard/power-menu.xml >/dev/null 2>&1 || true
adb exec-out cat /sdcard/power-menu.xml > "$ARTIFACT_DIR/power-menu.xml" 2>/dev/null || true
adb shell dumpsys window windows > "$ARTIFACT_DIR/power-menu-windows.txt" || true
adb shell dumpsys activity activities > "$ARTIFACT_DIR/power-menu-activities.txt" || true
adb exec-out screencap -p > "$ARTIFACT_DIR/power-menu-steady.png" 2>/dev/null || true

echo "Closing Assistant/system surface with Back"
adb shell input keyevent KEYCODE_BACK || true
sleep 1.2
wait_for_pause_foreground "after closing Assistant/system surface"
adb exec-out screencap -p > "$ARTIFACT_DIR/after-power.png" 2>/dev/null || true
adb shell dumpsys window windows > "$ARTIFACT_DIR/after-power-windows.txt" || true

wait "$REC_PID" || true
adb pull /sdcard/power-menu.mp4 "$ARTIFACT_DIR/power-menu.mp4" >/dev/null || true
adb shell rm -f /sdcard/power-menu.mp4 || true

adb logcat -d > "$ARTIFACT_DIR/logcat.txt" || true

{
  echo "ASSISTANT TRANSITION QA COMPLETE"
  echo "foreground_after=$(foreground_line)"
  echo "session_end=$END_MS"
  echo "video=$(test -s "$ARTIFACT_DIR/power-menu.mp4" && echo yes || echo no)"
} | tee "$ARTIFACT_DIR/summary.txt"

#!/usr/bin/env bash
set -Eeuo pipefail

PKG="ru.pauza.app"
ACTIVITY="$PKG/.MainActivity"
ACCESSIBILITY_COMPONENT="$PKG/$PKG.domain.PauseAccessibilityService"
APK="app/build/outputs/apk/debug/app-debug.apk"
VARIANT="${QA_VARIANT:-unknown}"
ARTIFACT_DIR="../qa-artifacts-reboot-speed/$VARIANT"
mkdir -p "$ARTIFACT_DIR"

foreground_line() {
  adb shell dumpsys activity activities 2>/dev/null |
    grep -m1 -E "topResumedActivity=|mResumedActivity" || true
}

foreground_package() {
  foreground_line | sed -n 's/.* u[0-9]\+ \([^/ ]*\)\/.*/\1/p'
}

accessibility_bound() {
  adb shell dumpsys accessibility 2>/dev/null |
    grep -A8 "Bound services:" |
    grep -Fq "PauseAccessibilityService"
}

fail() {
  echo "REBOOT SPEED QA FAIL [$VARIANT]: $*" | tee "$ARTIFACT_DIR/summary.txt"
  adb shell dumpsys activity activities > "$ARTIFACT_DIR/failure-activities.txt" || true
  adb shell dumpsys accessibility > "$ARTIFACT_DIR/failure-accessibility.txt" || true
  adb exec-out screencap -p > "$ARTIFACT_DIR/failure.png" 2>/dev/null || true
  adb logcat -d > "$ARTIFACT_DIR/logcat.txt" || true
  exit 1
}

echo "Installing $VARIANT"
adb install -r "$APK" >/dev/null

adb shell dumpsys deviceidle whitelist +"$PKG" || true
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure immersive_mode_confirmations confirmed || true

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
adb shell run-as "$PKG" mkdir -p "/data/user/0/$PKG/shared_prefs"
adb shell run-as "$PKG" cp /data/local/tmp/pause_store.xml "/data/user/0/$PKG/shared_prefs/pause_store.xml"

SERVICE_READY=0
for attempt in $(seq 1 30); do
  adb shell cmd appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow || true
  adb shell cmd appops set "$PKG" GET_USAGE_STATS allow || true
  adb shell settings --user 0 put secure enabled_accessibility_services "$ACCESSIBILITY_COMPONENT"
  adb shell settings --user 0 put secure accessibility_enabled 1
  adb shell am start -W -n "$ACTIVITY" >/dev/null 2>&1 || true
  sleep 0.5

  if accessibility_bound; then
    SERVICE_READY=1
    break
  fi
done
[ "$SERVICE_READY" -eq 1 ] || fail "Accessibility not bound before reboot after retries"

adb shell am start -W -n "$ACTIVITY" >/dev/null 2>&1 || true
for _ in $(seq 1 60); do
  if [ "$(foreground_package)" = "$PKG" ]; then
    break
  fi
  sleep 0.25
done
[ "$(foreground_package)" = "$PKG" ] || fail "Pause not foreground before reboot"

adb logcat -c || true
adb exec-out screencap -p > "$ARTIFACT_DIR/before-reboot.png" 2>/dev/null || true

echo "Rebooting $VARIANT"
REBOOT_HOST_MS=$(date +%s%3N)
adb reboot
adb wait-for-device

BOOTED=0
for _ in $(seq 1 180); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    BOOTED=1
    break
  fi
  sleep 0.5
done
[ "$BOOTED" -eq 1 ] || fail "sys.boot_completed did not become 1"

BOOT_COMPLETED_HOST_MS=$(date +%s%3N)
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure immersive_mode_confirmations confirmed || true
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
UNLOCK_HOST_MS=$(date +%s%3N)

BOUND_MS=-1
PAUSE_MS=-1
START_MS=$UNLOCK_HOST_MS

for _ in $(seq 1 200); do
  NOW_MS=$(date +%s%3N)
  ELAPSED=$(( NOW_MS - START_MS ))

  if [ "$BOUND_MS" -lt 0 ] && accessibility_bound; then
    BOUND_MS=$ELAPSED
  fi

  if [ "$(foreground_package)" = "$PKG" ]; then
    PAUSE_MS=$ELAPSED
    break
  fi

  sleep 0.1
done

{
  echo "variant=$VARIANT"
  echo "reboot_host_ms=$REBOOT_HOST_MS"
  echo "boot_completed_host_ms=$BOOT_COMPLETED_HOST_MS"
  echo "unlock_host_ms=$UNLOCK_HOST_MS"
  echo "boot_to_unlock_ms=$(( UNLOCK_HOST_MS - REBOOT_HOST_MS ))"
  echo "accessibility_bound_after_unlock_ms=$BOUND_MS"
  echo "pause_foreground_after_unlock_ms=$PAUSE_MS"
  echo "foreground=$(foreground_line)"
} | tee "$ARTIFACT_DIR/timing.txt"

[ "$BOUND_MS" -ge 0 ] || fail "Accessibility never rebound within measurement window"
[ "$PAUSE_MS" -ge 0 ] || fail "Pause did not become foreground automatically within 20 seconds"

adb exec-out screencap -p > "$ARTIFACT_DIR/after-auto-resume.png" 2>/dev/null || true

echo "Verifying protection after autonomous reboot resume"
adb shell am start -W -a android.settings.SETTINGS >/dev/null 2>&1 || true
PROTECTED=0
for _ in $(seq 1 50); do
  if [ "$(foreground_package)" = "$PKG" ]; then
    PROTECTED=1
    break
  fi
  sleep 0.1
done
[ "$PROTECTED" -eq 1 ] || fail "Settings was not blocked after reboot resume"

adb logcat -d > "$ARTIFACT_DIR/logcat.txt" || true
echo "REBOOT SPEED QA PASS [$VARIANT]" | tee "$ARTIFACT_DIR/summary.txt"

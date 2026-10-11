#!/usr/bin/env bash
set -Eeuo pipefail

PKG=ru.pauza.app
ACTIVITY="$PKG/.MainActivity"
COMPONENT="$PKG/$PKG.domain.PauseAccessibilityService"
ARTIFACT_DIR=qa-artifacts/health-visual
mkdir -p "$ARTIFACT_DIR"

snapshot() {
  local name="$1"
  adb shell dumpsys accessibility > "$ARTIFACT_DIR/$name-accessibility.txt" || true
  adb shell dumpsys activity activities > "$ARTIFACT_DIR/$name-activities.txt" || true
  adb exec-out screencap -p > "$ARTIFACT_DIR/$name.png" || true
  for try in 1 2 3; do
    if adb shell uiautomator dump /sdcard/pauza-health-ui.xml >/dev/null 2>&1 &&
       adb shell cat /sdcard/pauza-health-ui.xml > "$ARTIFACT_DIR/$name.xml"; then
      break
    fi
    sleep 1
  done
}

contains_text() {
  local text="$1"
  adb shell uiautomator dump /sdcard/pauza-health-check.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/pauza-health-check.xml 2>/dev/null | grep -Fq "$text"
}

wait_for_text() {
  local desired="$1"
  for try in $(seq 1 16); do
    if contains_text "$desired"; then return 0; fi
    sleep 1
  done
  return 1
}

tap_ui_text() {
  local wanted="$1"
  adb shell uiautomator dump /sdcard/pauza-tap.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/pauza-tap.xml > /tmp/pauza-tap.xml
  local coords
  coords="$(python3 - "$wanted" <<'PY'
import re, sys, xml.etree.ElementTree as ET
wanted = sys.argv[1]
root = ET.parse('/tmp/pauza-tap.xml').getroot()
for node in root.iter('node'):
    if node.attrib.get('text') != wanted and node.attrib.get('content-desc') != wanted:
        continue
    match = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.attrib.get('bounds',''))
    if match:
        x1,y1,x2,y2 = map(int, match.groups())
        print((x1+x2)//2, (y1+y2)//2)
        raise SystemExit(0)
raise SystemExit(1)
PY
)" || fail "Cannot tap UI element: $wanted"
  adb shell input tap $coords
}

fail() {
  echo "FAIL: $*" | tee -a "$ARTIFACT_DIR/summary.txt"
  snapshot failure
  adb logcat -d > "$ARTIFACT_DIR/logcat.txt" || true
  exit 1
}

echo "Install Pause debug build"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure immersive_mode_confirmations confirmed || true

# Install the test prefs before launching the process. PauseStore caches
# SharedPreferences in memory; changing its backing file after launch would
# incorrectly leave the first-run setup state active.
END_MS="$(( $(date +%s%3N) + 10 * 60 * 1000 ))"
cat > /tmp/pause-health-prefs.xml <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <set name="selected_packages"><string>com.android.settings</string></set>
    <long name="session_end_epoch_ms" value="$END_MS" />
    <boolean name="first_setup_completed" value="true" />
    <boolean name="restricted_settings_confirmed" value="true" />
    <boolean name="setup_checklist_completed" value="true" />
    <boolean name="block_short_videos" value="false" />
</map>
EOF
adb push /tmp/pause-health-prefs.xml /data/local/tmp/pause-health-prefs.xml >/dev/null
adb shell chmod 644 /data/local/tmp/pause-health-prefs.xml
adb shell run-as "$PKG" mkdir -p "/data/user/0/$PKG/shared_prefs"
adb shell run-as "$PKG" cp /data/local/tmp/pause-health-prefs.xml "/data/user/0/$PKG/shared_prefs/pause_store.xml"

# First launch un-stops the package with the intended preferences already loaded.
adb shell am start -W -n "$ACTIVITY" >/dev/null
sleep 1

adb shell cmd appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow || true
adb shell cmd appops set "$PKG" GET_USAGE_STATS allow || true
adb shell cmd appops set "$PKG" SYSTEM_ALERT_WINDOW allow || true

echo "Enable accessibility and establish healthy baseline"
READY=0
for try in $(seq 1 15); do
  adb shell settings --user 0 put secure enabled_accessibility_services "$COMPONENT"
  adb shell settings --user 0 put secure accessibility_enabled 1
  sleep 1
  if adb shell dumpsys accessibility | grep -A3 'Bound services:' | grep -Fq 'label=Пауза'; then
    READY=1
    break
  fi
done
[ "$READY" -eq 1 ] || fail "Accessibility failed to bind"
adb shell am start -W -n "$ACTIVITY" >/dev/null
wait_for_text 'Доступны только выбранные приложения' || fail 'No active Pause screen at baseline'
sleep 2
snapshot healthy
if contains_text 'Служба блокировки отключена' || contains_text 'Служба блокировки не отвечает'; then
  fail 'False service failure while bound and responsive'
fi
echo "PASS: healthy service shows active Pause without false warning" | tee "$ARTIFACT_DIR/summary.txt"

echo "Disable accessibility during an active session"
adb shell settings --user 0 delete secure enabled_accessibility_services
adb shell settings --user 0 put secure accessibility_enabled 0
adb shell am start -W -n "$ACTIVITY" >/dev/null
wait_for_text 'Служба блокировки отключена' || fail 'Missing visible failure warning after disabling accessibility'
wait_for_text 'Открыть настройки' || fail 'Missing settings recovery action'
snapshot disabled
if ! contains_text 'Доступны только выбранные приложения'; then
  fail 'Active screen was replaced by initial setup screen'
fi
echo "PASS: disabled service displays warning and preserves active session" | tee -a "$ARTIFACT_DIR/summary.txt"

echo "Re-enable accessibility and verify banner is removed"
READY=0
for try in $(seq 1 15); do
  adb shell cmd appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow || true
  adb shell settings --user 0 put secure enabled_accessibility_services "$COMPONENT"
  adb shell settings --user 0 put secure accessibility_enabled 1
  sleep 1
  if adb shell dumpsys accessibility | grep -A3 'Bound services:' | grep -Fq 'label=Пауза'; then
    READY=1
    break
  fi
done
[ "$READY" -eq 1 ] || fail "Accessibility did not recover"
adb shell am start -W -n "$ACTIVITY" >/dev/null
for try in $(seq 1 16); do
  if ! contains_text 'Служба блокировки отключена' && ! contains_text 'Служба блокировки не отвечает'; then
    break
  fi
  sleep 1
done
snapshot recovered
contains_text 'Доступны только выбранные приложения' || fail 'Active Pause not restored'
if contains_text 'Служба блокировки отключена' || contains_text 'Служба блокировки не отвечает'; then
  fail 'Warning did not disappear after recovery'
fi

echo "PASS: accessibility recovered; visual warning automatically removed" | tee -a "$ARTIFACT_DIR/summary.txt"

echo "Test: accessibility disabled when no active Pause session"
adb shell settings --user 0 delete secure enabled_accessibility_services
adb shell settings --user 0 put secure accessibility_enabled 0
adb shell am force-stop "$PKG"
sed '/name="session_end_epoch_ms"/d' /tmp/pause-health-prefs.xml > /tmp/pause-idle-prefs.xml
adb push /tmp/pause-idle-prefs.xml /data/local/tmp/pause-idle-prefs.xml >/dev/null
adb shell chmod 644 /data/local/tmp/pause-idle-prefs.xml
adb shell run-as "$PKG" cp /data/local/tmp/pause-idle-prefs.xml "/data/user/0/$PKG/shared_prefs/pause_store.xml"
adb shell am start -W -n "$ACTIVITY" >/dev/null
wait_for_text 'Защита недоступна' || fail 'Idle Pause did not show automatic health warning'
wait_for_text 'Специальные возможности отключены' || fail 'Idle warning did not explain accessibility failure'
if contains_text 'Настройка Паузы'; then
  fail 'Idle Pause wrongly opened first-run setup after accessibility was disabled'
fi
snapshot idle-accessibility-disabled
echo "PASS: no-session accessibility failure shown on main screen (not onboarding)" | tee -a "$ARTIFACT_DIR/summary.txt"

echo "Test: adaptive onboarding with restricted AppOps and cached acknowledgement"
adb shell am force-stop "$PKG"
sed 's/name="setup_checklist_completed" value="true"/name="setup_checklist_completed" value="false"/' /tmp/pause-idle-prefs.xml > /tmp/pause-onboarding-prefs.xml
adb push /tmp/pause-onboarding-prefs.xml /data/local/tmp/pause-onboarding-prefs.xml >/dev/null
adb shell chmod 644 /data/local/tmp/pause-onboarding-prefs.xml
adb shell run-as "$PKG" cp /data/local/tmp/pause-onboarding-prefs.xml "/data/user/0/$PKG/shared_prefs/pause_store.xml"
adb shell cmd appops set "$PKG" ACCESS_RESTRICTED_SETTINGS ignore
adb shell am start -W -n "$ACTIVITY" >/dev/null
wait_for_text 'Настройка Паузы' || fail 'Onboarding wizard did not open'
wait_for_text 'Специальные возможности' || fail 'Accessibility not offered as the first permission'
if contains_text 'Системное разрешение подтверждено'; then
  fail 'Old user acknowledgement was shown as verified restricted permission'
fi
if contains_text 'Разрешить запрещённые настройки'; then
  fail 'Restricted settings were presented as mandatory before trying accessibility'
fi
snapshot onboarding-before-attempt

tap_ui_text 'Открыть специальные возможности'
sleep 2
adb shell input keyevent KEYCODE_BACK
wait_for_text 'Если Android не даёт включить службу' || fail 'Troubleshooting guide did not appear after trying accessibility'
adb shell input swipe 550 1700 550 900 400
wait_for_text 'Сведения о приложении' || fail 'App info navigation missing from restricted-settings guide'
snapshot onboarding-help
echo "PASS: onboarding starts with accessibility and reveals conditional restricted-settings instructions" | tee -a "$ARTIFACT_DIR/summary.txt"

adb logcat -d > "$ARTIFACT_DIR/logcat.txt" || true

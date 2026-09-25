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
  adb shell dumpsys window windows > "$ARTIFACT_DIR/${name}-windows.txt" || true
  adb shell dumpsys activity activities > "$ARTIFACT_DIR/${name}-activities.txt" || true
  adb shell dumpsys accessibility > "$ARTIFACT_DIR/${name}-accessibility.txt" || true
  adb exec-out screencap -p > "$ARTIFACT_DIR/${name}.png" 2>/dev/null || true
}

pause_visible() {
  adb shell dumpsys window windows 2>/dev/null |
    awk -v pkg="$PKG" '
      /^  Window #[0-9]+ / {
        if (block ~ ("package=" pkg) && block ~ /ty=APPLICATION_OVERLAY/) found=1
        block=""
      }
      { block = block $0 "\n" }
      END {
        if (block ~ ("package=" pkg) && block ~ /ty=APPLICATION_OVERLAY/) found=1
        exit(found ? 0 : 1)
      }
    '
}

foreground_line() {
  adb shell dumpsys activity activities 2>/dev/null | grep -m1 "mResumedActivity" || true
}

fail() {
  echo "QA FAIL: $*" | tee -a "$ARTIFACT_DIR/summary.txt"
  snapshot "failure"
  adb logcat -d > "$ARTIFACT_DIR/logcat.txt" || true
  exit 1
}

wait_for_pause_visible() {
  local label="$1"
  for _ in $(seq 1 12); do
    if pause_visible; then
      return 0
    fi
    sleep 0.25
  done
  fail "$label: Pause overlay did not appear"
}

wait_for_pause_hidden() {
  local label="$1"
  for _ in $(seq 1 12); do
    if ! pause_visible; then
      return 0
    fi
    sleep 0.25
  done
  fail "$label: Pause overlay did not disappear for allowed app"
}

echo "Installing debug APK"
adb install -r "$APK"

echo "Preparing QA permissions"
adb shell dumpsys deviceidle whitelist +"$PKG" || true

END_MS="$(( $(date +%s%3N) + 15 * 60 * 1000 ))"
cat > /tmp/pause_store.xml <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <set name="selected_packages">
        <string>com.android.settings</string>
    </set>
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

# A freshly installed package is in Android's STOPPED state. Accessibility
# services from a stopped package will not bind and Android may clear the
# enabled-services setting. Launch once before enabling the service.
echo "Unstopping Pause package"
adb shell am start -W -n "$ACTIVITY" >/dev/null
sleep 0.7

echo "Granting special-access app-ops"
adb shell cmd appops set "$PKG" GET_USAGE_STATS allow
adb shell cmd appops set "$PKG" SYSTEM_ALERT_WINDOW allow

echo "Enabling Pause accessibility service"
adb shell settings --user 0 put secure enabled_accessibility_services "$ACCESSIBILITY_COMPONENT"
adb shell settings --user 0 put secure accessibility_enabled 1

echo "Waiting for Pause accessibility service"
SERVICE_READY=0
for _ in $(seq 1 30); do
  if adb shell dumpsys accessibility 2>/dev/null | grep -Fq "PauseAccessibilityService"; then
    SERVICE_READY=1
    break
  fi
  sleep 0.25
done

{
  echo "enabled_accessibility_services=$(adb shell settings get secure enabled_accessibility_services)"
  echo "accessibility_enabled=$(adb shell settings get secure accessibility_enabled)"
  echo "overlay_appop=$(adb shell cmd appops get "$PKG" SYSTEM_ALERT_WINDOW 2>/dev/null || true)"
  echo "usage_appop=$(adb shell cmd appops get "$PKG" GET_USAGE_STATS 2>/dev/null || true)"
  echo "prefs:"
  adb shell run-as "$PKG" cat "/data/user/0/$PKG/shared_prefs/pause_store.xml" 2>/dev/null || true
  echo "accessibility excerpt:"
  adb shell dumpsys accessibility 2>/dev/null | grep -A8 -B4 "PauseAccessibilityService" || true
} > "$ARTIFACT_DIR/setup-diagnostics.txt"

if [ "$SERVICE_READY" -ne 1 ]; then
  fail "Pause accessibility service did not become active"
fi

echo "Starting active Pause"
adb shell am start -W -n "$ACTIVITY" >/dev/null
sleep 1

echo "=== QA diagnostics before first assertion ==="
adb shell run-as "$PKG" cat "/data/user/0/$PKG/shared_prefs/pause_store.xml" || true
adb shell settings get secure enabled_accessibility_services || true
adb shell settings get secure accessibility_enabled || true
adb shell appops get "$PKG" GET_USAGE_STATS SYSTEM_ALERT_WINDOW || true
adb shell dumpsys accessibility | grep -A 12 -B 4 -F "$PKG" || true
adb shell dumpsys window windows | grep -A 16 -B 4 -F "$PKG" || true
foreground_line || true
echo "=== end diagnostics ==="

wait_for_pause_visible "initial launcher protection"
snapshot "initial"

echo "Test 1: allowed app opens and overlay leaves"
adb shell am start -W -a android.settings.SETTINGS >/dev/null
wait_for_pause_hidden "allowed Settings"
foreground_line | tee "$ARTIFACT_DIR/test1-foreground.txt"

echo "Test 2: Home returns to protected Pause"
adb shell input keyevent KEYCODE_HOME
wait_for_pause_visible "Home"
snapshot "home"

echo "Test 3: Recents cannot expose task switcher"
adb shell am start -W -a android.settings.SETTINGS >/dev/null
wait_for_pause_hidden "Settings before Recents"
adb shell input keyevent KEYCODE_APP_SWITCH
sleep 0.8
if pause_visible; then
  echo "Recents result: Pause overlay visible" | tee "$ARTIFACT_DIR/test3-result.txt"
else
  FG="$(foreground_line)"
  echo "$FG" | tee "$ARTIFACT_DIR/test3-result.txt"
  if ! echo "$FG" | grep -Fq "com.android.settings"; then
    fail "Recents left an unprotected non-allowed surface: $FG"
  fi
fi

echo "Test 4: Back cannot escape to launcher"
adb shell am start -W -a android.settings.SETTINGS >/dev/null
wait_for_pause_hidden "Settings before Back"
adb shell input keyevent KEYCODE_BACK
sleep 0.7
if ! pause_visible; then
  FG="$(foreground_line)"
  if ! echo "$FG" | grep -Fq "com.android.settings"; then
    fail "Back escaped without Pause protection: $FG"
  fi
fi

echo "Test 5: 30-cycle allowed-app/Home stress"
for i in $(seq 1 30); do
  adb shell am start -a android.settings.SETTINGS >/dev/null
  sleep 0.25
  if pause_visible; then
    fail "stress cycle $i: overlay blocked allowed Settings"
  fi

  adb shell input keyevent KEYCODE_HOME
  sleep 0.35
  if ! pause_visible; then
    fail "stress cycle $i: Home remained unprotected"
  fi

  echo "cycle $i ok" >> "$ARTIFACT_DIR/stress.txt"
done

echo "Test 6: repeated Back transitions"
for i in $(seq 1 15); do
  adb shell am start -a android.settings.SETTINGS >/dev/null
  sleep 0.3
  if pause_visible; then
    fail "Back cycle $i: overlay blocked Settings before Back"
  fi

  adb shell input keyevent KEYCODE_BACK
  sleep 0.6

  if ! pause_visible; then
    FG="$(foreground_line)"
    if ! echo "$FG" | grep -Fq "com.android.settings"; then
      fail "Back cycle $i escaped without protection: $FG"
    fi
  fi

  adb shell input keyevent KEYCODE_HOME
  sleep 0.35
  if ! pause_visible; then
    fail "Back cycle $i: Home after Back remained unprotected"
  fi
  echo "back-cycle $i ok" >> "$ARTIFACT_DIR/back-stress.txt"
done

echo "Test 7: long-running stability for allowed app"
adb shell am start -a android.settings.SETTINGS >/dev/null
wait_for_pause_hidden "Settings before soak"
for i in $(seq 1 20); do
  sleep 0.5
  if pause_visible; then
    fail "allowed Settings became blocked during soak at sample $i"
  fi
done

snapshot "final"
adb logcat -d > "$ARTIFACT_DIR/logcat.txt" || true
echo "QA PASS: navigation and stability suite completed" | tee "$ARTIFACT_DIR/summary.txt"

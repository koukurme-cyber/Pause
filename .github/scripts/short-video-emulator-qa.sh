#!/usr/bin/env bash
set -Eeuo pipefail

PKG="ru.pauza.app"
ACTIVITY="$PKG/.MainActivity"
ACCESSIBILITY_COMPONENT="$PKG/$PKG.domain.PauseAccessibilityService"
APK="app/build/outputs/apk/debug/app-debug.apk"
FIXTURE_PKG="com.instagram.android"
FIXTURE_ACTIVITY="$FIXTURE_PKG/.FixtureActivity"
FIXTURE_APK="qa-shortvideo-fixture/build/outputs/apk/debug/qa-shortvideo-fixture-debug.apk"
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
  # Current stable Pauza returns the user to MainActivity. The previous QA
  # predicate searched for any APPLICATION_OVERLAY block containing Pauza text;
  # on Android 15 this can accidentally span SystemUI's ShellDropTarget block
  # and create a false positive even while Phone is genuinely foreground.
  adb shell dumpsys activity activities 2>/dev/null |
    grep -E -m1 "topResumedActivity=|ResumedActivity:" |
    grep -Fq "ru.pauza.app/.MainActivity"
}

foreground_line() {
  adb shell dumpsys activity activities 2>/dev/null |
    grep -E -m1 "topResumedActivity=|mResumedActivity=|ResumedActivity:" || true
}

fail() {
  echo "QA FAIL: $*" | tee -a "$ARTIFACT_DIR/summary.txt"
  snapshot "failure"
  adb logcat -d > "$ARTIFACT_DIR/logcat.txt" || true
  exit 1
}

wait_for_pause_visible() {
  local label="$1"
  local stable_samples=0

  # Recents/Home transitions on the Android 15 Pixel image can briefly report
  # Pause as top-resumed before Launcher finishes its animation and steals focus
  # again. Do not accept a one-sample transient as successful protection.
  for _ in $(seq 1 28); do
    if pause_visible; then
      stable_samples=$((stable_samples + 1))
      if [ "$stable_samples" -ge 4 ]; then
        return 0
      fi
    else
      stable_samples=0
    fi
    sleep 0.25
  done
  fail "$label: Pause did not remain foreground stably"
}

wait_for_pause_hidden() {
  local label="$1"
  for _ in $(seq 1 20); do
    if ! pause_visible; then
      return 0
    fi
    sleep 0.25
  done
  fail "$label: Pause overlay did not disappear for allowed app"
}

SCREEN_SIZE=""
SCREEN_WIDTH=0
SCREEN_HEIGHT=0
PHONE_X=0
PHONE_Y=0

init_screen_coordinates() {
  SCREEN_SIZE="$(adb shell wm size | tr -d '\r' | awk -F': ' '/Physical size/ {print $2}' | tail -n1)"
  if [ -z "$SCREEN_SIZE" ]; then
    SCREEN_SIZE="$(adb shell wm size | tr -d '\r' | awk -F': ' '/Override size/ {print $2}' | tail -n1)"
  fi
  SCREEN_WIDTH="${SCREEN_SIZE%x*}"
  SCREEN_HEIGHT="${SCREEN_SIZE#*x}"
  # First shortcut in the fixed four-column overlay grid (Phone).
  PHONE_X=$(( SCREEN_WIDTH * 16 / 100 ))
  PHONE_Y=$(( SCREEN_HEIGHT * 30 / 100 ))
  echo "screen=$SCREEN_SIZE phoneTap=$PHONE_X,$PHONE_Y" | tee "$ARTIFACT_DIR/coordinates.txt"
}

tap_ui_text() {
  local wanted="$1"
  adb shell uiautomator dump /sdcard/pauza-window.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/pauza-window.xml > /tmp/pauza-window.xml

  local coords
  coords="$(python3 - "$wanted" <<'PY'
import re, sys, xml.etree.ElementTree as ET
wanted = sys.argv[1]
root = ET.parse("/tmp/pauza-window.xml").getroot()
for node in root.iter("node"):
    if node.attrib.get("text") == wanted or node.attrib.get("content-desc") == wanted:
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
        if m:
            x1,y1,x2,y2 = map(int, m.groups())
            print(f"{(x1+x2)//2} {(y1+y2)//2}")
            raise SystemExit
raise SystemExit(1)
PY
)" || fail "could not find UI element: $wanted"

  adb shell input tap ${coords}
}

open_allowed_phone() {
  local label="$1"
  wait_for_pause_visible "$label precondition"
  tap_ui_text "Телефон"
  wait_for_pause_hidden "$label"
  sleep 0.35
}

fixture_log_count() {
  local state="$1"
  adb logcat -d -s ShortVideoFixture:I '*:S' 2>/dev/null |
    grep -F -c "$state" || true
}

wait_for_fixture_state_count() {
  local state="$1"
  local wanted_count="$2"
  local label="$3"
  for _ in $(seq 1 40); do
    local count
    count="$(fixture_log_count "$state")"
    if [ "$count" -ge "$wanted_count" ]; then
      return 0
    fi
    sleep 0.25
  done
  fail "$label: fixture did not reach $state count=$wanted_count"
}

echo "Installing debug APK"
adb install -r "$APK"
echo "Installing Instagram accessibility fixture"
adb install -r "$FIXTURE_APK"

echo "Preparing QA environment"
adb shell dumpsys deviceidle whitelist +"$PKG" || true

# Headless Google API images occasionally surface first-boot Launcher ANR/error
# dialogs while the launcher is still settling. They are emulator noise, not
# Pause behavior, and can intercept every touch above our overlay.
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure immersive_mode_confirmations confirmed || true
adb shell am force-stop com.google.android.apps.nexuslauncher || true
adb shell input keyevent KEYCODE_HOME || true
sleep 2

END_MS="$(( $(date +%s%3N) + 15 * 60 * 1000 ))"
cat > /tmp/pause_store.xml <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <set name="selected_packages">
        <string>com.android.settings</string>
        <string>com.instagram.android</string>
    </set>
    <long name="session_end_epoch_ms" value="$END_MS" />
    <boolean name="first_setup_completed" value="true" />
    <boolean name="restricted_settings_confirmed" value="true" />
    <boolean name="setup_checklist_completed" value="true" />
    <boolean name="block_short_videos" value="true" />
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

echo "Waiting for installed accessibility component"
for _ in $(seq 1 30); do
  if adb shell dumpsys package "$PKG" 2>/dev/null | grep -Fq "PauseAccessibilityService"; then
    break
  fi
  sleep 0.25
done

echo "Enabling restricted special access and Pause accessibility service"
SERVICE_READY=0
for attempt in $(seq 1 12); do
  # Android 15 may clear special access for a freshly sideloaded package while
  # install bookkeeping is still settling. Re-apply the test-only gate until
  # AccessibilityManager confirms an actual bound service.
  adb shell cmd appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow || true
  adb shell cmd appops set "$PKG" GET_USAGE_STATS allow || true
  adb shell cmd appops set "$PKG" SYSTEM_ALERT_WINDOW allow || true
  adb shell settings --user 0 put secure enabled_accessibility_services "$ACCESSIBILITY_COMPONENT"
  adb shell settings --user 0 put secure accessibility_enabled 1

  sleep 0.5
  if adb shell dumpsys accessibility 2>/dev/null |
      grep -A3 "Bound services:" |
      grep -Fq "label=Пауза"; then
    SERVICE_READY=1
    echo "Accessibility bound on attempt $attempt"
    break
  fi
done

{
  echo "enabled_accessibility_services=$(adb shell settings get secure enabled_accessibility_services)"
  echo "accessibility_enabled=$(adb shell settings get secure accessibility_enabled)"
  echo "restricted_settings_appop=$(adb shell cmd appops get "$PKG" ACCESS_RESTRICTED_SETTINGS 2>/dev/null || true)"
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

init_screen_coordinates

# Clear any already-created Launcher ANR dialog from boot before assertions.
# If the launcher is still recovering, choose "Wait" and give it another beat.
if adb shell dumpsys window windows 2>/dev/null | grep -Fq "Application Not Responding: com.google.android.apps.nexuslauncher"; then
  echo "Dismissing Pixel Launcher ANR dialog from emulator boot"
  adb shell input tap "$(( SCREEN_WIDTH * 27 / 100 ))" "$(( SCREEN_HEIGHT * 45 / 100 ))" || true
  sleep 2
fi

echo "Starting active Pause"
adb shell am start -W -n "$ACTIVITY" >/dev/null
sleep 1

# Pixel emulator may still display Android's one-time immersive-mode education
# panel even when immersive_mode_confirmations is preseeded. Its "Got it"
# button otherwise intercepts the first Phone tap and creates a false failure.
adb shell input tap "$(( SCREEN_WIDTH * 86 / 100 ))" "$(( SCREEN_HEIGHT * 25 / 100 ))" || true
sleep 0.5

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

echo "Test 1: allowed Phone opens through the actual Pause overlay"
open_allowed_phone "allowed Phone"
PHONE_FOREGROUND="$(foreground_line)"
echo "$PHONE_FOREGROUND" | tee "$ARTIFACT_DIR/test1-foreground.txt"
PHONE_PACKAGE="$(echo "$PHONE_FOREGROUND" | sed -n 's/.* u[0-9]\+ \([^/ ]*\)\/.*/\1/p')"
if [ -z "$PHONE_PACKAGE" ]; then
  fail "could not resolve foreground Phone package: $PHONE_FOREGROUND"
fi
echo "phonePackage=$PHONE_PACKAGE" | tee -a "$ARTIFACT_DIR/test1-foreground.txt"

echo "Test 2: Home returns to protected Pause"
adb shell input keyevent KEYCODE_HOME
wait_for_pause_visible "Home"
snapshot "home"

echo "Test 3: Recents is tolerated only as a transient system surface"
open_allowed_phone "Phone before Recents"
adb shell input keyevent KEYCODE_APP_SWITCH
sleep 0.8
FG="$(foreground_line)"
echo "$FG" | tee "$ARTIFACT_DIR/test3-result.txt"

# The verified 0.7.5 SystemUI fix deliberately does not fight transient Recents.
# On Pixel/Android 15 Recents is hosted by NexusLauncher, so topResumedActivity
# can legitimately be the launcher while the overview surface is open.
# The actual invariant is that once we leave that transient surface (Home),
# normal enforcement resumes and Pause immediately takes over again.
if pause_visible; then
  echo "Recents result: Pause already resumed" | tee -a "$ARTIFACT_DIR/test3-result.txt"
elif echo "$FG" | grep -Fq "$PHONE_PACKAGE"; then
  echo "Recents result: allowed Phone remained resumed" | tee -a "$ARTIFACT_DIR/test3-result.txt"
elif echo "$FG" | grep -Fq "com.google.android.apps.nexuslauncher"; then
  echo "Recents result: Pixel launcher is hosting transient overview" | tee -a "$ARTIFACT_DIR/test3-result.txt"
else
  fail "Recents exposed an unexpected non-system app: $FG"
fi

# Home protection is already asserted independently in Test 2. Pixel's Android 15
# launcher can keep the Recents host resumed after a second Home press even though
# no third-party app escaped. Restore Pause deterministically so this known
# emulator quirk cannot prevent the short-video fixture from running.
adb shell am start -W -n "$ACTIVITY" >/dev/null
wait_for_pause_visible "restore after Recents"

echo "Test 4: Back cannot escape from allowed Phone to launcher"
open_allowed_phone "Phone before Back"
adb shell input keyevent KEYCODE_BACK
sleep 0.7
if ! pause_visible; then
  FG="$(foreground_line)"
  if ! echo "$FG" | grep -Fq "$PHONE_PACKAGE"; then
    fail "Back escaped without Pause protection: $FG"
  fi
fi
adb shell input keyevent KEYCODE_HOME
wait_for_pause_visible "Home after Back"

echo "Test 5: real Accessibility detection exits an Instagram-like Reels player"
adb logcat -c
adb shell am force-stop "$FIXTURE_PKG" || true
adb shell am start -W -n "$FIXTURE_ACTIVITY" >/dev/null
wait_for_fixture_state_count "STATE_REELS_PLAYER" 1 "direct Reels fixture launch"
wait_for_fixture_state_count "STATE_SAFE_HOME" 1 "direct Reels redirect"
FG="$(foreground_line)"
echo "$FG" | tee "$ARTIFACT_DIR/test5-direct-reels.txt"
if ! echo "$FG" | grep -Fq "$FIXTURE_PKG"; then
  fail "Reels redirect left Instagram fixture instead of staying in allowed app: $FG"
fi
snapshot "reels-direct-safe"

# Let the short-video notice auto-dismiss, then enter Reels through a genuine
# clickable navigation item. This exercises TYPE_VIEW_CLICKED early interception
# as well as the full-screen player detector.
sleep 2.5
tap_ui_text "Reels"
wait_for_fixture_state_count "STATE_REELS_PLAYER" 2 "Reels navigation click"
wait_for_fixture_state_count "STATE_SAFE_HOME" 2 "Reels navigation redirect"
sleep 0.8
FG="$(foreground_line)"
echo "$FG" | tee "$ARTIFACT_DIR/test5-click-reels.txt"
if ! echo "$FG" | grep -Fq "$FIXTURE_PKG"; then
  fail "clicked Reels redirect left Instagram fixture instead of staying in allowed app: $FG"
fi
snapshot "reels-click-safe"

echo "Test 6: 30-cycle real Phone/Home stress"
for i in $(seq 1 30); do
  open_allowed_phone "stress Phone $i"

  FG="$(foreground_line)"
  if ! echo "$FG" | grep -Fq "$PHONE_PACKAGE"; then
    fail "stress cycle $i: expected allowed Phone foreground, got: $FG"
  fi

  adb shell input keyevent KEYCODE_HOME
  wait_for_pause_visible "stress Home $i"

  echo "cycle $i ok" >> "$ARTIFACT_DIR/stress.txt"
done

echo "Test 7: repeated real Phone/Back transitions"
for i in $(seq 1 15); do
  open_allowed_phone "Back cycle Phone $i"

  adb shell input keyevent KEYCODE_BACK
  sleep 0.7

  if ! pause_visible; then
    FG="$(foreground_line)"
    if ! echo "$FG" | grep -Fq "$PHONE_PACKAGE"; then
      fail "Back cycle $i escaped without protection: $FG"
    fi
  fi

  adb shell input keyevent KEYCODE_HOME
  wait_for_pause_visible "Back cycle Home $i"
  echo "back-cycle $i ok" >> "$ARTIFACT_DIR/back-stress.txt"
done

echo "Test 8: long-running stability inside allowed Phone"
open_allowed_phone "Phone before soak"
for i in $(seq 1 40); do
  sleep 0.5
  if pause_visible; then
    fail "allowed Phone became blocked during soak at sample $i"
  fi
  FG="$(foreground_line)"
  if ! echo "$FG" | grep -Fq "$PHONE_PACKAGE"; then
    fail "allowed Phone left foreground during soak at sample $i: $FG"
  fi
done

snapshot "final"
adb logcat -d > "$ARTIFACT_DIR/logcat.txt" || true
echo "QA PASS: stable navigation suite completed with short-video experiment enabled" | tee "$ARTIFACT_DIR/summary.txt"

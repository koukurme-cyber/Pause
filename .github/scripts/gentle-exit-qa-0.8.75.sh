#!/usr/bin/env bash
set -Eeuo pipefail
PKG=ru.pauza.app
SERVICE="$PKG/$PKG.domain.PauseAccessibilityService"
DIR=qa-gentle-exit
mkdir -p "$DIR"
snapshot() {
  adb exec-out screencap -p > "$DIR/$1.png" 2>/dev/null || true
  adb shell dumpsys window windows > "$DIR/$1-windows.txt" || true
}
dump_ui() {
  adb shell uiautomator dump /sdcard/qa-ui.xml >/dev/null 2>&1 || true
  adb exec-out cat /sdcard/qa-ui.xml > "$DIR/ui.xml" 2>/dev/null || true
}
fail() {
  echo "FAIL: $*" | tee -a "$DIR/results.txt"
  snapshot fail
  adb logcat -d -s PauseProtection:V AndroidRuntime:E > "$DIR/logcat.txt" || true
  exit 1
}
has_text() {
  dump_ui
  grep -Fq "$1" "$DIR/ui.xml"
}
overlay_visible() {
  adb shell dumpsys window windows | grep -Fq "appop=CREATE_ACCESSIBILITY_OVERLAY"
}
expect_notice() {
  for i in $(seq 1 18); do
    if overlay_visible; then
      echo "PASS: $1 accessibility overlay visible" | tee -a "$DIR/results.txt"
      snapshot "$1"
      return
    fi
    sleep 0.5
  done
  fail "$1 accessibility overlay not shown"
}
click_return() {
  local size width height
  size=$(adb shell wm size | tr -d '\r' | awk -F': ' '/Physical size/ {print $2}' | tail -n 1)
  width=$(echo "$size" | cut -dx -f1)
  height=$(echo "$size" | cut -dx -f2)
  [ -n "$width" ] && [ -n "$height" ] || fail "Screen resolution not found"
  # The button is centered at about 68 percent of the Pixel 6 portrait display.
  adb shell input tap $((width/2)) $((height*68/100))
  for i in $(seq 1 22); do
    if adb shell dumpsys activity activities | grep -Eq "topResumedActivity=.*$PKG/|mResumedActivity=.*$PKG/"; then
      if ! overlay_visible; then
        echo "PASS: returned to active Pause" | tee -a "$DIR/results.txt"
        snapshot returned
        return
      fi
    fi
    sleep 0.5
  done
  fail "Return button did not restore active Pause"
}
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure immersive_mode_confirmations confirmed || true
END=$(( $(date +%s%3N) + 20*60*1000 ))
cat > /tmp/qa-prefs.xml <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
 <long name="session_end_epoch_ms" value="$END" />
 <boolean name="first_setup_completed" value="true" />
 <boolean name="setup_checklist_completed" value="true" />
</map>
EOF
adb push /tmp/qa-prefs.xml /data/local/tmp/qa-prefs.xml >/dev/null
adb shell chmod 644 /data/local/tmp/qa-prefs.xml
adb shell run-as "$PKG" mkdir -p "/data/user/0/$PKG/shared_prefs"
adb shell run-as "$PKG" cp /data/local/tmp/qa-prefs.xml "/data/user/0/$PKG/shared_prefs/pause_store.xml"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
adb shell cmd appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow || true
adb shell cmd appops set "$PKG" GET_USAGE_STATS allow
adb shell settings put secure enabled_accessibility_services "$SERVICE"
adb shell settings put secure accessibility_enabled 1
adb shell settings get secure enabled_accessibility_services > "$DIR/secure-after-command.txt" || true
is_bound() {
  adb shell dumpsys accessibility | grep -A12 "Bound services:" | grep -Fq PauseAccessibilityService
}
for i in $(seq 1 12); do
  if is_bound; then
    echo "PASS: accessibility bound" | tee -a "$DIR/results.txt"
    break
  fi
  sleep 0.4
done
tap_ui() {
  dump_ui
  local xy
  xy=$(python3 - "$DIR/ui.xml" "$1" <<'PY'
import sys,re,xml.etree.ElementTree as E
try: root=E.parse(sys.argv[1]).getroot()
except Exception: sys.exit(1)
target=sys.argv[2].casefold()
for n in root.iter("node"):
    if target in (n.get("text","").casefold(),n.get("content-desc","").casefold()):
        m=re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",n.get("bounds",""))
        if m:
            a=list(map(int,m.groups()))
            print((a[0]+a[2])//2,(a[1]+a[3])//2)
            sys.exit(0)
sys.exit(1)
PY
  ) || return 1
  adb shell input tap $xy
}
if ! is_bound; then
  adb shell am start -W -a android.settings.ACCESSIBILITY_SETTINGS >/dev/null 2>&1 || true
  sleep 2
  snapshot accessibility-settings
  for i in $(seq 1 4); do
    if tap_ui "Пауза"; then break; fi
    tap_ui "Downloaded apps" || tap_ui "Installed apps" || true
    sleep 1
    if tap_ui "Пауза"; then break; fi
    adb shell input swipe 530 1850 530 620 380
    sleep 1
  done
  snapshot accessibility-app-settings
  tap_ui "Use Пауза" || tap_ui "Use Pauza" || tap_ui "Пауза" || true
  sleep 1
  tap_ui "Allow" || tap_ui "Разрешить" || tap_ui "OK" || true
  sleep 1
  snapshot accessibility-enabled
  for i in $(seq 1 18); do
    if is_bound; then
      echo "PASS: accessibility bound via settings UI" | tee -a "$DIR/results.txt"
      break
    fi
    sleep 0.5
  done
fi
adb shell dumpsys accessibility > "$DIR/accessibility.txt" || true
adb shell settings get secure enabled_accessibility_services > "$DIR/enabled-services.txt" || true
if ! grep -Fq "PASS: accessibility bound" "$DIR/results.txt"; then
  echo "Accessibility never bound" >> "$DIR/results.txt"
  fail "Accessibility service not enabled"
fi
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 3
# Pixel Launcher can show a first-boot ANR dialog above an otherwise healthy Pause.
for i in $(seq 1 4); do
  if tap_ui "Close app"; then
    echo "Dismissed Pixel Launcher ANR" >> "$DIR/results.txt"
    sleep 1
  else
    break
  fi
done
snapshot initial
has_text "Осталось" || fail "Active Pause not shown"
if has_text "Служба блокировки отключена"; then fail "Accessibility service disabled in UI"; fi
echo "PASS: active screen" | tee -a "$DIR/results.txt"

adb shell am start -W -a android.settings.SETTINGS >/dev/null 2>&1 || true
expect_notice settings
click_return

echo "External web link transition"
adb shell am start -W -a android.intent.action.VIEW -d https://example.com >/dev/null 2>&1 || true
expect_notice external-link
click_return

adb shell input keyevent KEYCODE_HOME
expect_notice home
click_return

adb shell am start -W -a android.intent.action.DIAL -d tel: >/dev/null 2>&1 || true
sleep 2
if overlay_visible; then fail "Phone incorrectly blocked"; fi
echo "PASS: allowed Phone" | tee -a "$DIR/results.txt"
snapshot phone

adb shell input keyevent KEYCODE_APP_SWITCH
sleep 2
snapshot recents
adb shell am start -W -a android.settings.SETTINGS >/dev/null 2>&1 || true
expect_notice after-recents
click_return
echo "ALL TESTS PASSED" | tee -a "$DIR/results.txt"

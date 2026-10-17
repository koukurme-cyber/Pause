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
  adb logcat -d -v threadtime -s PauseTrace:I PauseProtection:V AndroidRuntime:E > "$DIR/logcat.txt" || true
  adb shell dumpsys accessibility > "$DIR/failure-accessibility.txt" || true
  adb shell dumpsys activity activities > "$DIR/failure-activities.txt" || true
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
  adb shell input tap $((width/2)) $((height*70/100))
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
adb install -r qa-link-origin-fixture/build/outputs/apk/debug/qa-link-origin-fixture-debug.apk
adb install -r qa-link-browser-fixture/build/outputs/apk/debug/qa-link-browser-fixture-debug.apk
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure immersive_mode_confirmations confirmed || true
END=$(( $(date +%s%3N) + 20*60*1000 ))
cat > /tmp/qa-prefs.xml <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
 <set name="selected_packages">
   <string>org.pauza.qa.origin</string>
 </set>
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

adb logcat -c || true
adb shell am start -W -a android.settings.SETTINGS >/dev/null 2>&1 || true
expect_notice settings
sleep 2
overlay_visible || fail "settings blocker flickered or dismissed itself"
echo "PASS: settings blocker stable for 2 seconds" | tee -a "$DIR/results.txt"
click_return
if ! adb logcat -d -s PauseTrace:I | grep -Fq "BUTTON_CLICK"; then
  fail "return button click was not observed by service"
fi
echo "PASS: return button click registered" | tee -a "$DIR/results.txt"

echo "External web link transition"
adb shell am start -W -a android.intent.action.VIEW -d https://example.com >/dev/null 2>&1 || true
expect_notice external-link
sleep 2
overlay_visible || fail "external link blocker flickered"
echo "PASS: external link blocker stable for 2 seconds" | tee -a "$DIR/results.txt"
click_return

expect_direct_pause() {
  local label="$1"
  for i in $(seq 1 26); do
    if adb shell dumpsys activity activities | grep -Eq "topResumedActivity=.*$PKG/|mResumedActivity=.*$PKG/"; then
      if ! overlay_visible; then
        echo "PASS: $label returned directly to Pause" | tee -a "$DIR/results.txt"
        snapshot "$label"
        return 0
      fi
    fi
    sleep 0.5
  done
  fail "$label did not return directly to Pause"
}
adb shell input keyevent KEYCODE_HOME
expect_direct_pause home

adb shell am start -W -a android.intent.action.DIAL -d tel: >/dev/null 2>&1 || true
sleep 2
if overlay_visible; then fail "Phone incorrectly blocked"; fi
echo "PASS: allowed Phone" | tee -a "$DIR/results.txt"
snapshot phone

# Explicitly return to Pause first; the earlier Phone session is finished.
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
sleep 1
adb shell input keyevent KEYCODE_APP_SWITCH
expect_direct_pause recents
adb shell am start -W -a android.settings.SETTINGS >/dev/null 2>&1 || true
expect_notice after-recents
click_return
echo "Testing return to allowed Phone after blocked external link"
adb shell am start -W -a android.intent.action.DIAL -d tel: >/dev/null 2>&1 || true
sleep 2
PHONE_PACKAGE=$(adb shell cmd package resolve-activity --brief -a android.intent.action.DIAL -d tel: 2>/dev/null | tr -d '\r' | tail -n 1 | cut -d/ -f1)
[ -n "$PHONE_PACKAGE" ] || fail "cannot determine Phone package"
echo "phone package=$PHONE_PACKAGE" >> "$DIR/results.txt"
adb shell am start -W -a android.intent.action.VIEW -d https://example.com >/dev/null 2>&1 || true
expect_notice allowed-phone-external-link
click_return_to_allowed() {
  local expected="$1"
  local size width height
  size=$(adb shell wm size | tr -d '\r' | awk -F': ' '/Physical size/ {print $2}' | tail -n 1)
  width=$(echo "$size" | cut -dx -f1)
  height=$(echo "$size" | cut -dx -f2)
  adb shell input tap $((width/2)) $((height*70/100))
  for i in $(seq 1 22); do
    if adb shell dumpsys activity activities | grep -E 'topResumedActivity=|mResumedActivity' | grep -Fq "$expected/"; then
      if ! overlay_visible; then
        echo "PASS: restored allowed $expected" | tee -a "$DIR/results.txt"
        snapshot allowed-phone-returned
        return 0
      fi
    fi
    sleep 0.5
  done
  fail "return did not restore allowed $expected"
}
click_return_to_allowed "$PHONE_PACKAGE"

echo "Testing REAL link click inside an allowed source app"
adb shell am start -W -n org.pauza.qa.origin/.OriginActivity > "$DIR/origin-launch.txt"
sleep 4
overlay_visible && fail "allowed source app blocked before opening link"
adb shell dumpsys activity activities | grep -Fq "org.pauza.qa.origin/" || fail "origin Activity not present after waiting for retries"
snapshot origin-before-click
# Android Button capitalizes labels on Pixel; tap_ui compares casefolded
# strings while the previous case-sensitive has_text assertion did not.
tap_ui "Открыть внешнюю ссылку" || fail "cannot tap source app's external link button"
expect_notice source-clicked-external-link
sleep 2
overlay_visible || fail "external-link notice dismissed before pressing return"
if ! adb logcat -d -s PauseTrace:I | grep -Fq "notice returnLabel=Вернуться в Источник ссылок source=org.pauza.qa.origin"; then
  fail "return button has wrong original app name"
fi
echo "PASS: external-link button label names original allowed app" | tee -a "$DIR/results.txt"
click_return_to_allowed "org.pauza.qa.origin"
has_text "тот же экран" || fail "return did not restore the previous origin screen"
has_text "создание Activity: 1" || fail "return relaunched source rather than restoring previous activity"
echo "PASS: actual source-app link and same-screen return" | tee -a "$DIR/results.txt"
# One complete selection revision is allowed during the active Pause.
echo "Testing one-time full selection correction"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 2
has_text "Исправить выбор" || fail "one-time correction entry point missing"
tap_ui "Исправить выбор" || fail "cannot open correction"
sleep 1
has_text "Подтвердить новый список" || fail "correction editor absent"
snapshot correction-first-open
tap_ui "Вернуться без изменений" || fail "cannot cancel selection edit"
sleep 1
has_text "Исправить выбор" || fail "cancelling correction consumed the attempt"
if adb shell run-as "$PKG" cat "/data/user/0/$PKG/shared_prefs/pause_store.xml" | grep -Fq 'selection_correction_used" value="true"'; then
  fail "selection correction consumed on cancel"
fi
echo "PASS: cancellation preserves the one correction" | tee -a "$DIR/results.txt"

tap_ui "Исправить выбор" || fail "cannot reopen correction"
sleep 2
# Accessibility hierarchy names the app row, but only the Switch is clickable.
# Locate the app label and click the switch on that same row's right edge.
toggle_app_by_label() {
  local label="$1"
  local coords width height
  coords=""
  for attempt in $(seq 1 10); do
    dump_ui
    coords=$(python3 - "$DIR/ui.xml" "$label" <<'PY'
import sys,re,xml.etree.ElementTree as ET
try: root=ET.parse(sys.argv[1]).getroot()
except Exception: sys.exit(1)
target=sys.argv[2].casefold()
for node in root.iter('node'):
    if node.get('text','').casefold()==target:
        hit=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',node.get('bounds',''))
        if hit:
            a,b,c,d=map(int,hit.groups())
            print((b+d)//2)
            sys.exit(0)
sys.exit(1)
PY
    ) || true
    if [ -n "$coords" ]; then break; fi
    adb shell input swipe 525 1700 525 620 300
    sleep 0.5
  done
  [ -n "$coords" ] || fail "Cannot find app in correction editor: $label"
  width=$(adb shell wm size | tr -d '\r' | awk -F': ' '/Physical size/{print $2}' | tail -n1 | cut -dx -f1)
  adb shell input tap $((width*85/100)) "$coords"
}
toggle_app_by_label "Запрещённый браузер"
sleep 1
snapshot correction-edited
has_text "Добавить: 1" || fail "editor failed to add selected browser"
tap_ui "Подтвердить новый список" || fail "cannot show confirmation"
sleep 1
has_text "Подтвердить исправление?" || fail "no explicit confirmation dialog"
tap_ui "Применить выбор" || fail "cannot consume one-time correction"
sleep 2
if has_text "Выбор уже исправлен" || has_text "Исправить выбор"; then
  fail "correction link or status remains visible after confirmation"
fi
echo "PASS: top-right corner empty after consumed correction" | tee -a "$DIR/results.txt"
if ! adb shell run-as "$PKG" cat "/data/user/0/$PKG/shared_prefs/pause_store.xml" | grep -Fq 'selection_correction_used" value="true"'; then
  fail "correction not persisted"
fi
if ! adb shell run-as "$PKG" cat "/data/user/0/$PKG/shared_prefs/pause_store.xml" | grep -Fq 'org.pauza.qa.browser'; then
  fail "newly allowed browser not persisted in selected packages"
fi
echo "PASS: confirmed corrected list and consumed attempt" | tee -a "$DIR/results.txt"
snapshot correction-used
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 2
if has_text "Выбор уже исправлен" || has_text "Исправить выбор"; then
  fail "correction status or link visible after app restart"
fi
echo "PASS: consumed correction survives app restart" | tee -a "$DIR/results.txt"
# The following independent review test resets the session through its seed.
# Assert review screen keeps at least one allowed application visible
# rather than reserving a zero-height, blank weighted grid.
adb shell am force-stop "$PKG"
sed -i "s/value=\"$END\"/value=\"0\"/" /tmp/qa-prefs.xml
adb push /tmp/qa-prefs.xml /data/local/tmp/qa-prefs.xml >/dev/null
adb shell run-as "$PKG" cp /data/local/tmp/qa-prefs.xml "/data/user/0/$PKG/shared_prefs/pause_store.xml"
# force-stop suspends a running accessibility service. Restart its enabled
# setting so the Setup Continue button is available for preview verification.
adb shell settings put secure enabled_accessibility_services "" || true
adb shell settings put secure accessibility_enabled 0 || true
adb shell settings put secure enabled_accessibility_services "$SERVICE"
adb shell settings put secure accessibility_enabled 1
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
for i in $(seq 1 15); do
  if is_bound; then break; fi
  sleep 0.5
done
if ! is_bound; then
  adb shell am start -W -a android.settings.ACCESSIBILITY_SETTINGS >/dev/null 2>&1 || true
  sleep 2
  tap_ui "Пауза" || true
  sleep 1
  tap_ui "Use Пауза" || tap_ui "Use Pauza" || true
  sleep 1
  tap_ui "Allow" || tap_ui "Разрешить" || true
  adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
  sleep 2
fi
if ! is_bound; then fail "cannot re-enable service for review UI test after force-stop"; fi
sleep 2
if has_text "Защита недоступна"; then fail "review Setup still reports protection disabled"; fi
tap_ui "Продолжить" || fail "review Setup Continue not available"
sleep 2
snapshot review-preview
has_text "Доступные приложения" || fail "review app-list heading missing"
has_text "Телефон" || fail "review selected Phone icon missing"
echo "PASS: review selected applications rendered" | tee -a "$DIR/results.txt"
echo "ALL TESTS PASSED" | tee -a "$DIR/results.txt"

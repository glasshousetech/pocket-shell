#!/usr/bin/env bash
# Only for the isolated emulator allocated to this workflow.
set -euo pipefail
out="${POCKET_DEMO_OUT:-demo-output}"
suite="${POCKET_DEMO_SUITE:-workbench}"
hold_ms=15000
if [[ "$suite" == workbench ]]; then hold_ms=10000; fi
case "$suite" in
  workbench) test_class=WorkbenchUiTest; expected_tests=2 ;;
  handoff) test_class=RemoteWorkspaceUiTest; expected_tests=1 ;;
  files) test_class=FilesWorkflowTest; expected_tests=1 ;;
  jobs) test_class=JobsWorkflowTest; expected_tests=2 ;;
  *) echo "Unknown native demo suite" >&2; exit 64 ;;
esac
mkdir -p "$out"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell wm size 720x1280
adb shell wm density 240
adb shell settings put system show_touches 1
adb shell pm grant network.ght.pocketshell android.permission.POST_NOTIFICATIONS
cat > "$out/record-device.sh" <<'RECORD'
#!/system/bin/sh
cat /proc/uptime > /data/local/tmp/pocketshell-capture-start.txt
screenrecord --size 720x1280 --bit-rate 6000000 --time-limit 180 /sdcard/pocketshell-workbench.mp4 &
recorder=$!
printf '%s\n' "$recorder" > /data/local/tmp/pocketshell-screenrecord.pid
wait "$recorder"
RECORD
adb push "$out/record-device.sh" /data/local/tmp/pocketshell-record.sh
adb shell sh /data/local/tmp/pocketshell-record.sh > "$out/screenrecord.log" 2>&1 &
bridge=$!
cleanup() {
  local recorder
  recorder=$(adb shell cat /data/local/tmp/pocketshell-screenrecord.pid 2>/dev/null | tr -d '\r') || true
  if [[ "$recorder" =~ ^[0-9]+$ ]]; then adb shell kill -2 "$recorder" 2>/dev/null || true; fi
  wait "$bridge" || true
}
trap cleanup EXIT
adb shell am instrument -r -w -e class "network.ght.pocketshell.$test_class" -e demoRecording true -e demoHoldMs "$hold_ms" network.ght.pocketshell.test/androidx.test.runner.AndroidJUnitRunner | tee "$out/instrument-output.txt"
cleanup
trap - EXIT
adb pull /sdcard/pocketshell-workbench.mp4 "$out/raw.mp4"
adb pull /data/local/tmp/pocketshell-capture-start.txt "$out/capture-start.txt"
adb exec-out run-as network.ght.pocketshell tar cf - -C /data/data/network.ght.pocketshell/files screenshots > "$out/screenshots.tar"
tar xf "$out/screenshots.tar" -C "$out"
grep -Eq "OK \($expected_tests tests?\)" "$out/instrument-output.txt"
! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[234]' "$out/instrument-output.txt"

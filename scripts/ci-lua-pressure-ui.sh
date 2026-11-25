#!/bin/sh
set -eu
package=dev.hyprdroid.launcher.experimental
report=app/build/reports/androidTests/pressure-ui
mkdir -p "$report"
collect() {
    adb logcat -d -s HyprLuaGate HyprLuaTile > "$report/logcat.txt" || true
    adb shell dumpsys meminfo "$package" > "$report/final-meminfo.txt" || true
}
trap collect EXIT
adb shell pm clear "$package" | grep -Fx Success
adb logcat -G 16M
adb logcat -c
runner=$(adb shell pm list instrumentation | tr -d '\r' |
    awk -v target="(target=$package)" '$2 == target { sub(/^instrumentation:/,"",$1); print $1 }')
test -n "$runner"
status=0
timeout 150s adb shell am instrument -w -r \
    -e class dev.hyprdroid.launcher.external.ExternalLuaPressureUiTest \
    "$runner" > "$report/instrumentation.txt" 2>&1 || status=$?
cat "$report/instrumentation.txt"
collect
python3 scripts/ci-lua-timings.py "$report/logcat.txt" | tee "$report/timings.txt"
grep -F 'tiles=8' "$report/logcat.txt"
test "$status" -eq 0
grep -F 'OK (1 test)' "$report/instrumentation.txt"

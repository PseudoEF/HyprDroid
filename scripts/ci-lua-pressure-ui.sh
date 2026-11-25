#!/bin/sh
set -eu
package=dev.hyprdroid.launcher.experimental
base=app/build/reports/androidTests/pressure-ui
mkdir -p "$base"
runner=$(adb shell pm list instrumentation | tr -d '\r' |
    awk -v target="(target=$package)" '$2 == target { sub(/^instrumentation:/,"",$1); print $1 }')
test -n "$runner"
status=0
for run in 1 2 3; do
    report="$base/run-$run"
    mkdir -p "$report"
    adb shell pm clear "$package" | grep -Fx Success
    adb logcat -G 16M
    adb logcat -c
    timeout 150s adb shell am instrument -w -r \
        -e class dev.hyprdroid.launcher.external.ExternalLuaPressureUiTest \
        "$runner" > "$report/instrumentation.txt" 2>&1 || status=1
    cat "$report/instrumentation.txt"
    adb logcat -d -s HyprLuaGate HyprLuaTile HyprTileInput > "$report/logcat.txt"
    adb shell dumpsys meminfo "$package" > "$report/final-meminfo.txt"
    python3 scripts/ci-lua-timings.py "$report/logcat.txt" | tee "$report/timings.txt"
    grep -F 'tiles=8' "$report/logcat.txt" || status=1
    grep -F 'OK (1 test)' "$report/instrumentation.txt" || status=1
done
exit "$status"

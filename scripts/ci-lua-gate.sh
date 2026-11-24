#!/bin/sh
set -eu
package=dev.hyprdroid.launcher.experimental
report=app/build/reports/androidTests/release-gate
mkdir -p "$report"
collect() {
    adb logcat -d -s HyprLuaGate HyprLuaTile ActivityTaskManager lmkd > "$report/logcat.txt" || true
    adb shell dumpsys activity exit-info "$package" > "$report/exit-info.txt" || true
}
trap collect EXIT
adb shell pm clear "$package" | grep -Fx Success
adb logcat -G 16M
adb logcat -c
runner=$(adb shell pm list instrumentation | tr -d '\r' |
    awk -v target="(target=$package)" '$2 == target { sub(/^instrumentation:/,"",$1); print $1 }')
test -n "$runner"
status=0
adb shell am instrument -w -r -e class 'dev.hyprdroid.launcher.external.ExternalTileLifecycleTest#firstIncrementAfterMountPersistsAcrossRecreationTwentyTimes' "$runner" > "$report/instrumentation.txt" 2>&1 || status=$?
cat "$report/instrumentation.txt"
collect
grep -F 'lifecycle round=' "$report/logcat.txt" | tee "$report/lifecycle-rounds.txt"
test "$status" -eq 0
grep -F 'OK (1 test)' "$report/instrumentation.txt"
test "$(wc -l < "$report/lifecycle-rounds.txt")" -eq 20

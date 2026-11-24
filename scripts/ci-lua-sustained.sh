#!/bin/sh
set -eu
package=dev.hyprdroid.launcher.experimental
report=app/build/reports/androidTests/sustained
mkdir -p "$report"
collect() {
    adb logcat -d -s HyprLuaSustained HyprLuaTile > "$report/logcat.txt" || true
    adb shell dumpsys meminfo "$package" > "$report/final-meminfo.txt" || true
}
trap collect EXIT
adb shell pm clear "$package" | grep -Fx Success
adb logcat -c
adb logcat -G 16M
runner=$(adb shell pm list instrumentation | tr -d '\r' |
    awk -v target="(target=$package)" '$2 == target { sub(/^instrumentation:/,"",$1); print $1 }')
test -n "$runner"
status=0
adb shell am instrument -w -r -e class dev.hyprdroid.launcher.external.ExternalLuaSustainedTest "$runner" > "$report/instrumentation.txt" 2>&1 || status=$?
cat "$report/instrumentation.txt"
collect
grep -F 'elapsedMs=' "$report/logcat.txt" | tail -25
printf 'binding_requests=%s\nunexpected_deaths=%s\n' \
    "$(grep -c 'Worker binding requested' "$report/logcat.txt" || true)" \
    "$(grep -c 'Worker Binder died; disposalRequested=false' "$report/logcat.txt" || true)" |
    tee "$report/worker-counts.txt"
test "$status" -eq 0
grep -F 'OK (1 test)' "$report/instrumentation.txt"

#!/bin/sh
set -eu
package=dev.hyprdroid.launcher.experimental
report=app/build/reports/androidTests/prewarm
mkdir -p "$report"
runner=$(adb shell pm list instrumentation | tr -d '\r' |
    awk -v target="(target=$package)" '$2 == target { sub(/^instrumentation:/,"",$1); print $1 }')
test -n "$runner"
test_status=0
for count in 0 1 2 4 8; do
    # Alternate order to reduce a fixed first-run bias.
    case "$count" in 1|4) modes="true false" ;; *) modes="false true" ;; esac
    for warm in $modes; do
        trial="$report/${count}-${warm}"
        mkdir -p "$trial"
        adb shell pm clear "$package" | grep -Fx Success
        adb logcat -G 16M
        adb logcat -c
        (
            while :; do
                printf '\nSAMPLE %s\n' "$(date +%s)"
                adb shell dumpsys meminfo -s |
                    awk -v pkg="$package" '/Total PSS by process:/ { show=1; next }
                        /Total PSS by / { show=0 } show && index($0,pkg) { print }'
                sleep 2
            done
        ) > "$trial/pss.txt" 2>&1 &
        sampler=$!
        adb shell am instrument -w -r -e class dev.hyprdroid.launcher.external.ExternalLuaPrewarmingTest \
            -e clockTiles "$count" -e prewarm "$warm" "$runner" > "$trial/instrumentation.txt" 2>&1 || test_status=1
        kill "$sampler" 2>/dev/null || true
        wait "$sampler" 2>/dev/null || true
        adb logcat -d -s HyprLuaGate HyprLuaReserve HyprLuaTile HyprLuaBenchmark > "$trial/logcat.txt"
        grep -F 'OK (1 test)' "$trial/instrumentation.txt" || test_status=1
        grep -F 'HyprLuaBenchmark:' "$trial/logcat.txt" || test_status=1
        python3 scripts/ci-lua-timings.py "$trial/logcat.txt" > "$trial/timings.txt"
    done
done
python3 scripts/ci-lua-prewarm-report.py "$report" | tee "$report/summary.txt"
exit "$test_status"

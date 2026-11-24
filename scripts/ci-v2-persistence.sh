#!/bin/sh
set -eu
package="$1"
runners=$(adb shell pm list instrumentation)
printf '%s
' "$runners"
runner=$(printf '%s
' "$runners" | tr -d '\r' |
    awk -v target="(target=$package)" '$2 == target { sub(/^instrumentation:/,"",$1); print $1 }')
test -n "$runner"
report=app/build/reports/androidTests/v2-persistence
mkdir -p "$report"
trap 'adb logcat -d -s HyprLuaTile > "$report/logcat.txt" || true' EXIT
phase() {
    status=0
    adb shell am instrument -w -r \
        -e class dev.hyprdroid.launcher.external.ExternalV2PersistenceTest \
        -e persistencePhase "$1" "$runner" > "$report/$1.txt" 2>&1 || status=$?
    cat "$report/$1.txt"
    test "$status" -eq 0
    grep -F 'OK (1 test)' "$report/$1.txt"
}
phase seed
adb shell am force-stop "$package"
if adb shell pidof "$package"; then
    echo "Previous launcher process is still alive" >&2
    exit 1
fi
phase restore

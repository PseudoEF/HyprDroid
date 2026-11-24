#!/bin/sh
set -eu
package=dev.hyprdroid.launcher.experimental
report=app/build/reports/androidTests/lua-circuit.txt
runner=$(adb shell pm list instrumentation | tr -d '\r' |
    awk -v target="(target=$package)" '$2 == target { sub(/^instrumentation:/,"",$1); print $1 }')
test -n "$runner"
adb shell am force-stop "$package"
status=0
adb shell am instrument -w -r -e class dev.hyprdroid.launcher.external.ExternalLuaCircuitTest "$runner" > "$report" 2>&1 || status=$?
cat "$report"
test "$status" -eq 0
grep -F 'OK (1 test)' "$report"

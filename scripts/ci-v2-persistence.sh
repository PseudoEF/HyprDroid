#!/bin/sh
set -eu
package="$1"
phase() {
    output=$(adb shell am instrument -w -r \
        -e class dev.hyprdroid.launcher.external.ExternalV2PersistenceTest \
        -e persistencePhase "$1" "$package.test/androidx.test.runner.AndroidJUnitRunner")
    printf '%s\n' "$output"
    printf '%s\n' "$output" | grep -F 'OK (1 test)'
}
phase seed
adb shell am force-stop "$package"
if adb shell pidof "$package"; then
    echo "Previous launcher process is still alive" >&2
    exit 1
fi
phase restore

#!/bin/sh
set -eu
developer=dev.hyprdroid.launcher.developer
target="$developer.test/androidx.test.runner.AndroidJUnitRunner"
adb install -r app/build/outputs/apk/androidTest/developer/app-developer-androidTest.apk
mkdir -p app/build/reports/developer-workspace
for mode in clean classic; do
    # The two first-window scenarios each start with a fresh emulator sandbox.
    adb shell pm clear "$developer" | grep -Fx Success
    report="app/build/reports/developer-workspace/$mode.txt"
    adb shell am instrument -w -r \
        -e class 'dev.hyprdroid.launcher.developer.DeveloperWorkspaceTest#firstWindowOpensFromTheWorkspaceMenu' \
        -e workspaceMode "$mode" "$target" > "$report"
    cat "$report"
    grep -F 'OK (1 test)' "$report"
done

#!/bin/sh
set -eu
developer=dev.hyprdroid.launcher.developer
target="$developer.test/androidx.test.runner.AndroidJUnitRunner"
mkdir -p app/build/reports/developer-workspace
for mode in clean classic; do
    # Uninstall only the emulator fixture. A fresh install also resets the
    # PackageManager timestamps used by the shell presentation migration.
    adb uninstall "$developer" | grep -Fx Success
    adb install "$RUNNER_TEMP/developer-test.apk"
    adb install -r app/build/outputs/apk/androidTest/developer/app-developer-androidTest.apk
    report="app/build/reports/developer-workspace/$mode.txt"
    adb shell am instrument -w -r \
        -e class 'dev.hyprdroid.launcher.developer.DeveloperWorkspaceTest#firstWindowOpensFromTheWorkspaceMenu' \
        -e workspaceMode "$mode" "$target" > "$report"
    cat "$report"
    grep -F 'OK (1 test)' "$report"
done

#!/bin/sh
set -eu
mkdir -p .local/shell-ui
collect_artifacts() {
    adb logcat -d > .local/shell-ui/logcat.txt || true
    adb pull /sdcard/Download/hyprdroid-shell .local/shell-ui/ || true
}
trap collect_artifacts EXIT
# Keep seeded workspaces installed for the font-size and orientation previews.
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.notClass=dev.hyprdroid.launcher.UpgradePersistenceTest,dev.hyprdroid.launcher.WidgetProcessRestoreTest --no-daemon --stacktrace
# A separate pair of instrumentation invocations proves restoration after a real process death.
# Shell binding permission is limited to this isolated emulator fixture, never product code.
adb shell appwidget grantbind --package dev.hyprdroid.launcher --user 0
for widget_phase in seed verify; do
  if [ "$widget_phase" = verify ]; then adb shell am force-stop dev.hyprdroid.launcher; fi
  adb shell am instrument -w -r -e class dev.hyprdroid.launcher.WidgetProcessRestoreTest -e widgetPhase "$widget_phase" \
    dev.hyprdroid.launcher.test/androidx.test.runner.AndroidJUnitRunner > ".local/shell-ui/widget-process-$widget_phase.txt"
  cat ".local/shell-ui/widget-process-$widget_phase.txt"
  grep -q 'OK (1 test)' ".local/shell-ui/widget-process-$widget_phase.txt"
done
adb shell appwidget revokebind --package dev.hyprdroid.launcher --user 0
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell settings put system font_scale 1.6
adb shell am force-stop dev.hyprdroid.launcher
adb shell am start -n dev.hyprdroid.launcher/.MainActivity
sleep 3
adb shell screencap -p /sdcard/Download/hyprdroid-shell/large-font.png
adb shell wm size 720x1280
adb shell wm density 320
sleep 3
adb shell screencap -p /sdcard/Download/hyprdroid-shell/small-screen.png
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 3
adb shell screencap -p /sdcard/Download/hyprdroid-shell/landscape.png

#!/bin/sh
set -eu
mkdir -p .local/shell-ui
collect_artifacts() {
    adb logcat -d > .local/shell-ui/logcat.txt || true
    adb pull /sdcard/Android/data/dev.hyprdroid.launcher/files/shell-previews .local/shell-ui/ || true
}
trap collect_artifacts EXIT
./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace
adb shell settings put system font_scale 1.6
adb shell am force-stop dev.hyprdroid.launcher
adb shell am start -n dev.hyprdroid.launcher/.MainActivity
sleep 3
adb shell screencap -p /sdcard/Android/data/dev.hyprdroid.launcher/files/shell-previews/large-font.png
adb shell wm size 720x1280
adb shell wm density 320
sleep 3
adb shell screencap -p /sdcard/Android/data/dev.hyprdroid.launcher/files/shell-previews/small-screen.png
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 3
adb shell screencap -p /sdcard/Android/data/dev.hyprdroid.launcher/files/shell-previews/landscape.png

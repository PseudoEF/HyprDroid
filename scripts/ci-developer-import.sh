#!/bin/sh
set -eu
developer=dev.hyprdroid.launcher.developer
target="$developer.test/androidx.test.runner.AndroidJUnitRunner"
adb install -r app/build/outputs/apk/androidTest/developer/app-developer-androidTest.apk
for file in developer.pub.pem my-tile-1.0.0.hyp my-tile.signature.json; do
    adb push "$HYPR_DEVELOPER_SDK_FIXTURE/$file" "/sdcard/Download/$file"
    adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Download/$file"
done
mkdir -p app/build/reports/developer-import
for phase in seed verify; do
    if [ "$phase" = verify ]; then adb shell am force-stop "$developer"; fi
    report="app/build/reports/developer-import/$phase.txt"
    adb shell am instrument -w -r -e class dev.hyprdroid.launcher.developer.DeveloperImportTest \
        -e developerPhase "$phase" "$target" > "$report"
    cat "$report"
    grep -F 'OK (1 test)' "$report"
done

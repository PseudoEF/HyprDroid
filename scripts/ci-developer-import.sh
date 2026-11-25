#!/bin/sh
set -eu
developer=dev.hyprdroid.launcher.developer
target="$developer.test/androidx.test.runner.AndroidJUnitRunner"
reports=app/build/reports/developer-import
mkdir -p "$reports"
collect() {
    adb logcat -d -s HyprDeveloperSaf ActivityTaskManager DocumentsUI > "$reports/picker-logcat.txt" || true
    adb exec-out run-as "$developer" cat cache/developer-picker-window.xml > "$reports/picker-window.xml" 2>/dev/null || true
}
trap collect EXIT
fingerprint=$(openssl pkey -pubin -in "$HYPR_DEVELOPER_SDK_FIXTURE/developer.pub.pem" -outform DER | sha256sum | cut -d ' ' -f 1)
test "${#fingerprint}" -eq 64
adb install -r app/build/outputs/apk/androidTest/developer/app-developer-androidTest.apk
adb shell mkdir -p /sdcard/Download
adb shell cmd package resolve-activity --brief -a android.intent.action.OPEN_DOCUMENT \
    -c android.intent.category.OPENABLE -t '*/*' > "$reports/picker-resolver.txt"
for file in developer.pub.pem my-tile-1.0.0.hyp my-tile.signature.json; do
    adb push "$HYPR_DEVELOPER_SDK_FIXTURE/$file" "/sdcard/Download/$file"
    expected=$(sha256sum "$HYPR_DEVELOPER_SDK_FIXTURE/$file" | cut -d ' ' -f 1)
    actual=$(adb shell sha256sum "/sdcard/Download/$file" | cut -d ' ' -f 1)
    test "$expected" = "$actual"
    printf '%s  %s\n' "$actual" "$file" >> "$reports/fixture-sha256.txt"
    adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Download/$file"
done
for phase in seed verify; do
    if [ "$phase" = verify ]; then adb shell am force-stop "$developer"; fi
    report="$reports/$phase.txt"
    adb shell am instrument -w -r -e class dev.hyprdroid.launcher.developer.DeveloperImportTest \
        -e developerPhase "$phase" -e developerFingerprint "$fingerprint" "$target" > "$report"
    cat "$report"
    grep -F 'OK (1 test)' "$report"
done

#!/bin/sh
set -eu
ordinary=dev.hyprdroid.launcher
developer=dev.hyprdroid.launcher.developer
ordinary_apk=app/build/outputs/apk/debug/app-debug.apk

adb install "$ordinary_apk"
adb install "$RUNNER_TEMP/developer-test.apk"
ordinary_uid=$(adb shell pm list packages -U "$ordinary" | tr -d '\r' | awk -v p="package:$ordinary" '$1 == p {print $2}')
developer_uid=$(adb shell pm list packages -U "$developer" | tr -d '\r' | awk -v p="package:$developer" '$1 == p {print $2}')
test -n "$ordinary_uid"
test -n "$developer_uid"
test "$ordinary_uid" != "$developer_uid"

adb shell run-as "$ordinary" touch ordinary-private
adb shell run-as "$developer" touch developer-private
adb shell run-as "$ordinary" test ! -e developer-private
adb shell run-as "$developer" test ! -e ordinary-private
if adb shell run-as "$developer" cat "/data/user/0/$ordinary/ordinary-private"; then
    exit 1
fi
sh scripts/ci-home.sh select
adb shell am start -W -n "$developer/dev.hyprdroid.launcher.developer.DeveloperActivity"
adb install -r "$ordinary_apk"
adb install -r "$RUNNER_TEMP/developer-test.apk"
adb shell run-as "$ordinary" test -f ordinary-private
adb shell run-as "$developer" test -f developer-private
./gradlew :app:connectedDebugAndroidTest

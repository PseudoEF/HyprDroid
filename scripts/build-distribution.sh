#!/bin/sh
set -eu
destination="$RUNNER_TEMP/hyprdroid-distribution"
mkdir "$destination"
mkdir "$RUNNER_TEMP/hyprdroid-upgrade"
./gradlew :app:assembleDebug -PbuildVersionCode="$CODE_A" -PbuildVersionName=0.5.2-update-test.1 --no-daemon --stacktrace
cp app/build/outputs/apk/debug/app-debug.apk "$destination/HyprDroid-0.5.2-update-test.1-$CODE_A.apk"
python3 scripts/verify-apk.py "$destination/HyprDroid-0.5.2-update-test.1-$CODE_A.apk" "$CODE_A" 0.5.2-update-test.1
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PbuildVersionCode="$CODE_B" -PbuildVersionName=0.5.2 --no-daemon --stacktrace
cp app/build/outputs/apk/debug/app-debug.apk "$destination/HyprDroid-0.5.2-$CODE_B.apk"
cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk "$RUNNER_TEMP/hyprdroid-upgrade/test.apk"
python3 scripts/verify-apk.py "$destination/HyprDroid-0.5.2-$CODE_B.apk" "$CODE_B" 0.5.2

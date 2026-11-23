#!/bin/sh
set -eu
destination="$RUNNER_TEMP/hyprdroid-distribution"
mkdir "$destination"
mkdir "$RUNNER_TEMP/hyprdroid-upgrade"
baseline_commit=b9ac974cd333b9ae59caea409424ff7e7fda0140
test "$(git -C .local/upgrade-baseline rev-parse HEAD)" = "$baseline_commit"
# Rebuild the pinned v0.9.0 source with its original code and the same persistent key.
# It is a non-distributed fixture; no dependence on an expiring historical artifact.
HYPRDROID_BUILD_COMMIT="$baseline_commit" ./gradlew -p .local/upgrade-baseline :app:assembleDebug -PbuildVersionCode=10095 -PbuildVersionName=0.9.0 --no-daemon --stacktrace
cp .local/upgrade-baseline/app/build/outputs/apk/debug/app-debug.apk "$RUNNER_TEMP/hyprdroid-upgrade/a.apk"
HYPRDROID_BUILD_COMMIT="$baseline_commit" python3 scripts/verify-apk.py "$RUNNER_TEMP/hyprdroid-upgrade/a.apk" 10095 0.9.0
set --
if [ "${HYPRDROID_TEST_EXTERNAL_LUA:-false}" = true ]; then
  set -- -PexternalLuaOnMain=true
  if [ "${HYPRDROID_TEST_V2_FIXTURES:-false}" = true ]; then
    set -- "$@" -Pv2FixtureLua=true
  fi
fi
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PbuildVersionCode="$CODE_B" -PbuildVersionName=0.9.0 "$@" --no-daemon --stacktrace
cp app/build/outputs/apk/debug/app-debug.apk "$destination/HyprDroid-0.9.0-$CODE_B.apk"
cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk "$RUNNER_TEMP/hyprdroid-upgrade/test.apk"
python3 scripts/verify-apk.py "$destination/HyprDroid-0.9.0-$CODE_B.apk" "$CODE_B" 0.9.0

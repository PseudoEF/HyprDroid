#!/bin/sh
set -eu
destination="$RUNNER_TEMP/hyprdroid-distribution"
mkdir "$destination"
mkdir "$RUNNER_TEMP/hyprdroid-upgrade"
if [ "${HYPRDROID_TEST_OFFICIAL_UPGRADE:-false}" = true ]; then
  baseline_commit=84f7b5aea57bacc34506aacfa84f954f25584780
  cp .local/upgrade-10191/HyprDroid-0.9.0-10191.apk "$RUNNER_TEMP/hyprdroid-upgrade/a.apk"
  HYPRDROID_BUILD_COMMIT="$baseline_commit" GITHUB_RUN_ID=45372808949 GITHUB_RUN_ATTEMPT=1 \
    python3 scripts/verify-apk.py "$RUNNER_TEMP/hyprdroid-upgrade/a.apk" 10191 0.9.0
elif [ "${HYPRDROID_TEST_V2_OFFICIAL:-false}" = true ]; then
  baseline_commit=caf1b17edec8be135d994460ed1d87dd167bab20
  cp .local/upgrade-10185/HyprDroid-0.9.0-10185.apk "$RUNNER_TEMP/hyprdroid-upgrade/a.apk"
  HYPRDROID_BUILD_COMMIT="$baseline_commit" GITHUB_RUN_ID=45328873979 GITHUB_RUN_ATTEMPT=1     python3 scripts/verify-apk.py "$RUNNER_TEMP/hyprdroid-upgrade/a.apk" 10185 0.9.0
else
  baseline_commit=b9ac974cd333b9ae59caea409424ff7e7fda0140
  test "$(git -C .local/upgrade-baseline rev-parse HEAD)" = "$baseline_commit"
  HYPRDROID_BUILD_COMMIT="$baseline_commit" ./gradlew -p .local/upgrade-baseline :app:assembleDebug -PbuildVersionCode=10095 -PbuildVersionName=0.9.0 --no-daemon --stacktrace
  cp .local/upgrade-baseline/app/build/outputs/apk/debug/app-debug.apk "$RUNNER_TEMP/hyprdroid-upgrade/a.apk"
  HYPRDROID_BUILD_COMMIT="$baseline_commit" python3 scripts/verify-apk.py "$RUNNER_TEMP/hyprdroid-upgrade/a.apk" 10095 0.9.0
fi
set --
if [ "${HYPRDROID_TEST_EXTERNAL_LUA:-false}" = true ]; then
  set -- -PexternalLuaOnMain=true
  if [ "${HYPRDROID_TEST_V2_FIXTURES:-false}" = true ]; then
    set -- "$@" -Pv2FixtureLua=true
  elif [ "${HYPRDROID_TEST_V2_OFFICIAL:-false}" = true ]; then
    set -- "$@" -Pv2OfficialLua=true
  fi
fi
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PbuildVersionCode="$CODE_B" -PbuildVersionName=0.9.0 "$@" --no-daemon --stacktrace
cp app/build/outputs/apk/debug/app-debug.apk "$destination/HyprDroid-0.9.0-$CODE_B.apk"
cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk "$RUNNER_TEMP/hyprdroid-upgrade/test.apk"
python3 scripts/verify-apk.py "$destination/HyprDroid-0.9.0-$CODE_B.apk" "$CODE_B" 0.9.0

#!/bin/sh
set -eu
package=dev.hyprdroid.launcher
report=.local/upgrade-report
mkdir -p "$report"
collect_report() {
  adb logcat -d > "$report/logcat.txt" || true
  adb pull /sdcard/Download/hyprdroid-shell "$report/" || true
}
trap collect_report EXIT
fixture() {
  phase="$1"
  code="$2"
  version="$3"
  commit="$4"
  adb shell am instrument -w -r -e class dev.hyprdroid.launcher.UpgradePersistenceTest \
    -e phase "$phase" -e expectedCode "$code" -e expectedName "$version" \
    -e expectedCommit "$commit" -e expectedCertificate "$(cat config/debug-signing-cert.sha256)" \
    "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$report/$phase.txt"
  cat "$report/$phase.txt"
  grep -q 'OK (1 test)' "$report/$phase.txt"
}
official_case() {
  adb shell am instrument -w -r -e class "dev.hyprdroid.launcher.external.OfficialV2NetworkTest#$1"     "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$report/official-$1.txt"
  cat "$report/official-$1.txt"
  grep -q 'OK (1 test)' "$report/official-$1.txt"
}
cp "$RUNNER_TEMP/hyprdroid-upgrade/a.json" "$report/build-a.json"
cp "$RUNNER_TEMP/hyprdroid-distribution/HyprDroid-0.9.0-$CODE_B.json" "$report/build-b.json"
adb install "$RUNNER_TEMP/hyprdroid-upgrade/a.apk"
adb install "$RUNNER_TEMP/hyprdroid-upgrade/test.apk"
adb shell appwidget grantbind --package "$package" --user 0
baseline_code=10095
baseline_commit=b9ac974cd333b9ae59caea409424ff7e7fda0140
if [ "${HYPRDROID_TEST_V2_OFFICIAL:-false}" = true ]; then
  baseline_code=10185
  baseline_commit=caf1b17edec8be135d994460ed1d87dd167bab20
fi
if [ "${HYPRDROID_TEST_OFFICIAL_UPGRADE:-false}" = true ]; then
  baseline_code=10199
  baseline_commit=d435279278f3132f27565affb8b58b47f83abb09
fi
fixture seed "$baseline_code" 0.9.0 "$baseline_commit"
if [ "${HYPRDROID_TEST_V2_OFFICIAL:-false}" = true ]; then
  if [ "${HYPRDROID_TEST_OFFICIAL_UPGRADE:-false}" = true ]; then
    official_case seedOfficial
  else
    official_case seedLegacy
  fi
fi
adb shell appwidget revokebind --package "$package" --user 0
# Simulate an already opted-in installation, including Android's actual special permission.
adb shell appops set "$package" SYSTEM_ALERT_WINDOW allow
adb shell appops get "$package" SYSTEM_ALERT_WINDOW > "$report/overlay-permission-before.txt"
adb shell am force-stop "$package"
adb shell run-as "$package" sha256sum files/datastore/workspaces.preferences_pb files/datastore/shell.preferences_pb > "$report/datastore-before.sha256"
adb shell dumpsys package "$package" > "$report/package-before.txt"
# No uninstall, clear-data, downgrade flag or replacement DataStore fixture.
adb install -r "$RUNNER_TEMP/hyprdroid-distribution/HyprDroid-0.9.0-$CODE_B.apk" > "$report/install-update.txt"
cat "$report/install-update.txt"
grep -q Success "$report/install-update.txt"
adb shell run-as "$package" sha256sum files/datastore/workspaces.preferences_pb files/datastore/shell.preferences_pb > "$report/datastore-after.sha256"
cmp "$report/datastore-before.sha256" "$report/datastore-after.sha256"
fixture verify "$CODE_B" 0.9.0 "$HYPRDROID_BUILD_COMMIT"
adb shell appops get "$package" SYSTEM_ALERT_WINDOW > "$report/overlay-permission-after.txt"
adb shell dumpsys package "$package" > "$report/package-after.txt"
if [ "${HYPRDROID_TEST_EXTERNAL_LUA:-false}" = true ]; then
  worker=dev.hyprdroid.launcher.external.ExternalLuaAndroidTest
  tests="$worker#enabledAppUsesPrivateWorkerAndItsOwnPackageStorage,$worker#arenaAndInstructionLimitsFailWithoutCrashingCore,$worker#twoSimultaneousRequestsCarryIndependentStateAndRejectUnknownEvent,$worker#rejectsInvalidSourceAndPrivilegedLibraries"
  adb shell am instrument -w -r -e class "$tests,dev.hyprdroid.launcher.external.ExternalLuaProtocolTest" \
    "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$report/external-lua.txt"
  cat "$report/external-lua.txt"
  grep -q 'OK (7 tests)' "$report/external-lua.txt"
fi
if [ "${HYPRDROID_TEST_V2_FIXTURES:-false}" = true ]; then
  adb shell am instrument -w -r -e class dev.hyprdroid.launcher.external.ExternalV2WorkerTest     "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$report/external-lua-v2.txt"
  cat "$report/external-lua-v2.txt"
  grep -q 'OK (2 tests)' "$report/external-lua-v2.txt"
fi
if [ "${HYPRDROID_TEST_V2_OFFICIAL:-false}" = true ]; then
  if [ "${HYPRDROID_TEST_OFFICIAL_UPGRADE:-false}" = true ]; then
    official_case verifyOfficialUpgrade
  else
    official_case migrateLegacy
  fi
  # The replacement check above has finished. This emulator now tests pristine package storage.
  adb shell pm clear "$package" | grep -F Success
  official_case networkInstall
fi
sh scripts/ci-home.sh select > "$report/home-registration.txt"
cat "$report/home-registration.txt"
echo "Update v0.9.0 ($baseline_code) -> v0.9.0 ($CODE_B) succeeded; UID, firstInstallTime and DataStore preserved" | tee "$report/result.txt"

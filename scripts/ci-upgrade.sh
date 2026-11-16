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
cp "$RUNNER_TEMP/hyprdroid-upgrade/a.json" "$report/build-a.json"
cp "$RUNNER_TEMP/hyprdroid-distribution/HyprDroid-0.7.1-$CODE_B.json" "$report/build-b.json"
adb install "$RUNNER_TEMP/hyprdroid-upgrade/a.apk"
adb install "$RUNNER_TEMP/hyprdroid-upgrade/test.apk"
adb shell appwidget grantbind --package "$package" --user 0
fixture seed 10045 0.7.0 2a5b856655f1d471f15cf9a4ebd05033c4b919c4
adb shell appwidget revokebind --package "$package" --user 0
# Simulate an already opted-in installation, including Android's actual special permission.
adb shell appops set "$package" SYSTEM_ALERT_WINDOW allow
adb shell appops get "$package" SYSTEM_ALERT_WINDOW > "$report/overlay-permission-before.txt"
adb shell am force-stop "$package"
adb shell run-as "$package" sha256sum files/datastore/workspaces.preferences_pb files/datastore/shell.preferences_pb > "$report/datastore-before.sha256"
adb shell dumpsys package "$package" > "$report/package-before.txt"
# No uninstall, clear-data, downgrade flag or replacement DataStore fixture.
adb install -r "$RUNNER_TEMP/hyprdroid-distribution/HyprDroid-0.7.1-$CODE_B.apk" > "$report/install-update.txt"
cat "$report/install-update.txt"
grep -q Success "$report/install-update.txt"
adb shell run-as "$package" sha256sum files/datastore/workspaces.preferences_pb files/datastore/shell.preferences_pb > "$report/datastore-after.sha256"
cmp "$report/datastore-before.sha256" "$report/datastore-after.sha256"
fixture verify "$CODE_B" 0.7.1 "$HYPRDROID_BUILD_COMMIT"
adb shell appops get "$package" SYSTEM_ALERT_WINDOW > "$report/overlay-permission-after.txt"
adb shell dumpsys package "$package" > "$report/package-after.txt"
echo "Update v0.7 (10045) -> v0.7.1 ($CODE_B) succeeded; UID, firstInstallTime and DataStore preserved" | tee "$report/result.txt"

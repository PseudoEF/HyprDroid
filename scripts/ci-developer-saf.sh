#!/bin/sh
set -eu
adb install "$RUNNER_TEMP/developer-test.apk"
sh scripts/ci-developer-import.sh

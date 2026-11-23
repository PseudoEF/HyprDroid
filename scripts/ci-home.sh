#!/bin/sh
set -eu

component=dev.hyprdroid.launcher/.MainActivity

case "${1:-check}" in
  select) adb shell cmd package set-home-activity --user 0 "$component" ;;
  check) ;;
  *) exit 2 ;;
esac

candidates=$(adb shell cmd package query-activities --brief --user 0 \
  -a android.intent.action.MAIN -c android.intent.category.HOME -c android.intent.category.DEFAULT)
printf '%s\n' "$candidates"
printf '%s\n' "$candidates" | awk -v component="$component" 'NF == 1 && $1 == component { found=1 } END { exit !found }'

if [ "${1:-check}" = select ]; then
    resolved=$(adb shell cmd package resolve-activity --brief --user 0 \
      -a android.intent.action.MAIN -c android.intent.category.HOME -c android.intent.category.DEFAULT)
    printf '%s\n' "$resolved"
    printf '%s\n' "$resolved" | awk -v component="$component" 'NF == 1 && $1 == component { found=1 } END { exit !found }'
fi

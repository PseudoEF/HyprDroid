#!/bin/sh
# Verify actual packaged manifests and code, using Android SDK tools.
set -eu

analyzer="$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer"
ordinary="$1"
developer="$2"
reports=$(mktemp -d)
trap 'rm -rf "$reports"' EXIT

test "$("$analyzer" manifest application-id "$ordinary")" = dev.hyprdroid.launcher
test "$("$analyzer" manifest application-id "$developer")" = dev.hyprdroid.launcher.developer
"$analyzer" manifest print "$ordinary" > "$reports/ordinary.xml"
"$analyzer" manifest print "$developer" > "$reports/developer.xml"
"$analyzer" dex packages --defined-only "$ordinary" > "$reports/ordinary.dex"
"$analyzer" dex packages --defined-only "$developer" > "$reports/developer.dex"

python3 - "$reports" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

reports = Path(sys.argv[1])
android = "{http://schemas.android.com/apk/res/android}"
component = "dev.hyprdroid.launcher.developer.DeveloperActivity"

def activities_with(root, category):
    return [
        item.get(android + "name")
        for item in root.findall("./application/activity") + root.findall("./application/activity-alias")
        if any(
            any(action.get(android + "name") == "android.intent.action.MAIN" for action in intent.findall("action"))
            and any(entry.get(android + "name") == category for entry in intent.findall("category"))
            for intent in item.findall("intent-filter")
        )
    ]

ordinary = ET.parse(reports / "ordinary.xml")
developer = ET.parse(reports / "developer.xml")
assert activities_with(ordinary, "android.intent.category.HOME") == ["dev.hyprdroid.launcher.MainActivity"]
assert not activities_with(developer, "android.intent.category.HOME")
assert activities_with(developer, "android.intent.category.LAUNCHER") == [component]
developer_dex = (reports / "developer.dex").read_text()
assert component in developer_dex
assert "dev.hyprdroid.launcher.developer.DeveloperPackageStore" in developer_dex
assert "dev.hyprdroid.launcher.developer.DeveloperCommands" in developer_dex
assert all(service.get(android + "name") != "dev.hyprdroid.launcher.external.ExternalLuaService"
           for service in developer.findall("./application/service"))
assert "dev.hyprdroid.launcher.developer." not in (reports / "ordinary.dex").read_text()
assert component not in (reports / "ordinary.xml").read_text()

for root in (ordinary, developer):
    assert root.getroot().get(android + "sharedUserId") is None
    assert root.find("application").get(android + "allowBackup") == "false"
PY

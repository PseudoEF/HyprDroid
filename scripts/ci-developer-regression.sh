#!/bin/sh
status=0
sh scripts/ci-developer-edition.sh || status=1
sh scripts/ci-developer-import.sh || status=1
sh scripts/ci-developer-workspace.sh || status=1
exit "$status"

#!/usr/bin/env bash
# pull-log.sh — retrieve the app log from the device.
set -euo pipefail
OUT="${1:-probe.log}"
adb pull /sdcard/Android/data/com.ytmlauncher/files/probe.log "$OUT"
echo "---"
cat "$OUT"

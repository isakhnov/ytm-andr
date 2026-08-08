#!/usr/bin/env bash
# pull-log.sh — retrieve the probe log from the device.
set -euo pipefail
OUT="${1:-probe.log}"
adb pull /sdcard/Android/data/com.ytmprobe/files/probe.log "$OUT"
echo "---"
cat "$OUT"

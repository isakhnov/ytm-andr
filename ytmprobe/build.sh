#!/usr/bin/env bash
# build.sh — build and install the probe from the command line. No Android Studio.
set -euo pipefail

SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
[[ -d "$SDK" ]] || { echo "SDK not found at $SDK — set ANDROID_HOME"; exit 1; }

echo "sdk.dir=$SDK" > local.properties
echo "using SDK: $SDK"

# --- JDK selection -------------------------------------------------------
# AGP 8.5 / Gradle 8.7 support Java 17-22. A newer JDK (23+) fails with an
# unhelpful bare version number as the error message. Pin a supported one.
pick_jdk() {
  local v
  for v in 21 17; do
    if /usr/libexec/java_home -v "$v" >/dev/null 2>&1; then
      /usr/libexec/java_home -v "$v"; return 0
    fi
  done
  return 1
}

if [[ -z "${JAVA_HOME:-}" ]] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -qE '"(17|21)'; then
  if JH="$(pick_jdk)"; then
    export JAVA_HOME="$JH"
    echo "pinned JAVA_HOME=$JAVA_HOME"
  else
    echo
    echo "No Java 17 or 21 found."
    echo "Your default JDK is:"
    java -version 2>&1 | sed 's/^/    /'
    echo
    echo "Gradle 8.7 / AGP 8.5 do not support JDK 23+. Install a supported one:"
    echo "    brew install --cask temurin@21"
    echo "then re-run ./build.sh"
    exit 1
  fi
fi
echo "java: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"

if [[ ! -x ./gradlew ]]; then
  command -v gradle >/dev/null || { echo "no gradle — brew install gradle"; exit 1; }
  echo "generating wrapper (gradle 8.7)..."
  gradle wrapper --gradle-version 8.7 --no-daemon
fi

echo "accepting licenses..."
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true

echo "building..."
./gradlew assembleDebug --no-daemon

APK="app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$APK" ]] || { echo "build produced no apk"; exit 1; }
echo
echo "built: $APK"

if adb devices | grep -qw device; then
  echo "installing..."
  adb install -r "$APK"
  adb shell am start -n com.ytmprobe/.MainActivity
  echo "launched."
else
  echo "no device attached — install later with:"
  echo "  adb install -r $APK"
fi

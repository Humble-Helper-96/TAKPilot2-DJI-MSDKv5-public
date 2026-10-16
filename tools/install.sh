#!/usr/bin/env bash
#
# Build, bump and install — the ONLY way a build should reach the controller.
#
# ⚠ EVERY INSTALL GETS A NEW versionCode, AND THAT IS THE POINT. Builds that share a versionCode
# cannot be told apart by `pm dumpsys` on the device, so there is no way to know whether the
# aircraft is flying the build you just fixed. `BUILD_TIME` does not help either: it is stamped
# in UTC during Gradle CONFIGURATION, so it reads a couple of minutes earlier than the APK. A
# build you cannot identify from the device is a build you cannot flight-test.
#
# It also verifies the install actually took. `adb install` can print "Performing Streamed
# Install" and then fail, and a half-read success message is how the wrong build gets flown.
#
# Usage:  tools/install.sh ["what this build is for"]
set -euo pipefail

cd "$(dirname "$0")/.."
GRADLE_FILE="app/build.gradle"
NOTE="${1:-}"
PKG=$(grep -oP '^\s*applicationId "\K[^"]+' "$GRADLE_FILE")

CURRENT=$(grep -oP '^\s*versionCode \K[0-9]+' "$GRADLE_FILE")
NEXT=$((CURRENT + 1))

# Replace only the real declaration, never a commented line.
sed -i "0,/^\(\s*\)versionCode ${CURRENT}$/s//\1versionCode ${NEXT}/" "$GRADLE_FILE"
NOW=$(grep -oP '^\s*versionCode \K[0-9]+' "$GRADLE_FILE")
if [ "$NOW" != "$NEXT" ]; then
    echo "versionCode did not move ($CURRENT -> $NOW). Refusing to build." >&2
    exit 1
fi
echo "versionCode $CURRENT -> $NEXT${NOTE:+  ($NOTE)}"

./gradlew :app:assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || { echo "No APK at $APK" >&2; exit 1; }

OUT=$(adb install -r "$APK" 2>&1) || { echo "$OUT" >&2; exit 1; }
echo "$OUT" | grep -q "Success" || { echo "INSTALL FAILED:"; echo "$OUT"; exit 1; }

# ⚠ Confirm from the DEVICE, not from the installer's own output.
ON_DEVICE=$(adb shell dumpsys package "$PKG" | grep -oP 'versionCode=\K[0-9]+' | head -1)
if [ "$ON_DEVICE" != "$NEXT" ]; then
    echo "The device reports versionCode $ON_DEVICE, expected $NEXT." >&2
    exit 1
fi

echo "installed and verified on the controller: versionCode $ON_DEVICE"

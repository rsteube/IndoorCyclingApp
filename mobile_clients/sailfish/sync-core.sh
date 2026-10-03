#!/usr/bin/env bash
# Builds the shared Kotlin core into lib/, and copies the package's data into data/: the two things
# the .pro takes from outside this directory.
#
# sfdk builds from a copy of this directory, so both have to be inside it; lib/ and data/ are
# git-ignored. The .pro checks only that they exist, not that they are current, so run this before
# every sfdk build that should carry a change to shared/ or to core/. build.sh does.
#
#   ./sync-core.sh            release library, the one an RPM ships
#   ./sync-core.sh debug      debug library, with Kotlin's assertions and symbols
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
variant="${1:-release}"
case "$variant" in
    release) task=linkReleaseSharedLinuxArm64; out=releaseShared ;;
    debug)   task=linkDebugSharedLinuxArm64;   out=debugShared ;;
    *) echo "usage: $0 [release|debug]" >&2; exit 2 ;;
esac

# Kotlin 2.2 predates JDK 25; use 21 where it is installed.
if [ -z "${JAVA_HOME:-}" ] && [ -d /usr/lib/jvm/java-21-openjdk-amd64 ]; then
    export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
fi

if [ ! -e "$here/core/sailfish-sysroot/lib/libsqlite3.so" ]; then
    "$here/core/stage-sysroot.sh"
fi

"$repo/gradlew" -p "$here/core" "$task" --console=plain -q

mkdir -p "$here/lib"
cp -f "$here/core/build/bin/linuxArm64/$out/libindoorbikecore.so" "$here/lib/"
cp -f "$here/core/build/bin/linuxArm64/$out/libindoorbikecore_api.h" "$here/lib/"
echo "Synced the $variant core into $here/lib"

# The package's data, from the Android app's assets so there is one copy: the bundled workouts.
# Mode 644 whatever the source: Harbour refuses an executable data file.
android_assets="$repo/mobile_clients/android/src/main/assets"
rm -rf "$here/data"
mkdir -p "$here/data"
install -m 644 "$android_assets/workouts_sample.json" "$here/data/"
echo "Staged the bundled workouts into $here/data"

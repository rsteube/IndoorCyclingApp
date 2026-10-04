#!/bin/sh
# Copies the phone's libraries that the Kotlin/Native linker needs out of the
# Sailfish SDK's build engine, so libindoorbikecore.so can be linked on the host.
#
# Kotlin/Native ships its own aarch64 sysroot with libc and little else. The
# SDK's target has the right libraries, but inside the build engine's container,
# and only as the versioned .so.N file: the bare .so the linker looks for comes
# from a -devel package the target does not carry.
#
# Two are staged: libsqlite3, which SQLDelight's native driver links, and
# libcrypto from OpenSSL 3, for the Zwift controllers' curve and cipher should
# the OpenSSL Ktor links in statically ever go (see libcrypto.def). Both
# are on Harbour's allowed list and ship on every phone. Each is named by the
# SONAME the core must ask for, since the target also carries OpenSSL 1.1's
# libcrypto.so.1.1, and each keeps that SONAME, so the linked core asks for the
# file the phone really has.
#
# Taken from nextcloud-sailfish's core/sailfish/stage-sysroot.sh.
#
# Usage: mobile_clients/sailfish/core/stage-sysroot.sh [target-name]
# Writes mobile_clients/sailfish/core/sailfish-sysroot/lib (git-ignored).
set -eu

TARGET="${1:-}"
ENGINE="$(docker ps --format '{{.Names}}' | grep '^sailfish-sdk-build-engine' | head -1 || true)"
OUT="$(cd "$(dirname "$0")" && pwd)/sailfish-sysroot"

if [ -z "$ENGINE" ]; then
    echo "The Sailfish SDK build engine is not running. Start it (sfdk engine start) and run this again." >&2
    exit 1
fi

if [ -z "$TARGET" ]; then
    TARGET="$(docker exec "$ENGINE" sh -c 'ls /srv/mer/targets' | grep -- '-aarch64$' | sort | tail -1)"
fi
if [ -z "$TARGET" ]; then
    echo "No aarch64 target in the build engine. Install one with the SDK." >&2
    exit 1
fi

echo "Staging from $TARGET into $OUT"
mkdir -p "$OUT/lib"

for wanted in libsqlite3.so.0 libcrypto.so.3; do
    lib="${wanted%%.so.*}"
    # Resolved inside the container: docker cp copies a symlink as a symlink,
    # which would land here pointing at a path that does not exist.
    file="$(docker exec "$ENGINE" sh -c \
        "readlink -f /srv/mer/targets/$TARGET/usr/lib64/$wanted 2>/dev/null")"
    if [ -z "$file" ]; then
        echo "  $lib: not in the target" >&2
        exit 1
    fi
    real="$(basename "$file")"
    docker cp "$ENGINE:$file" "$OUT/lib/$real"
    soname="$(docker exec "$ENGINE" sh -c "readelf -d $file 2>/dev/null" | sed -n 's/.*SONAME.*\[\(.*\)\].*/\1/p')"
    [ -n "$soname" ] && ln -sf "$real" "$OUT/lib/$soname"
    ln -sf "$real" "$OUT/lib/$lib.so"
    echo "  $lib <- $real"
done

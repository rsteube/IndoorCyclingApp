#!/usr/bin/env bash
# Puts lib/libindoorbikecore.so and data/ where a debug build pushed by sailfish-mcp finds them.
#
# sailfish-mcp's push mode copies the app binary and its QML only, to
# ~/sailfish-mcp-app/<app>/ on the phone. The binary finds the core through the rpath
# $ORIGIN/../share/<app>/lib, which from there is ~/sailfish-mcp-app/share/<app>/lib. Run this
# after ./sync-core.sh whenever the core changed, then deploy_and_launch as usual.
#
# The phone is reached as "jolla", an ssh alias; set SAILFISH_HOST for another.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
host="${SAILFISH_HOST:-jolla}"
app="harbour-indoorbike"
lib="$here/lib/libindoorbikecore.so"

[ -f "$lib" ] || { echo "$lib is missing. Run ./sync-core.sh first." >&2; exit 1; }
remote="sailfish-mcp-app/share/$app"
ssh -o BatchMode=yes "$host" "mkdir -p '$remote/lib' '$remote/data'"
scp -q -o BatchMode=yes "$lib" "$host:$remote/lib/"
echo "Pushed $(du -h "$lib" | cut -f1) core to $host:~/$remote/lib"
if [ -d "$here/data" ]; then
    scp -q -r -o BatchMode=yes "$here/data/." "$host:$remote/data/"
    echo "Pushed data/ to $host:~/$remote/data"
fi

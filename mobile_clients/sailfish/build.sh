#!/usr/bin/env bash
# Builds the release RPM the one correct way: the core relinked and copied in, the QML checked,
# then sfdk.
#
# Linking the core and calling sfdk by hand builds an RPM against whichever copy of the library
# was lying in lib/, which behaves like an older version of the core, or crashes before any QML
# loads when the shared code's public API moved. QML is not compiled, so a syntax error in it is no
# build failure either: qmllint is the only check before the phone.
#
#   ./build.sh              RPMS/harbour-indoorbike-<version>-<release>.aarch64.rpm
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
cd "$here"

./sync-core.sh release

if command -v qmllint-qt5 >/dev/null || command -v qmllint >/dev/null; then
    lint="$(command -v qmllint-qt5 || command -v qmllint)"
    find qml -name '*.qml' -print0 | xargs -0 "$lint"
fi

exec sfdk -c "target=${SAILFISH_TARGET:-SailfishOS-5.1.0.11-aarch64}" build

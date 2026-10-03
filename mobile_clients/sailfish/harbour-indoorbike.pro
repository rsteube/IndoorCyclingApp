# IndoorBike for Sailfish OS. See docs/SAILFISH_PORT.md.
#
# TARGET names the binary, the main QML file, the desktop file, the icons and the spec. Rename
# them together: SailfishApp::main finds the QML by this name, and a mismatch launches to nothing.
TARGET = harbour-indoorbike

CONFIG += sailfishapp
# BlueZ is reached over the system bus. HTTP is not Qt's: it runs in the shared core, because
# Qt 5.6's TLS fails against current servers on Sailfish OS 5.1.
QT += dbus
# sailfishapp turns pkg-config linking on itself; adding link_pkgconfig as well runs it before
# sailfishapp adds its own library, and the link fails on SailfishApp::application.
PKGCONFIG += keepalive

# The spec passes its Version in. A build made outside the spec (sailfish-mcp's debug build) takes
# the spec's and is marked "-dev", so it is never mistaken for the installed app beside it.
isEmpty(VERSION): VERSION = $$system(sed -n 's/^Version:[[:space:]]*//p' $$PWD/rpm/harbour-indoorbike.spec)
APP_VERSION_SHOWN = $$VERSION
CONFIG(inspectsurface) {
    APP_VERSION_SHOWN = $${VERSION}-dev
    # The demo trainer and the other developer conveniences, as in Android's debug build.
    DEFINES += INDOORBIKE_DEV
}
DEFINES += APP_VERSION=\\\"$$APP_VERSION_SHOWN\\\"

HEADERS += \
    src/bluetransport.h \
    src/bluez.h \
    src/indoorbikecore.h \
    src/keyedlistmodel.h \
    src/notifypipe.h

SOURCES += \
    src/bluetransport.cpp \
    src/bluez.cpp \
    src/harbour-indoorbike.cpp \
    src/indoorbikecore.cpp \
    src/indoorbikecorestate.cpp \
    src/keyedlistmodel.cpp \
    src/notifypipe.cpp

DISTFILES += \
    qml/harbour-indoorbike.qml \
    qml/cover/*.qml \
    qml/pages/*.qml \
    qml/components/*.qml \
    rpm/harbour-indoorbike.spec \
    rpm/harbour-indoorbike.changes \
    harbour-indoorbike.desktop

SAILFISHAPP_ICONS = 86x86 108x108 128x128 172x172

# The shared Kotlin, compiled for the phone by core/ and copied into lib/ by sync-core.sh. It has to
# be inside this directory: sfdk builds from a copy of it.
CORE_DIR = $$PWD/lib
CORE_LIB = $$CORE_DIR/libindoorbikecore.so
!exists($$CORE_LIB) {
    error("$$CORE_LIB is missing. Run ./sync-core.sh first; see docs/SAILFISH_PORT.md.")
}
INCLUDEPATH += $$CORE_DIR
LIBS += -L$$CORE_DIR -lindoorbikecore

# Harbour's place for an app's own libraries. Two rpath entries: the installed place, and the same
# place relative to the binary, which is how a build pushed under the home directory with
# share/<TARGET>/lib beside it finds it too. Both given to the linker directly: QMAKE_RPATHDIR
# prefixes the SDK's sysroot, and the binary would carry a path from the build machine.
CORE_INSTALL_DIR = /usr/share/$${TARGET}/lib
QMAKE_LFLAGS += -Wl,-rpath,$$CORE_INSTALL_DIR
QMAKE_LFLAGS += -Wl,-rpath,\'\$\$ORIGIN/../share/$${TARGET}/lib\'
corelib.files = $$CORE_LIB
corelib.path = $$CORE_INSTALL_DIR
INSTALLS += corelib

# No sailfishapp_i18n yet: without a translations/ folder its lupdate fails quietly on every build.
# Every visible string goes through qsTr() or tr(), so a language is a translations/<TARGET>-<lang>.ts
# listed in TRANSLATIONS, and sailfishapp_i18n turned on.

# sailfish-mcp's inspect surface, which lets the MCP drive a debug build on the phone. The MCP's
# inspect_vendor copies it in, and it stays out of git: it contains em dashes, which this
# repository does not allow. It compiles to nothing without CONFIG+=inspectsurface.
exists($$PWD/harbour-inspect/harbour-inspect.pri): include($$PWD/harbour-inspect/harbour-inspect.pri)

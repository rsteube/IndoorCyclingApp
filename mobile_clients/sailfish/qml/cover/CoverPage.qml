import QtQuick 2.6
import Sailfish.Silica 1.0

CoverBackground {
    readonly property var trainer: core.state.trainer || ({})

    CoverPlaceholder {
        visible: !trainer.connected
        text: qsTr("IndoorBike")
        icon.source: "/usr/share/icons/hicolor/86x86/apps/harbour-indoorbike.png"
    }

    Column {
        visible: !!trainer.connected
        anchors.centerIn: parent
        spacing: Theme.paddingSmall

        Label {
            anchors.horizontalCenter: parent.horizontalCenter
            text: trainer.power !== undefined ? trainer.power : "--"
            font.pixelSize: Theme.fontSizeHuge
        }
        Label {
            anchors.horizontalCenter: parent.horizontalCenter
            text: qsTr("watts")
            color: Theme.secondaryColor
        }
    }
}

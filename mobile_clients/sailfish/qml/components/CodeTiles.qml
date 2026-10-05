import QtQuick 2.6
import Sailfish.Silica 1.0

// The pairing code, one character to a tile, large enough to read across a room. A new code turns
// its tiles over one after another, so a changed code is noticed.
Row {
    id: root

    property string code

    readonly property real tileWidth: Math.min(Theme.itemSizeMedium * 1.15,
                                               ((parent ? parent.width : 0) - 2 * Theme.horizontalPageMargin
                                                - 5 * spacing) / Math.max(6, code.length))

    spacing: Theme.paddingSmall

    Repeater {
        id: tiles

        model: root.code.length

        Rectangle {
            id: tile

            width: root.tileWidth
            height: width * 1.3
            radius: Theme.paddingMedium
            color: Theme.rgba(Theme.highlightBackgroundColor, 0.22)
            border.color: Theme.rgba(Theme.highlightColor, 0.35)
            border.width: Math.max(1, Math.round(Theme.paddingSmall / 4))

            transform: Scale {
                id: flip

                origin.x: tile.width / 2
                origin.y: tile.height / 2
                yScale: 1
            }

            Label {
                anchors.centerIn: parent
                color: Theme.highlightColor
                font.pixelSize: Math.round(tile.height * 0.55)
                font.family: Theme.fontFamilyHeading
                text: root.code.charAt(index)
            }

            SequentialAnimation {
                id: turn

                PauseAnimation { duration: index * 70 }
                NumberAnimation { target: flip; property: "yScale"; from: 0; to: 1; duration: 320; easing.type: Easing.OutBack }
            }

            Connections {
                target: root
                onCodeChanged: turn.restart()
            }
            Component.onCompleted: turn.restart()
        }
    }
}

import QtQuick 2.6
import Sailfish.Silica 1.0

// Something being looked for or announced: rings spreading out from an icon, slowly, like a sonar.
// Settles to a still, filled disc once it has found what it was waiting for.
Item {
    id: radar

    property string icon
    property color tint: Theme.highlightColor
    // Rings while true; a calm disc once found.
    property bool searching: true
    property bool found

    width: Theme.itemSizeHuge * 1.6
    height: width

    Repeater {
        model: 3

        Rectangle {
            id: ring

            anchors.centerIn: parent
            width: radar.width * 0.38
            height: width
            radius: width / 2
            color: "transparent"
            border.color: radar.tint
            border.width: Math.max(1, Math.round(Theme.paddingSmall / 3))
            opacity: 0
            visible: radar.searching

            SequentialAnimation {
                running: ring.visible && Qt.application.active
                loops: Animation.Infinite

                PauseAnimation { duration: index * 700 }
                ParallelAnimation {
                    NumberAnimation { target: ring; property: "scale"; from: 1; to: 2.6; duration: 2100; easing.type: Easing.OutQuad }
                    NumberAnimation { target: ring; property: "opacity"; from: 0.55; to: 0; duration: 2100; easing.type: Easing.OutQuad }
                }
                PauseAnimation { duration: (2 - index) * 700 }
            }
        }
    }

    // The found state's halo: one soft pulse when it arrives.
    Rectangle {
        id: halo

        anchors.centerIn: parent
        width: radar.width * 0.38
        height: width
        radius: width / 2
        color: radar.tint
        opacity: 0
    }

    Rectangle {
        id: disc

        anchors.centerIn: parent
        width: radar.width * 0.38
        height: width
        radius: width / 2
        color: Theme.rgba(radar.tint, radar.found ? 0.9 : 0.2)

        Behavior on color { ColorAnimation { duration: 400 } }

        Glyph {
            anchors.centerIn: parent
            width: parent.width * 0.55
            height: width
            name: radar.found ? "check" : radar.icon
            color: radar.found ? Theme.highlightDimmerColor : radar.tint
        }
    }

    onFoundChanged: {
        if (found)
            arrive.restart()
    }

    ParallelAnimation {
        id: arrive

        SequentialAnimation {
            NumberAnimation { target: disc; property: "scale"; from: 1; to: 1.18; duration: 160; easing.type: Easing.OutQuad }
            NumberAnimation { target: disc; property: "scale"; to: 1; duration: 420; easing.type: Easing.OutBack }
        }
        ParallelAnimation {
            NumberAnimation { target: halo; property: "scale"; from: 1; to: 2.2; duration: 700; easing.type: Easing.OutCubic }
            NumberAnimation { target: halo; property: "opacity"; from: 0.45; to: 0; duration: 700; easing.type: Easing.OutCubic }
        }
    }
}

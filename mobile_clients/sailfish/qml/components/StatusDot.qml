import QtQuick 2.6
import Sailfish.Silica 1.0

// How a device or a service is doing, as a dot: green and steady when it is there, breathing while
// it is being looked for, red when it failed, grey when it is not set up.
Item {
    id: dot

    // "ok", "busy", "bad" or "off".
    property string level: "off"

    readonly property color tone: level === "ok" ? "#1FB373"
                                : level === "bad" ? Theme.errorColor
                                : level === "busy" ? Theme.highlightColor
                                : Theme.rgba(Theme.secondaryColor, 0.6)

    width: Theme.paddingMedium
    height: width

    // A ring spreading out from the dot while something is being looked for.
    Rectangle {
        id: ring

        anchors.centerIn: parent
        width: parent.width
        height: width
        radius: width / 2
        color: "transparent"
        border.color: dot.tone
        border.width: Math.max(1, Math.round(Theme.paddingSmall / 4))
        opacity: 0
        visible: dot.level === "busy"

        ParallelAnimation {
            running: ring.visible && Qt.application.active
            loops: Animation.Infinite
            NumberAnimation { target: ring; property: "scale"; from: 1; to: 2.8; duration: 1400; easing.type: Easing.OutCubic }
            NumberAnimation { target: ring; property: "opacity"; from: 0.8; to: 0; duration: 1400; easing.type: Easing.OutCubic }
        }
    }

    Rectangle {
        anchors.fill: parent
        radius: width / 2
        color: dot.tone

        Behavior on color { ColorAnimation { duration: 250 } }
    }
}

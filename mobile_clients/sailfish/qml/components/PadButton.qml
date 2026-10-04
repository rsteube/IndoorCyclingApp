import QtQuick 2.6
import Sailfish.Silica 1.0

// One button of a companion game's menu pad. Live when the game enabled it; otherwise drawn dead in
// its place, so the pad never moves under a thumb. Pressed, it fills with the game's accent.
BackgroundItem {
    id: root

    property string control
    property string icon

    readonly property var companion: core.state.companion || ({})
    readonly property var controls: companion.controls || null
    readonly property bool live: !!controls && !!controls.enabled && controls.enabled.indexOf(control) >= 0

    width: Theme.itemSizeMedium
    height: Theme.itemSizeMedium
    enabled: live
    highlightedColor: "transparent"
    onClicked: core.send({ "cmd": "companion.press", "control": control })

    Rectangle {
        anchors {
            fill: parent
            margins: Theme.paddingSmall / 2
        }
        radius: Theme.paddingSmall
        color: root.down ? (root.companion.accent || Theme.highlightBackgroundColor)
                         : Theme.rgba(Theme.highlightBackgroundColor, root.live ? 0.25 : 0.08)
    }
    Icon {
        anchors.centerIn: parent
        source: root.icon
        opacity: root.live ? 1 : 0.3
        color: root.down && root.companion.onAccent ? root.companion.onAccent : Theme.primaryColor
    }
}

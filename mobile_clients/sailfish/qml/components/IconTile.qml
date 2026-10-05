import QtQuick 2.6
import Sailfish.Silica 1.0

// An icon on a soft rounded tile of its own colour: what a row is about, at a glance.
Rectangle {
    id: tile

    property alias name: glyph.name
    property color tint: Theme.highlightColor
    property bool highlighted
    // Dimmed for something not set up yet.
    property bool muted
    // Turning, for something under way: a sync, a search.
    property bool spinning

    width: Theme.itemSizeSmall
    height: width
    radius: width * 0.3
    color: Theme.rgba(muted ? Theme.secondaryColor : tint, highlighted ? 0.32 : (muted ? 0.1 : 0.18))

    Behavior on color { ColorAnimation { duration: 150 } }

    Glyph {
        id: glyph

        anchors.centerIn: parent
        width: Math.round(parent.width * 0.6)
        height: width
        color: tile.highlighted ? Theme.highlightColor : (tile.muted ? Theme.secondaryColor : tile.tint)

        RotationAnimator on rotation {
            running: tile.spinning && tile.visible && Qt.application.active
            from: 360
            to: 0
            duration: 1200
            loops: Animation.Infinite
            // Back upright when it stops, not frozen at an angle.
            onRunningChanged: if (!running) glyph.rotation = 0
        }
    }
}

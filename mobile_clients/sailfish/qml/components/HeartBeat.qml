import QtQuick 2.6
import Sailfish.Silica 1.0

// A heart that beats at the rider's own heart rate. Still when there is no reading.
Glyph {
    id: heart

    property int bpm

    name: "favorite_fill"
    width: Theme.iconSizeSmall
    height: width
    transformOrigin: Item.Center

    SequentialAnimation {
        id: beat

        NumberAnimation { target: heart; property: "scale"; to: 1.28; duration: 90; easing.type: Easing.OutQuad }
        NumberAnimation { target: heart; property: "scale"; to: 1; duration: 260; easing.type: Easing.OutBack }
    }

    Timer {
        // Faster than 220 bpm is a sensor glitch, not a heart; slower than 30 likewise.
        interval: 60000 / Math.max(30, Math.min(220, heart.bpm || 60))
        repeat: true
        running: heart.bpm > 0 && heart.visible && Qt.application.active
        onTriggered: beat.restart()
    }
}

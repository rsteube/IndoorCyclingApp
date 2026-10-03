import QtQuick 2.6
import Sailfish.Silica 1.0
import "Format.js" as Format

// A workout's shape: each segment a bar as long as its time and as tall as its share of FTP, in
// its zone's colour. Rectangles rather than a Canvas, which goes blank after the app has been in
// the background on Sailfish.
Item {
    id: root

    property var segments: []
    // Seconds into the workout, to dim what has been ridden; negative for none.
    property int elapsed: -1
    readonly property var zones: core.state.zones || []

    readonly property int totalSeconds: {
        var total = 0
        for (var i = 0; i < segments.length; ++i)
            total += segments[i].seconds
        return total
    }
    readonly property real maxPct: {
        var max = 120
        for (var i = 0; i < segments.length; ++i)
            max = Math.max(max, segments[i].pct)
        return max
    }

    Row {
        anchors.fill: parent

        Repeater {
            model: root.segments

            Item {
                width: root.totalSeconds > 0 ? root.width * modelData.seconds / root.totalSeconds : 0
                height: root.height

                Rectangle {
                    anchors.bottom: parent.bottom
                    width: parent.width
                    height: Math.max(Theme.paddingSmall / 2, root.height * modelData.pct / root.maxPct)
                    color: Format.zoneColor(root.zones, modelData.zone, Theme.highlightColor)
                    opacity: 0.85
                }
            }
        }
    }

    // What has been ridden, dimmed.
    Rectangle {
        visible: root.elapsed >= 0 && root.totalSeconds > 0
        anchors {
            left: parent.left
            top: parent.top
            bottom: parent.bottom
        }
        width: root.totalSeconds > 0 ? root.width * Math.min(root.elapsed, root.totalSeconds) / root.totalSeconds : 0
        color: Theme.rgba(Theme.overlayBackgroundColor, Theme.opacityHigh)
    }
    Rectangle {
        visible: root.elapsed >= 0 && root.totalSeconds > 0
        x: root.totalSeconds > 0 ? root.width * Math.min(root.elapsed, root.totalSeconds) / root.totalSeconds - width / 2 : 0
        width: Theme.paddingSmall / 2
        height: parent.height
        color: Theme.primaryColor
    }
}

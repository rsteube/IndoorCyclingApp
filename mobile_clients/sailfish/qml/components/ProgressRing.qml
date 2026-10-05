import QtQuick 2.6
import Sailfish.Silica 1.0

// A ring that fills clockwise from the top: how far through an interval, a level, a ride. Glides
// forward to each new value, and starts again from empty without unwinding when the value falls
// back, as it does at the start of the next interval.
Canvas {
    id: ring

    property real value
    property color color: Theme.highlightColor
    property color trackColor: Theme.rgba(Theme.primaryColor, 0.12)
    property real lineWidth: Theme.paddingMedium
    property int duration: 300
    property int easing: Easing.OutCubic

    property real shown: 0

    function clamp(v) {
        return Math.max(0, Math.min(1, v || 0))
    }

    onValueChanged: {
        var v = clamp(value)
        // Any step back is a new start: snap, never unwind.
        if (v < shown - 0.01 || !visible) {
            glide.stop()
            shown = v
        } else {
            glide.to = v
            glide.restart()
        }
    }
    Component.onCompleted: shown = clamp(value)

    NumberAnimation {
        id: glide

        target: ring
        property: "shown"
        duration: ring.duration
        easing.type: ring.easing
    }

    antialiasing: true
    onShownChanged: requestPaint()
    onColorChanged: requestPaint()
    onTrackColorChanged: requestPaint()
    onLineWidthChanged: requestPaint()
    onWidthChanged: requestPaint()
    onHeightChanged: requestPaint()

    onPaint: {
        var ctx = getContext("2d")
        ctx.reset()
        var r = Math.min(width, height) / 2 - lineWidth / 2
        if (r <= 0)
            return
        var cx = width / 2
        var cy = height / 2
        ctx.lineWidth = lineWidth
        ctx.lineCap = "round"

        ctx.strokeStyle = trackColor
        ctx.beginPath()
        ctx.arc(cx, cy, r, 0, 2 * Math.PI, false)
        ctx.stroke()

        if (shown <= 0.001)
            return
        ctx.strokeStyle = color
        ctx.beginPath()
        ctx.arc(cx, cy, r, -Math.PI / 2, -Math.PI / 2 + 2 * Math.PI * shown, false)
        ctx.stroke()
    }
}

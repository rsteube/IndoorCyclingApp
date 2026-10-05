import QtQuick 2.6
import Sailfish.Silica 1.0
import "Icons.js" as Icons

// A Material Symbols icon, drawn from its path rather than shipped as a picture: it is sharp at any
// size, needs no image plugin, and takes any colour, so it follows the ambience and the pressed
// highlight like the text beside it.
Canvas {
    id: glyph

    property string name
    property color color: Theme.primaryColor

    implicitWidth: Theme.iconSizeMedium
    implicitHeight: implicitWidth
    antialiasing: true

    onNameChanged: requestPaint()
    onColorChanged: requestPaint()
    onWidthChanged: requestPaint()
    onHeightChanged: requestPaint()

    onPaint: {
        var ctx = getContext("2d")
        ctx.reset()
        var d = Icons.paths[name]
        if (!d || width <= 0 || height <= 0)
            return
        var s = Math.min(width, height) / 960
        ctx.translate((width - 960 * s) / 2, (height - 960 * s) / 2)
        ctx.scale(s, s)
        // The glyphs are drawn in a box from y = -960 to 0.
        ctx.translate(0, 960)
        ctx.fillStyle = color
        ctx.path = d
        ctx.fill()
    }
}

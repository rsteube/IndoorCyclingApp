import QtQuick 2.6
import Sailfish.Silica 1.0
import "Icons.js" as Icons

// A Material Symbols icon, made into a one-path SVG in its own colour: sharp at any size, and it
// follows the ambience and the pressed highlight like the text beside it. An image rather than a
// Canvas, because a Canvas comes back empty after Sailfish takes a backgrounded app's graphics
// away, and an image is reloaded by itself.
Image {
    id: glyph

    property string name
    property color color: Theme.primaryColor

    // "#aarrggbb" is not SVG: the colour goes in as rgb() and its alpha as an opacity.
    readonly property string svg: {
        var d = Icons.paths[name]
        if (!d)
            return ""
        var fill = "rgb(" + Math.round(color.r * 255) + "," + Math.round(color.g * 255) + ","
                   + Math.round(color.b * 255) + ")"
        return "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 -960 960 960'>"
               + "<path fill='" + fill + "' fill-opacity='" + color.a.toFixed(3) + "' d='" + d + "'/></svg>"
    }

    // An Image's implicit size is its picture's and read-only: the defaults go on the size itself.
    width: Theme.iconSizeMedium
    height: width
    sourceSize.width: Math.max(1, Math.round(width))
    sourceSize.height: Math.max(1, Math.round(height))
    fillMode: Image.PreserveAspectFit
    smooth: true
    source: svg.length > 0 ? "data:image/svg+xml;utf8," + encodeURIComponent(svg) : ""
}

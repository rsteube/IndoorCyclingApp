import QtQuick 2.6
import Sailfish.Silica 1.0
import harbour.indoorbike 1.0

// The road on OpenStreetMap's map, framed to fit, with the part ridden and where the rider is, as
// the Android app shows a route. The core fetches each tile once and keeps it (MapTiles.kt), and
// they are dimmed for a ride screen (src/maptiles.cpp). With no network the road is drawn on an
// empty ground and nothing else changes.
Item {
    id: root

    // [latitude, longitude] pairs along the road.
    property var points: []
    property real fraction: -1
    property alias color: shape.color
    property alias riddenColor: shape.riddenColor
    property alias markerColor: shape.markerColor
    property alias lineWidth: shape.lineWidth

    // Tiles drawn at the phone's scale, so the map's labels can be read on it.
    readonly property real tileSize: 256 * Math.max(1, Theme.pixelRatio)
    readonly property var frame: frameFor(points, width, height)

    function mercator(latitude, longitude) {
        var lat = Math.max(-85.0511, Math.min(85.0511, latitude)) * Math.PI / 180
        return { "x": (longitude + 180) / 360,
                 "y": (1 - Math.log(Math.tan(lat) + 1 / Math.cos(lat)) / Math.PI) / 2 }
    }

    // The zoom that fits the whole road with a margin, and the part of the world in view.
    function frameFor(points, w, h) {
        if (!points || points.length < 2 || w <= 0 || h <= 0)
            return null
        var minX = 1, maxX = 0, minY = 1, maxY = 0
        for (var i = 0; i < points.length; ++i) {
            var m = mercator(points[i][0], points[i][1])
            minX = Math.min(minX, m.x); maxX = Math.max(maxX, m.x)
            minY = Math.min(minY, m.y); maxY = Math.max(maxY, m.y)
        }
        var spanX = Math.max(maxX - minX, 1e-7) * 1.2
        var spanY = Math.max(maxY - minY, 1e-7) * 1.2
        var z = Math.floor(Math.log(Math.min(w / (spanX * tileSize), h / (spanY * tileSize))) / Math.LN2)
        z = Math.max(1, Math.min(17, z))
        var world = tileSize * Math.pow(2, z)
        var viewW = w / world
        var viewH = h / world
        return { "z": z, "world": world, "x": (minX + maxX) / 2 - viewW / 2, "y": (minY + maxY) / 2 - viewH / 2,
                 "w": viewW, "h": viewH }
    }

    readonly property var tiles: {
        var f = frame
        if (!f)
            return []
        var n = Math.pow(2, f.z)
        var list = []
        for (var ty = Math.floor(f.y * n); ty <= Math.floor((f.y + f.h) * n); ++ty) {
            if (ty < 0 || ty >= n)
                continue
            for (var tx = Math.floor(f.x * n); tx <= Math.floor((f.x + f.w) * n); ++tx)
                list.push({ "z": f.z, "x": ((tx % n) + n) % n, "y": ty,
                            "left": (tx / n - f.x) * f.world, "top": (ty / n - f.y) * f.world })
        }
        return list
    }

    clip: true

    Rectangle {
        anchors.fill: parent
        color: Theme.rgba(Theme.highlightDimmerColor, 0.5)
    }

    Repeater {
        model: root.tiles

        Image {
            id: tile

            property bool gone: false

            x: modelData.left
            y: modelData.top
            width: root.tileSize
            height: root.tileSize
            asynchronous: true
            smooth: true
            opacity: status === Image.Ready ? 1 : 0

            Behavior on opacity { FadeAnimator { } }

            Component.onCompleted: {
                core.request({ "cmd": "map.tile", "z": modelData.z, "x": modelData.x, "y": modelData.y }, function(reply) {
                    if (reply.ok && tile && !tile.gone)
                        tile.source = "image://maptile" + reply.value
                })
            }
            Component.onDestruction: gone = true
        }
    }

    RouteShape {
        id: shape

        anchors.fill: parent
        points: root.points
        fraction: root.fraction
        mapView: root.frame ? Qt.rect(root.frame.x, root.frame.y, root.frame.w, root.frame.h) : Qt.rect(0, 0, 0, 0)
    }

    // OpenStreetMap's licence asks for this wherever its map is shown.
    Label {
        anchors {
            right: parent.right
            bottom: parent.bottom
            margins: Theme.paddingSmall
        }
        color: Theme.secondaryColor
        font.pixelSize: Theme.fontSizeTiny
        text: "© OpenStreetMap contributors"
    }
}

import QtQuick 2.6
import Sailfish.Silica 1.0
import harbour.indoorbike 1.0

// The road on OpenStreetMap's map, with the part ridden and where the rider is, as the Android
// app shows a route: the whole road framed to fit, or, with [follow], close in on the rider. The
// core fetches each tile once and keeps it (MapTiles.kt), and they are dimmed for a ride screen
// (src/maptiles.cpp). With no network the road is drawn on an empty ground and nothing else
// changes.
Item {
    id: root

    // [latitude, longitude] pairs along the road.
    property var points: []
    property real fraction: -1
    // Close in on the rider rather than framing the whole road; needs the rider's position.
    property bool follow: false
    property real riderLatitude: NaN
    property real riderLongitude: NaN
    property alias color: shape.color
    property alias riddenColor: shape.riddenColor
    property alias markerColor: shape.markerColor
    property alias lineWidth: shape.lineWidth

    // Tiles drawn at the phone's scale, so the map's labels can be read on it.
    readonly property real tileSize: 256 * Math.max(1, Theme.pixelRatio)
    // Close enough to see the road the rider is on, wide enough to see the next bend, as on Android.
    readonly property int followZoom: 15
    readonly property bool following: follow && !isNaN(riderLatitude) && !isNaN(riderLongitude)
    readonly property var frame: following ? frameAround(riderLatitude, riderLongitude, width, height)
                                           : frameFor(points, width, height)

    // The tiles in view, as one key, so the set only changes when the view crosses a tile's edge: a
    // rider moving along does not reload the map four times a second. One key rather than a number
    // each, because numbers updated one at a time once paired one zoom's first column with
    // another's last, and the app froze working through millions of tiles.
    readonly property string rangeKey: frame ? rangeOf(frame).join("/") : ""
    readonly property var range: rangeKey.length > 0 ? rangeKey.split("/").map(Number) : null
    readonly property var tiles: tilesFor(rangeKey)

    // A view needs a handful of tiles; more than this is a frame gone wrong, not a map to load.
    readonly property int maxTiles: 64

    function rangeOf(f) {
        var n = Math.pow(2, f.z)
        return [f.z, Math.floor(f.x * n), Math.floor((f.x + f.w) * n), Math.floor(f.y * n), Math.floor((f.y + f.h) * n)]
    }

    function mercator(latitude, longitude) {
        var lat = Math.max(-85.0511, Math.min(85.0511, latitude)) * Math.PI / 180
        return { "x": (longitude + 180) / 360,
                 "y": (1 - Math.log(Math.tan(lat) + 1 / Math.cos(lat)) / Math.PI) / 2 }
    }

    function view(centerX, centerY, z, w, h) {
        var world = tileSize * Math.pow(2, z)
        return { "z": z, "world": world, "x": centerX - w / world / 2, "y": centerY - h / world / 2,
                 "w": w / world, "h": h / world }
    }

    // The zoom that fits the whole road with a margin.
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
        return view((minX + maxX) / 2, (minY + maxY) / 2, Math.max(1, Math.min(17, z)), w, h)
    }

    function frameAround(latitude, longitude, w, h) {
        if (w <= 0 || h <= 0)
            return null
        var m = mercator(latitude, longitude)
        return view(m.x, m.y, followZoom, w, h)
    }

    function tilesFor(key) {
        if (key.length === 0)
            return []
        var r = key.split("/").map(Number)
        var z = r[0], x0 = r[1], x1 = r[2], y0 = r[3], y1 = r[4]
        if ((x1 - x0 + 1) * (y1 - y0 + 1) > maxTiles)
            return []
        var n = Math.pow(2, z)
        var list = []
        for (var ty = y0; ty <= y1; ++ty) {
            if (ty < 0 || ty >= n)
                continue
            for (var tx = x0; tx <= x1; ++tx)
                list.push({ "z": z, "x": ((tx % n) + n) % n, "y": ty, "column": tx - x0, "row": ty - y0 })
        }
        return list
    }

    clip: true

    Rectangle {
        anchors.fill: parent
        color: Theme.rgba(Theme.highlightDimmerColor, 0.5)
    }

    // The tiles, laid out from the first one in view; the whole layer slides as the view moves.
    Item {
        x: root.frame && root.range ? (root.range[1] / Math.pow(2, root.range[0]) - root.frame.x) * root.frame.world : 0
        y: root.frame && root.range ? (root.range[3] / Math.pow(2, root.range[0]) - root.frame.y) * root.frame.world : 0

        Repeater {
            model: root.tiles

            Image {
                id: tile

                property bool gone: false

                x: modelData.column * root.tileSize
                y: modelData.row * root.tileSize
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

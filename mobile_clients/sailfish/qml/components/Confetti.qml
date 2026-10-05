import QtQuick 2.6
import Sailfish.Silica 1.0

// A burst of confetti in the zone colours, for a finished ride. Falls through and is gone in under
// two seconds; nothing to tap, nothing in the way.
Item {
    id: confetti

    property var colors: ["#9E9E9E", "#2E7DD7", "#1FB373", "#F0C419", "#F28C28", "#E5484D", "#9B59D0"]
    property int pieces: 46

    function burst() {
        for (var i = 0; i < repeater.count; ++i)
            repeater.itemAt(i).launch()
    }

    anchors.fill: parent
    enabled: false

    Repeater {
        id: repeater

        model: confetti.pieces

        Rectangle {
            id: piece

            property real t: 0
            property real vx: 0
            property real vy: 0
            property real spin: 0
            property real ox: confetti.width / 2
            property real oy: confetti.height * 0.18

            function launch() {
                var angle = (Math.random() * 0.8 + 0.1) * Math.PI
                var speed = confetti.width * (0.45 + Math.random() * 0.55)
                vx = Math.cos(angle) * speed
                vy = -Math.sin(angle) * speed * 1.2
                spin = (Math.random() - 0.5) * 900
                color = confetti.colors[Math.floor(Math.random() * confetti.colors.length)]
                width = Theme.paddingSmall * (1.2 + Math.random())
                height = width * (0.4 + Math.random() * 0.8)
                fall.duration = 1400 + Math.random() * 600
                fall.restart()
            }

            // Thrown up and out, then down under gravity.
            x: ox + vx * t - width / 2
            y: oy + vy * t + confetti.height * 1.1 * t * t
            rotation: spin * t
            opacity: t < 0.75 ? 1 : (1 - t) * 4
            visible: t > 0 && t < 1
            radius: width / 5

            NumberAnimation {
                id: fall

                target: piece
                property: "t"
                from: 0
                to: 1
                easing.type: Easing.Linear
            }
        }
    }
}

import QtQuick 2.6
import Sailfish.Silica 1.0

// How strong a Bluetooth device is heard, as four rising bars rather than a number in dBm: the
// closest trainer is the one with the most.
Row {
    id: bars

    property int rssi
    property color color: Theme.highlightColor

    readonly property int level: rssi === 0 ? 0 : rssi > -60 ? 4 : rssi > -70 ? 3 : rssi > -82 ? 2 : 1

    spacing: Math.max(1, Math.round(Theme.paddingSmall / 2))
    height: Theme.iconSizeExtraSmall

    Repeater {
        model: 4

        Rectangle {
            anchors.bottom: parent.bottom
            width: Math.round(Theme.paddingSmall * 1.2)
            height: bars.height * (index + 1) / 4
            radius: width / 3
            color: index < bars.level ? bars.color : Theme.rgba(Theme.secondaryColor, 0.3)

            Behavior on color { ColorAnimation { duration: 200 } }
        }
    }
}

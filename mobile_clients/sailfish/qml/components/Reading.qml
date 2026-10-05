import QtQuick 2.6
import Sailfish.Silica 1.0

// One live number with its unit and name, as the ride screens show them. A numeric reading glides
// between values; one with a `beat` has a heart beside it beating at that rate.
Column {
    id: root

    property string label
    property string value
    property string unit
    property bool large
    property color valueColor: Theme.primaryColor
    // Set `numeric` to glide: the number, its decimals, and whether there is one at all.
    property bool numeric
    property real number
    property int decimals
    property bool available: true
    property int duration: 220
    // Heart rate: a beating heart beside the number.
    property int beat

    spacing: Theme.paddingSmall / 2

    Row {
        anchors.horizontalCenter: parent.horizontalCenter
        spacing: Theme.paddingSmall

        HeartBeat {
            anchors.verticalCenter: valueLabel.verticalCenter
            visible: root.beat > 0
            bpm: root.beat
            color: root.valueColor
        }
        AnimatedNumber {
            id: valueLabel

            value: root.number
            decimals: root.decimals
            available: root.available
            duration: root.duration
            // A plain reading shows its text as given.
            text: root.numeric ? (available ? shown.toFixed(decimals) : placeholder) : root.value
            color: root.valueColor
            font.pixelSize: root.large ? Theme.fontSizeHuge * 1.4 : Theme.fontSizeExtraLarge
            font.family: Theme.fontFamilyHeading

            Behavior on color { ColorAnimation { duration: 300 } }
        }
        Label {
            anchors.baseline: valueLabel.baseline
            text: root.unit
            color: Theme.secondaryColor
            font.pixelSize: Theme.fontSizeSmall
        }
    }

    Label {
        anchors.horizontalCenter: parent.horizontalCenter
        text: root.label
        color: Theme.highlightColor
        font.pixelSize: Theme.fontSizeExtraSmall
    }
}

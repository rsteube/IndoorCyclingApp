import QtQuick 2.6
import Sailfish.Silica 1.0

// One live number with its unit and name, as the ride screens show them.
Column {
    id: root

    property string label
    property string value
    property string unit
    property bool large
    property color valueColor: Theme.primaryColor

    spacing: Theme.paddingSmall / 2

    Row {
        anchors.horizontalCenter: parent.horizontalCenter
        spacing: Theme.paddingSmall

        Label {
            id: valueLabel

            text: root.value
            color: root.valueColor
            font.pixelSize: root.large ? Theme.fontSizeHuge * 1.4 : Theme.fontSizeExtraLarge
            font.family: Theme.fontFamilyHeading
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

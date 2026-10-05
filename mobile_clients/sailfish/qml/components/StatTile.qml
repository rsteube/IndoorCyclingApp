import QtQuick 2.6
import Sailfish.Silica 1.0

// One headline number on a soft tile, tappable to change it: FTP, weight, a week's rides.
BackgroundItem {
    id: tile

    property string icon
    property color tint: Theme.highlightColor
    property real value
    property int decimals: 0
    property bool available: true
    property string unit
    property string label

    height: Theme.itemSizeExtraLarge + Theme.paddingLarge
    // A tile that is only information gives no feedback on a tap.
    highlightedColor: enabled ? Theme.rgba(Theme.highlightBackgroundColor, Theme.highlightBackgroundOpacity) : "transparent"

    Rectangle {
        anchors.fill: parent
        anchors.margins: Theme.paddingSmall / 2
        radius: Theme.paddingLarge
        color: Theme.rgba(tile.tint, tile.highlighted ? 0.28 : 0.12)

        Behavior on color { ColorAnimation { duration: 150 } }
    }

    Column {
        anchors.centerIn: parent
        width: parent.width - 2 * Theme.paddingMedium
        spacing: Theme.paddingSmall / 2

        Glyph {
            anchors.horizontalCenter: parent.horizontalCenter
            visible: tile.icon.length > 0
            width: Theme.iconSizeSmall
            height: width
            name: tile.icon
            color: tile.highlighted ? Theme.highlightColor : tile.tint
        }
        Row {
            anchors.horizontalCenter: parent.horizontalCenter
            spacing: Theme.paddingSmall / 2

            AnimatedNumber {
                id: number

                value: tile.value
                decimals: tile.decimals
                available: tile.available
                duration: 600
                color: tile.highlighted ? Theme.highlightColor : Theme.primaryColor
                font.pixelSize: Theme.fontSizeExtraLarge
                font.family: Theme.fontFamilyHeading
            }
            Label {
                anchors.baseline: number.baseline
                visible: tile.available
                color: Theme.secondaryHighlightColor
                font.pixelSize: Theme.fontSizeExtraSmall
                text: tile.unit
            }
        }
        Label {
            width: parent.width
            horizontalAlignment: Text.AlignHCenter
            truncationMode: TruncationMode.Fade
            color: tile.highlighted ? Theme.highlightColor : Theme.secondaryHighlightColor
            font.pixelSize: Theme.fontSizeExtraSmall
            text: tile.label
        }
    }
}

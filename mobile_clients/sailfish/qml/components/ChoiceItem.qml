import QtQuick 2.6
import Sailfish.Silica 1.0

// One way to ride, on the first page: its icon, a title and a line saying what it is. Slides in a
// beat after the one above it the first time the page shows, so the choices arrive as a set.
BackgroundItem {
    id: root

    property string title
    property string description
    property string icon
    property color tint: Theme.highlightColor
    // Its place in the list, for the entrance.
    property int order

    width: parent ? parent.width : 0
    height: Math.max(Theme.itemSizeLarge, texts.height + 2 * Theme.paddingMedium)

    IconTile {
        id: tile

        anchors {
            left: parent.left
            leftMargin: Theme.horizontalPageMargin
            verticalCenter: parent.verticalCenter
        }
        width: Theme.itemSizeMedium * 0.9
        name: root.icon
        tint: root.tint
        highlighted: root.highlighted
        muted: !root.enabled
    }

    Column {
        id: texts

        anchors {
            left: tile.right
            leftMargin: Theme.paddingLarge
            right: chevron.left
            rightMargin: Theme.paddingMedium
            verticalCenter: parent.verticalCenter
        }

        Label {
            width: parent.width
            color: root.highlighted ? Theme.highlightColor : Theme.primaryColor
            opacity: root.enabled ? 1 : Theme.opacityLow
            text: root.title
        }
        Label {
            width: parent.width
            wrapMode: Text.Wrap
            color: root.highlighted ? Theme.secondaryHighlightColor : Theme.secondaryColor
            opacity: root.enabled ? 1 : Theme.opacityLow
            font.pixelSize: Theme.fontSizeExtraSmall
            text: root.description
        }
    }

    Glyph {
        id: chevron

        anchors {
            right: parent.right
            rightMargin: Theme.paddingMedium
            verticalCenter: parent.verticalCenter
        }
        width: Theme.iconSizeSmall
        height: width
        name: "chevron_right"
        color: root.highlighted ? Theme.highlightColor : Theme.secondaryColor
        opacity: root.enabled ? 1 : Theme.opacityLow
    }

    // The entrance: from a little to the right and transparent, into place.
    transform: Translate { id: shift; x: 0 }
    SequentialAnimation {
        id: entrance

        PropertyAction { target: root; property: "opacity"; value: 0 }
        PropertyAction { target: shift; property: "x"; value: Theme.paddingLarge * 2 }
        PauseAnimation { duration: 60 + root.order * 55 }
        ParallelAnimation {
            NumberAnimation { target: root; property: "opacity"; to: 1; duration: 320; easing.type: Easing.OutCubic }
            NumberAnimation { target: shift; property: "x"; to: 0; duration: 380; easing.type: Easing.OutCubic }
        }
    }
    Component.onCompleted: entrance.start()
}

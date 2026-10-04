import QtQuick 2.6
import Sailfish.Silica 1.0

// One ride of a companion session, as Android's session panel lists it: its name, what it came
// to, and the screenshots the game sent during it, a tap away from full size.
Column {
    id: root

    property string name
    property string detail
    property string note
    // File paths, oldest first.
    property var screenshots: []

    x: Theme.horizontalPageMargin
    width: parent.width - 2 * Theme.horizontalPageMargin
    spacing: Theme.paddingSmall

    Label {
        width: parent.width
        truncationMode: TruncationMode.Fade
        color: Theme.highlightColor
        text: root.name
    }
    Label {
        width: parent.width
        truncationMode: TruncationMode.Fade
        color: Theme.secondaryHighlightColor
        font.pixelSize: Theme.fontSizeSmall
        text: root.detail
    }
    Label {
        width: parent.width
        visible: root.note.length > 0
        wrapMode: Text.Wrap
        color: Theme.secondaryColor
        font.pixelSize: Theme.fontSizeExtraSmall
        text: root.note
    }
    ScreenshotStrip {
        width: parent.width
        pictures: root.screenshots
        thumbHeight: Theme.itemSizeSmall
        limit: 5
    }
}

import QtQuick 2.6
import Sailfish.Silica 1.0

// One way to ride, on the first page: a title and a line saying what it is.
BackgroundItem {
    id: root

    property string title
    property string description

    width: parent ? parent.width : 0
    height: Math.max(Theme.itemSizeLarge, texts.height + 2 * Theme.paddingMedium)
    opacity: enabled ? 1 : Theme.opacityLow

    Column {
        id: texts

        anchors {
            left: parent.left
            leftMargin: Theme.horizontalPageMargin
            right: parent.right
            rightMargin: Theme.horizontalPageMargin
            verticalCenter: parent.verticalCenter
        }

        Label {
            width: parent.width
            color: root.highlighted ? Theme.highlightColor : Theme.primaryColor
            text: root.title
        }
        Label {
            width: parent.width
            wrapMode: Text.Wrap
            color: root.highlighted ? Theme.secondaryHighlightColor : Theme.secondaryColor
            font.pixelSize: Theme.fontSizeExtraSmall
            text: root.description
        }
    }
}

import QtQuick 2.6
import Sailfish.Silica 1.0

// One step of the way into something: a number that becomes a tick once it is done.
Item {
    id: root

    property int number
    property string title
    property string detail
    property bool done

    width: parent ? parent.width : 0
    height: Math.max(badge.height, texts.height) + Theme.paddingMedium

    Rectangle {
        id: badge

        x: Theme.horizontalPageMargin
        width: Theme.iconSizeSmallPlus
        height: width
        radius: width / 2
        color: root.done ? Theme.highlightColor : "transparent"
        border.color: Theme.highlightColor
        border.width: root.done ? 0 : 2

        Label {
            anchors.centerIn: parent
            visible: !root.done
            color: Theme.highlightColor
            font.pixelSize: Theme.fontSizeSmall
            text: root.number
        }
        Icon {
            anchors.centerIn: parent
            visible: root.done
            width: parent.width * 0.8
            height: width
            color: Theme.highlightBackgroundColor
            source: "image://theme/icon-s-accept"
        }
    }

    Column {
        id: texts

        anchors {
            left: badge.right
            leftMargin: Theme.paddingLarge
            right: parent.right
            rightMargin: Theme.horizontalPageMargin
        }

        Label {
            width: parent.width
            wrapMode: Text.Wrap
            color: root.done ? Theme.secondaryHighlightColor : Theme.highlightColor
            text: root.title
        }
        Label {
            width: parent.width
            wrapMode: Text.Wrap
            color: Theme.secondaryColor
            font.pixelSize: Theme.fontSizeSmall
            text: root.detail
        }
    }
}

import QtQuick 2.6
import Sailfish.Silica 1.0

// A ride's screenshots as a row of thumbnails, at most [limit] and then "+N"; a tap opens them
// full size. Nothing at all for a ride without any.
Row {
    id: root

    // File paths, oldest first.
    property var pictures: []
    property real thumbHeight: Theme.itemSizeLarge
    property int limit: 0

    readonly property int shown: limit > 0 ? Math.min(limit, pictures.length) : pictures.length

    visible: pictures.length > 0
    spacing: Theme.paddingSmall
    height: visible ? thumbHeight : 0

    Repeater {
        model: root.shown

        BackgroundItem {
            width: root.thumbHeight * 16 / 9
            height: root.thumbHeight
            onClicked: pageStack.push(Qt.resolvedUrl("../pages/ScreenshotViewerPage.qml"),
                                      { pictures: root.pictures, index: index })

            Image {
                anchors.fill: parent
                fillMode: Image.PreserveAspectCrop
                asynchronous: true
                sourceSize.height: root.thumbHeight * 2
                source: "file://" + root.pictures[index]
            }
        }
    }
    Label {
        anchors.verticalCenter: parent.verticalCenter
        visible: root.pictures.length > root.shown
        color: Theme.secondaryHighlightColor
        text: "+" + (root.pictures.length - root.shown)
    }
}

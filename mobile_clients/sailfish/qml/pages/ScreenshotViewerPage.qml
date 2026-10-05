import QtQuick 2.6
import Sailfish.Silica 1.0
import Sailfish.Share 1.0
import "../components"
import "../components/Format.js" as Format

// A ride's screenshots full size, one at a time, as Android's viewer: swipe between them, see how
// far into the ride each was taken, and share the one on screen.
Page {
    id: page

    // File paths, oldest first, and the one to open on.
    property var pictures: []
    property int index: 0

    // "<taken at>-<ride seconds>[-n].png": how far into the ride it was taken.
    function rideSecondsOf(path) {
        var name = path.split("/").pop().replace(/\.(png|jpg)$/, "")
        var parts = name.split("-")
        return parts.length > 1 ? parseInt(parts[1]) : -1
    }

    allowedOrientations: Orientation.All
    backgroundColor: "black"

    ShareAction {
        id: shareAction

        title: qsTr("Share the screenshot")
    }

    SlideshowView {
        id: view

        anchors.fill: parent
        model: page.pictures
        currentIndex: page.index
        itemWidth: width
        itemHeight: height

        delegate: Image {
            width: view.width
            height: view.height
            fillMode: Image.PreserveAspectFit
            asynchronous: true
            source: "file://" + modelData
        }
    }

    Label {
        anchors {
            horizontalCenter: parent.horizontalCenter
            bottom: parent.bottom
            bottomMargin: Theme.paddingLarge
        }
        color: Theme.highlightColor
        font.pixelSize: Theme.fontSizeSmall
        text: {
            var at = page.rideSecondsOf(page.pictures[view.currentIndex] || "")
            return qsTr("%1 of %2").arg(view.currentIndex + 1).arg(page.pictures.length)
                   + (at >= 0 ? " · " + qsTr("%1 into the ride").arg(Format.clock(at)) : "")
        }
    }

    // Into Pictures, where Gallery shows it and any app can attach it.
    IconButton {
        anchors {
            left: parent.left
            bottom: parent.bottom
            margins: Theme.paddingMedium
        }
        icon.source: "image://theme/icon-m-download"
        onClicked: {
            var path = page.pictures[view.currentIndex]
            var saved = shareCard.saveToPictures(path, "IndoorBike " + path.split("/").pop())
            notice.show(saved ? qsTr("Saved to Pictures") : qsTr("The screenshot couldn't be saved to Pictures."))
        }
    }

    IconButton {
        anchors {
            right: parent.right
            bottom: parent.bottom
            margins: Theme.paddingMedium
        }
        icon.source: "image://theme/icon-m-share"
        onClicked: {
            var path = page.pictures[view.currentIndex]
            shareAction.mimeType = path.match(/\.jpg$/) ? "image/jpeg" : "image/png"
            shareAction.resources = [path]
            shareAction.trigger()
        }
    }

    InfoNotice { id: notice }
}

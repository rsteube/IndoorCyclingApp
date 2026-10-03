import QtQuick 2.6
import Sailfish.Silica 1.0

// A saved ride and one service: whether it went, and a tap to send it, or send it again.
BackgroundItem {
    id: root

    property string title
    // When it was uploaded, in epoch milliseconds; 0 when it has not been.
    property real uploadedAt
    property bool autoUpload
    property bool uploading

    signal upload()

    width: parent ? parent.width : 0
    height: Theme.itemSizeMedium
    enabled: !uploading
    onClicked: upload()

    Column {
        anchors {
            left: parent.left
            leftMargin: Theme.horizontalPageMargin
            right: action.left
            rightMargin: Theme.paddingLarge
            verticalCenter: parent.verticalCenter
        }

        Label {
            width: parent.width
            truncationMode: TruncationMode.Fade
            color: root.highlighted ? Theme.highlightColor : Theme.primaryColor
            text: root.title
        }
        Label {
            width: parent.width
            truncationMode: TruncationMode.Fade
            color: root.uploadedAt > 0 ? Theme.highlightColor : Theme.secondaryColor
            font.pixelSize: Theme.fontSizeExtraSmall
            text: root.uploadedAt > 0
                  ? qsTr("Uploaded %1").arg(new Date(root.uploadedAt).toLocaleString(Qt.locale(), Locale.ShortFormat))
                  : root.autoUpload ? qsTr("Automatic upload is on. Tap to send it again.")
                                    : qsTr("Tap to upload this ride.")
        }
    }

    BusyIndicator {
        anchors {
            right: parent.right
            rightMargin: Theme.horizontalPageMargin
            verticalCenter: parent.verticalCenter
        }
        size: BusyIndicatorSize.Small
        running: root.uploading
    }

    Label {
        id: action

        anchors {
            right: parent.right
            rightMargin: Theme.horizontalPageMargin
            verticalCenter: parent.verticalCenter
        }
        visible: !root.uploading
        color: Theme.highlightColor
        font.pixelSize: Theme.fontSizeSmall
        text: root.uploadedAt > 0 ? qsTr("Send again") : qsTr("Upload")
    }
}

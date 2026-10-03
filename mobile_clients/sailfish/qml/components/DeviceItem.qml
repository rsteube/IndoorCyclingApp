import QtQuick 2.6
import Sailfish.Silica 1.0

// A trainer, strap or controller on the first page: what it is, and how it is doing.
BackgroundItem {
    id: root

    property string title
    property var device: ({})
    property string emptyText
    property string detail

    readonly property string status: {
        if (device.connected)
            return (device.name || title) + (detail ? " · " + detail : "")
        switch (device.state) {
        case "Connecting":
            return qsTr("Connecting to %1").arg(device.name || title)
        case "Scanning":
            return device.pairedId ? qsTr("Looking for it") : qsTr("Searching")
        case "Error":
            return qsTr("Couldn't connect. It retries by itself.")
        default:
            return device.pairedId ? qsTr("Paired, not in range") : emptyText
        }
    }

    width: parent ? parent.width : 0
    height: Math.max(Theme.itemSizeMedium, texts.height + 2 * Theme.paddingSmall)

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
            color: root.device.connected ? Theme.highlightColor : Theme.secondaryColor
            font.pixelSize: Theme.fontSizeExtraSmall
            text: root.status
        }
    }
}

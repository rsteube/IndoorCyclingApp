import QtQuick 2.6
import Sailfish.Silica 1.0

// A trainer, strap or controller on the first page: what it is, how it is doing, and its live
// reading, big, on the right: watts from the trainer, a beating heart from the strap.
BackgroundItem {
    id: root

    property string title
    property string icon
    property color tint: Theme.highlightColor
    property var device: ({})
    property string emptyText
    // The live reading on the right, and its unit.
    property real reading
    property string unit
    property bool heart

    readonly property string statusText: {
        if (device.connected)
            return device.name || title
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
        muted: !root.device.connected
    }

    Column {
        id: texts

        anchors {
            left: tile.right
            leftMargin: Theme.paddingLarge
            right: readingRow.left
            rightMargin: Theme.paddingMedium
            verticalCenter: parent.verticalCenter
        }
        spacing: Theme.paddingSmall / 2

        Row {
            spacing: Theme.paddingMedium

            Label {
                id: titleLabel

                color: root.highlighted ? Theme.highlightColor : Theme.primaryColor
                text: root.title
            }
            StatusDot {
                anchors.verticalCenter: titleLabel.verticalCenter
                level: root.device.connected ? "ok"
                       : root.device.state === "Connecting" || root.device.state === "Scanning" ? "busy"
                       : root.device.state === "Error" ? "bad"
                       : root.device.pairedId ? "off" : ""
                visible: level.length > 0
            }
        }
        Label {
            width: parent.width
            wrapMode: Text.Wrap
            color: root.highlighted ? Theme.secondaryHighlightColor : Theme.secondaryColor
            font.pixelSize: Theme.fontSizeExtraSmall
            text: root.statusText
        }
    }

    Row {
        id: readingRow

        anchors {
            right: parent.right
            rightMargin: Theme.horizontalPageMargin
            verticalCenter: parent.verticalCenter
        }
        spacing: Theme.paddingSmall
        visible: !!root.device.connected && root.unit.length > 0
        opacity: visible ? 1 : 0

        Behavior on opacity { FadeAnimator { } }

        HeartBeat {
            anchors.verticalCenter: parent.verticalCenter
            visible: root.heart
            bpm: root.reading
            color: root.tint
        }
        AnimatedNumber {
            id: number

            anchors.verticalCenter: parent.verticalCenter
            value: root.reading
            available: root.reading > 0
            color: root.highlighted ? Theme.highlightColor : Theme.primaryColor
            font.pixelSize: Theme.fontSizeLarge
            font.family: Theme.fontFamilyHeading
        }
        Label {
            anchors.baseline: number.baseline
            color: Theme.secondaryHighlightColor
            font.pixelSize: Theme.fontSizeExtraSmall
            text: root.unit
        }
    }
}

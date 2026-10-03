import QtQuick 2.6
import Sailfish.Silica 1.0

// Find a trainer, a heart rate monitor or a controller and connect to it. `kind` is the core's
// topic and command prefix for it: "trainer", "hrm" or "controller". Scans while the page is open.
Page {
    id: page

    property string kind: "trainer"
    property string title: qsTr("Pair a trainer")
    property string hint: qsTr("Wake the trainer by turning the pedals. Close other apps that are connected to it.")

    readonly property var device: core.state[kind] || ({})
    readonly property var candidates: device.candidates || []

    allowedOrientations: Orientation.All

    onStatusChanged: {
        if (status === PageStatus.Active) {
            core.send({ "cmd": kind + ".scan" })
        } else if (status === PageStatus.Deactivating) {
            core.send({ "cmd": kind + ".stopScan" })
        }
    }

    SilicaListView {
        id: list

        anchors.fill: parent
        model: page.candidates

        header: Column {
            width: list.width
            spacing: Theme.paddingMedium

            PageHeader { title: page.title }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                wrapMode: Text.Wrap
                color: Theme.highlightColor
                font.pixelSize: Theme.fontSizeSmall
                text: device.connected
                      ? qsTr("Connected to %1").arg(device.name)
                      : (device.state === "Connecting" ? qsTr("Connecting...") : page.hint)
            }

            BusyLabel {
                running: page.candidates.length === 0 && !device.connected
                text: qsTr("Searching")
                height: running ? Theme.itemSizeLarge : 0
            }
        }

        delegate: ListItem {
            id: item

            contentHeight: Theme.itemSizeMedium
            onClicked: core.send({ "cmd": page.kind + ".connect", "id": modelData.id })

            Label {
                id: name

                anchors {
                    left: parent.left
                    leftMargin: Theme.horizontalPageMargin
                    right: signal.left
                    rightMargin: Theme.paddingMedium
                    verticalCenter: parent.verticalCenter
                }
                truncationMode: TruncationMode.Fade
                color: item.highlighted ? Theme.highlightColor : Theme.primaryColor
                text: modelData.name
            }
            Label {
                id: signal

                anchors {
                    right: parent.right
                    rightMargin: Theme.horizontalPageMargin
                    verticalCenter: parent.verticalCenter
                }
                color: Theme.secondaryColor
                font.pixelSize: Theme.fontSizeExtraSmall
                text: modelData.rssi ? qsTr("%1 dBm").arg(modelData.rssi) : ""
            }
        }

        VerticalScrollDecorator { }
    }
}

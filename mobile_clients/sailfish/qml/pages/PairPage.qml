import QtQuick 2.6
import Sailfish.Silica 1.0
import "../components"
import "../components/Accent.js" as Accent

// Find a trainer, a heart rate monitor or a controller and connect to it. `kind` is the core's
// topic and command prefix for it: "trainer", "hrm" or "controller". Scans while the page is open,
// with a radar going until something answers, and every device found with how strongly it is heard.
Page {
    id: page

    property string kind: "trainer"
    property string title: qsTr("Pair a trainer")
    property string hint: qsTr("Wake the trainer by turning the pedals. Close other apps that are connected to it.")

    readonly property var device: core.state[kind] || ({})
    readonly property var candidates: device.candidates || []
    readonly property string icon: kind === "hrm" ? "favorite" : kind === "controller" ? "gamepad" : "pedal_bike"
    readonly property color tint: kind === "hrm" ? Accent.heart : kind === "controller" ? Accent.violet : Accent.power

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

            Radar {
                anchors.horizontalCenter: parent.horizontalCenter
                width: Theme.itemSizeHuge * 1.3
                icon: page.icon
                tint: page.device.connected ? Accent.success : page.tint
                searching: !page.device.connected
                found: !!page.device.connected
            }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.Wrap
                color: Theme.highlightColor
                font.pixelSize: page.device.connected ? Theme.fontSizeLarge : Theme.fontSizeSmall
                text: page.device.connected
                      ? qsTr("Connected to %1").arg(page.device.name)
                      : (page.device.state === "Connecting" ? qsTr("Connecting...") : page.hint)
            }

            SectionHeader {
                visible: page.candidates.length > 0
                text: qsTr("Found nearby")
            }
            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: page.candidates.length === 0 && !page.device.connected
                horizontalAlignment: Text.AlignHCenter
                color: Theme.secondaryHighlightColor
                font.pixelSize: Theme.fontSizeExtraSmall
                text: qsTr("Searching")
            }
        }

        delegate: ListItem {
            id: item

            contentHeight: Theme.itemSizeMedium
            onClicked: core.send({ "cmd": page.kind + ".connect", "id": modelData.id })

            IconTile {
                id: tile

                anchors {
                    left: parent.left
                    leftMargin: Theme.horizontalPageMargin
                    verticalCenter: parent.verticalCenter
                }
                name: page.icon
                tint: page.tint
                highlighted: item.highlighted
            }
            Label {
                anchors {
                    left: tile.right
                    leftMargin: Theme.paddingLarge
                    right: bars.left
                    rightMargin: Theme.paddingMedium
                    verticalCenter: parent.verticalCenter
                }
                truncationMode: TruncationMode.Fade
                color: item.highlighted ? Theme.highlightColor : Theme.primaryColor
                text: modelData.name
            }
            SignalBars {
                id: bars

                anchors {
                    right: parent.right
                    rightMargin: Theme.horizontalPageMargin
                    verticalCenter: parent.verticalCenter
                }
                rssi: modelData.rssi || 0
                color: item.highlighted ? Theme.highlightColor : page.tint
            }
        }

        VerticalScrollDecorator { }
    }
}

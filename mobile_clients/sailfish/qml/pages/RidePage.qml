import QtQuick 2.6
import Sailfish.Silica 1.0
import "../components"

Page {
    id: page

    readonly property var trainer: core.state.trainer || ({})
    readonly property var hrm: core.state.hrm || ({})
    readonly property bool connected: !!trainer.connected
    readonly property int heartRate: hrm.connected && hrm.heartRate > 0 ? hrm.heartRate : (trainer.heartRate || 0)

    allowedOrientations: Orientation.All

    SilicaFlickable {
        anchors.fill: parent
        contentHeight: column.height + Theme.paddingLarge

        PullDownMenu {
            MenuItem {
                text: qsTr("Pair a heart rate monitor")
                onClicked: pageStack.push(Qt.resolvedUrl("PairPage.qml"), {
                    kind: "hrm",
                    title: qsTr("Pair a heart rate monitor"),
                    hint: qsTr("Put the strap on, or wet its contacts, so it starts sending.")
                })
            }
            MenuItem {
                text: qsTr("Pair a trainer")
                onClicked: pageStack.push(Qt.resolvedUrl("PairPage.qml"))
            }
        }

        Column {
            id: column

            width: parent.width
            spacing: Theme.paddingLarge

            PageHeader {
                title: qsTr("IndoorBike")
                description: appVersion
            }

            SectionHeader { text: qsTr("Trainer") }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                wrapMode: Text.Wrap
                color: Theme.highlightColor
                text: page.connected
                      ? qsTr("Connected to %1").arg(trainer.name || qsTr("the trainer"))
                      : (trainer.state === "Scanning" ? qsTr("Looking for the trainer")
                                                      : qsTr("No trainer connected"))
            }

            Row {
                width: parent.width
                visible: page.connected

                Reading {
                    width: parent.width / 3
                    large: true
                    label: qsTr("Power")
                    value: trainer.power !== undefined ? trainer.power : "--"
                    unit: "W"
                }
                Reading {
                    width: parent.width / 3
                    large: true
                    label: qsTr("Cadence")
                    value: trainer.cadence !== undefined ? trainer.cadence : "--"
                    unit: "rpm"
                }
                Reading {
                    width: parent.width / 3
                    large: true
                    label: qsTr("Heart rate")
                    value: page.heartRate > 0 ? page.heartRate : "--"
                    unit: "bpm"
                }
            }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: !!trainer.ergError
                wrapMode: Text.Wrap
                color: Theme.errorColor
                font.pixelSize: Theme.fontSizeSmall
                text: trainer.ergError || ""
            }

            ButtonLayout {
                Button {
                    visible: !page.connected
                    text: qsTr("Pair a trainer")
                    onClicked: pageStack.push(Qt.resolvedUrl("PairPage.qml"))
                }
                Button {
                    visible: devBuild && !page.connected
                    text: qsTr("Use the demo trainer")
                    onClicked: core.send({ "cmd": "trainer.demo" })
                }
                Button {
                    visible: page.connected && !!trainer.isMock
                    text: trainer.mockPedaling ? qsTr("Stop pedalling") : qsTr("Start pedalling")
                    onClicked: core.send({ "cmd": "trainer.demoPedal" })
                }
                Button {
                    visible: page.connected
                    text: qsTr("Disconnect")
                    onClicked: core.send({ "cmd": "trainer.disconnect" })
                }
            }

            SectionHeader { text: qsTr("Heart rate monitor") }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                wrapMode: Text.Wrap
                color: Theme.highlightColor
                text: hrm.connected ? qsTr("%1: %2 bpm").arg(hrm.name || qsTr("Strap")).arg(hrm.heartRate || "--")
                                    : qsTr("None connected")
            }
        }

        VerticalScrollDecorator { }
    }
}

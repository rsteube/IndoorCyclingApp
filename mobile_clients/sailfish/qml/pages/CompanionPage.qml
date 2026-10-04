import QtQuick 2.6
import Sailfish.Silica 1.0
import Nemo.KeepAlive 1.2
import "../components"
import "../components/Format.js" as Format

// Companion mode's way in: open the socket, show the address and the code, and hand over to the
// ride as soon as the game starts describing a road. The socket is open only while this page, or
// the companion ride it leads to, is on screen.
Page {
    id: page

    objectName: "companionPage"

    readonly property var companion: core.state.companion || ({})
    readonly property var trainer: core.state.trainer || ({})
    readonly property var ride: core.state.ride || ({})
    readonly property bool trainerReady: !!trainer.connected
    property bool wasReceiving: false

    function openRide() {
        if (pageStack.currentPage === page)
            pageStack.push(Qt.resolvedUrl("RideScreenPage.qml"))
    }

    allowedOrientations: Orientation.All
    // Every time the page comes up, as on iOS: back from a session that ended, the socket is closed.
    onStatusChanged: if (status === PageStatus.Active && !companion.running) core.send({ "cmd": "companion.start" })
    // Leaving for anywhere but the ride closes the socket. A companion ride still going keeps it,
    // until the ride's own page ends the session.
    Component.onDestruction: {
        if (!(ride.active && ride.kind === "companion"))
            core.send({ "cmd": "companion.stop" })
    }

    // The game started describing the road: the ride takes over.
    onCompanionChanged: {
        var receiving = !!companion.receivingRoad
        if (receiving && !wasReceiving)
            openRide()
        wasReceiving = receiving
    }

    // A game on another computer is set up while the phone sits there; it must not lock.
    DisplayBlanking { preventBlanking: page.status === PageStatus.Active }

    // Joining or leaving a Wi-Fi changes the address to type in.
    Timer {
        interval: 10000
        repeat: true
        running: page.status === PageStatus.Active && !!page.companion.running
        onTriggered: core.send({ "cmd": "companion.refreshAddresses" })
    }

    SilicaFlickable {
        anchors.fill: parent
        contentHeight: column.height + Theme.paddingLarge

        PullDownMenu {
            visible: !!page.companion.debug
            MenuItem {
                text: qsTr("Debug: ride without a companion")
                onClicked: {
                    core.send({ "cmd": "companion.withoutCompanion" })
                    page.openRide()
                }
            }
        }

        Column {
            id: column

            width: parent.width
            spacing: Theme.paddingMedium

            PageHeader { title: qsTr("Companion") }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: !!page.companion.error
                wrapMode: Text.Wrap
                color: Theme.errorColor
                text: qsTr("Could not start: %1").arg(page.companion.error || "")
            }
            BusyLabel {
                running: !page.companion.running && !page.companion.error
                text: qsTr("Opening the connection")
            }

            SectionHeader { text: qsTr("How to start") }

            Step {
                number: 1
                title: qsTr("Get the trainer ready")
                done: page.trainerReady
                detail: page.trainerReady ? qsTr("Ready.")
                                          : qsTr("The companion can connect without one, but nothing would move the trainer.")
            }
            Button {
                anchors.horizontalCenter: parent.horizontalCenter
                visible: !page.trainerReady
                text: qsTr("Pair a trainer")
                onClicked: pageStack.push(Qt.resolvedUrl("PairPage.qml"))
            }
            Step {
                number: 2
                title: qsTr("Connect the companion")
                done: !!page.companion.controllerName
                detail: page.companion.controllerName
                        ? qsTr("%1 is connected.").arg(page.companion.controllerName)
                        : qsTr("Open it on a computer or console on the same Wi-Fi. It finds this phone by itself; if it doesn't, type in the details below, then the pairing code.")
            }
            Step {
                number: 3
                title: qsTr("Start the ride in the companion")
                done: false
                detail: qsTr("This page turns into the ride as soon as it starts describing the road.")
            }

            SectionHeader {
                visible: !!page.companion.running
                text: qsTr("Connection details")
            }
            DetailItem {
                visible: !!page.companion.running
                label: qsTr("Pairing code")
                value: page.companion.token || ""
            }
            DetailItem {
                visible: !!page.companion.running
                label: qsTr("Address")
                value: page.companion.addresses && page.companion.addresses.length > 0
                       ? page.companion.addresses.join(", ") : qsTr("No network")
            }
            DetailItem {
                visible: !!page.companion.running
                label: qsTr("Port")
                value: page.companion.port || ""
            }
            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: !!page.companion.running
                wrapMode: Text.Wrap
                color: Theme.secondaryHighlightColor
                font.pixelSize: Theme.fontSizeExtraSmall
                text: !page.companion.addresses || page.companion.addresses.length === 0
                      ? qsTr("Join a Wi-Fi network: a companion connects over it.")
                      : page.companion.debug ? qsTr("Debug build: the code is always the same.")
                                             : qsTr("The code is new for every ride.")
            }

            SectionHeader { text: qsTr("What this is") }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                wrapMode: Text.Wrap
                color: Theme.highlightColor
                font.pixelSize: Theme.fontSizeSmall
                text: qsTr("Companion mode hands the trainer to a game or app on another device, which describes the road. It needs one running.")
            }
            BackgroundItem {
                width: parent.width
                onClicked: Qt.openUrlExternally("https://indoorbike.app")
                Label {
                    x: Theme.horizontalPageMargin
                    anchors.verticalCenter: parent.verticalCenter
                    color: parent.highlighted ? Theme.highlightColor : Theme.primaryColor
                    text: qsTr("Find or build a companion")
                }
            }

            SectionHeader {
                visible: !!page.companion.paired && page.companion.paired.length > 0
                text: qsTr("Paired companions")
            }
            Repeater {
                model: page.companion.paired || []

                ListItem {
                    id: pairedItem

                    width: column.width
                    menu: ContextMenu {
                        MenuItem {
                            text: qsTr("Forget")
                            onClicked: pairedItem.remorseDelete(function() {
                                core.send({ "cmd": "companion.forget", "deviceId": modelData.deviceId })
                            })
                        }
                    }

                    Column {
                        x: Theme.horizontalPageMargin
                        width: parent.width - 2 * Theme.horizontalPageMargin
                        anchors.verticalCenter: parent.verticalCenter

                        Label {
                            width: parent.width
                            truncationMode: TruncationMode.Fade
                            color: pairedItem.highlighted ? Theme.highlightColor : Theme.primaryColor
                            text: modelData.name
                        }
                        Label {
                            width: parent.width
                            color: Theme.secondaryColor
                            font.pixelSize: Theme.fontSizeExtraSmall
                            text: qsTr("Last connected %1").arg(Format.relative(modelData.lastSeen))
                        }
                    }
                }
            }

            TextSwitch {
                text: qsTr("Show every reading while riding")
                description: qsTr("Speed, distance, climbing and heart rate as well as power, gradient, gear and cadence.")
                checked: !!page.companion.showAllData
                automaticCheck: false
                onClicked: core.send({ "cmd": "companion.showAllData", "value": !checked })
            }
        }

        VerticalScrollDecorator { }
    }
}

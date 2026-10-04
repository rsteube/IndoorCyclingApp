import QtQuick 2.6
import Sailfish.Silica 1.0
import Nemo.KeepAlive 1.2
import "../components"
import "../components/Format.js" as Format

// Companion mode's way in, as the Android app's Companion screen: open the socket, say how things
// stand, show where to connect and the code, and give way to the ride as soon as the game starts
// describing a road. The socket is open only while this page, or the ride it leads to, is on screen.
Page {
    id: page

    objectName: "companionPage"

    readonly property var companion: core.state.companion || ({})
    readonly property var trainer: core.state.trainer || ({})
    readonly property var ride: core.state.ride || ({})
    readonly property bool trainerReady: !!trainer.connected
    readonly property var addresses: companion.addresses || []
    property bool wasReceiving: false
    property bool handedOff: false

    // The ride takes this page's place, as on Android: leaving the ride goes home, not back here.
    function openRide() {
        if (handedOff)
            return
        handedOff = true
        pageStack.replace(Qt.resolvedUrl("RideScreenPage.qml"))
    }

    allowedOrientations: Orientation.All
    // Every time the page comes up, as on iOS: back from a session that ended, the socket is closed.
    onStatusChanged: if (status === PageStatus.Active && !companion.running) core.send({ "cmd": "companion.start" })
    // Leaving for anywhere but the ride closes the socket.
    Component.onDestruction: {
        if (!handedOff)
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

    // Joining or leaving a Wi-Fi changes the addresses to type in.
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
                text: qsTr("Debug: open the ride without a companion")
                onClicked: {
                    core.send({ "cmd": "companion.withoutCompanion" })
                    page.openRide()
                }
            }
        }

        Column {
            id: column

            width: parent.width
            spacing: Theme.paddingLarge

            PageHeader {
                title: qsTr("Companion")
                description: qsTr("Ride mode · Alpha")
            }

            // How things stand.
            Column {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                spacing: Theme.paddingSmall

                Label {
                    width: parent.width
                    wrapMode: Text.Wrap
                    color: page.companion.error ? "#F0C419" : Theme.highlightColor
                    font.pixelSize: Theme.fontSizeLarge
                    text: page.companion.error ? qsTr("Could not start")
                          : !page.companion.running ? qsTr("Opening the socket")
                          : page.companion.controllerName ? qsTr("%1 connected").arg(page.companion.controllerName)
                          : qsTr("Waiting for a companion")
                }
                Label {
                    width: parent.width
                    wrapMode: Text.Wrap
                    color: Theme.secondaryHighlightColor
                    font.pixelSize: Theme.fontSizeSmall
                    text: page.companion.error ? page.companion.error
                          : !page.companion.running ? qsTr("One moment.")
                          : page.companion.controllerName
                            ? qsTr("Waiting for it to start describing the road. The ride begins on its first update.")
                            : qsTr("Start your companion app and point it at this phone.")
                }
            }

            // Without a trainer a companion can connect, but nothing would move the bike.
            Column {
                width: parent.width
                visible: !page.trainerReady
                spacing: Theme.paddingMedium

                Label {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    wrapMode: Text.Wrap
                    color: "#F0C419"
                    font.pixelSize: Theme.fontSizeSmall
                    text: qsTr("No trainer paired. A companion can connect without one, but nothing would move the trainer.")
                }
                Button {
                    anchors.horizontalCenter: parent.horizontalCenter
                    text: qsTr("Pair a trainer")
                    onClicked: pageStack.push(Qt.resolvedUrl("PairPage.qml"))
                }
            }

            // Where to connect: every address, as the URL a companion types in.
            Column {
                width: parent.width
                visible: !!page.companion.running
                spacing: Theme.paddingSmall

                SectionHeader { text: page.addresses.length > 1 ? qsTr("Addresses") : qsTr("Address") }

                Repeater {
                    model: page.addresses

                    Label {
                        x: Theme.horizontalPageMargin
                        width: column.width - 2 * Theme.horizontalPageMargin
                        wrapMode: Text.WrapAnywhere
                        color: Theme.highlightColor
                        font.pixelSize: Theme.fontSizeMedium
                        text: "ws://" + modelData + ":" + page.companion.port + "/companion"
                    }
                }
                Label {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    visible: page.addresses.length === 0
                    wrapMode: Text.Wrap
                    color: "#F0C419"
                    font.pixelSize: Theme.fontSizeSmall
                    text: qsTr("No network. Join the same Wi-Fi as the companion: it connects over it.")
                }
                DetailItem {
                    label: qsTr("Port")
                    value: page.companion.port || ""
                }
                Label {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    wrapMode: Text.Wrap
                    color: Theme.secondaryHighlightColor
                    font.pixelSize: Theme.fontSizeExtraSmall
                    text: qsTr("New for every ride. A companion that remembered the last one is pointing at a socket that has closed.")
                }
                // Sailfish has no DNS-SD to advertise with; the UDP beacon is how a companion finds it.
                Label {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    wrapMode: Text.Wrap
                    color: Theme.secondaryHighlightColor
                    font.pixelSize: Theme.fontSizeExtraSmall
                    text: qsTr("A companion that listens for this phone's beacon finds it on the same Wi-Fi. Otherwise type the address into the companion by hand.")
                }
            }

            // The pairing code.
            Column {
                width: parent.width
                visible: !!page.companion.running
                spacing: Theme.paddingSmall

                SectionHeader { text: qsTr("Pairing code") }

                Label {
                    anchors.horizontalCenter: parent.horizontalCenter
                    color: Theme.highlightColor
                    font.pixelSize: Theme.fontSizeHuge
                    font.letterSpacing: Theme.paddingSmall
                    text: page.companion.token || ""
                }
                Label {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.Wrap
                    color: Theme.secondaryHighlightColor
                    font.pixelSize: Theme.fontSizeExtraSmall
                    text: page.companion.debug ? qsTr("Debug build: the code is always the same.")
                                               : qsTr("New for every ride. It is never stored and never reused.")
                }
            }

            SectionHeader { text: qsTr("Needs a second app") }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                wrapMode: Text.Wrap
                color: Theme.highlightColor
                font.pixelSize: Theme.fontSizeSmall
                text: qsTr("Companion mode hands the trainer to a game or app on another device, which describes the road. It needs one running.")
            }
            Button {
                anchors.horizontalCenter: parent.horizontalCenter
                text: qsTr("Find or build a companion")
                onClicked: Qt.openUrlExternally("https://indoorbike.app/companion/")
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
                    contentHeight: Theme.itemSizeMedium
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
                            text: !modelData.lastSeen ? qsTr("Paired")
                                  : Date.now() - modelData.lastSeen < 60000 ? qsTr("Connected just now")
                                  : qsTr("Last connected %1").arg(Format.relative(modelData.lastSeen))
                        }
                    }
                }
            }

            TextSwitch {
                text: qsTr("Show every reading while riding")
                description: qsTr("Gradient, gear, cadence, heart rate, speed, distance and climb as well as power. For building a companion: it changes nothing the game is sent.")
                checked: !!page.companion.showAllData
                automaticCheck: false
                onClicked: core.send({ "cmd": "companion.showAllData", "value": !checked })
            }
        }

        VerticalScrollDecorator { }
    }
}

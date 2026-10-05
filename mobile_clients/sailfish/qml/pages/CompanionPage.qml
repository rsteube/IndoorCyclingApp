import QtQuick 2.6
import Sailfish.Silica 1.0
import Nemo.KeepAlive 1.2
import "../components"
import "../components/Accent.js" as Accent
import "../components/Format.js" as Format

// Companion mode's way in, as the Android app's Companion screen: open the socket, say how things
// stand, show the code, and give way to the ride as soon as the game starts describing a road. Laid
// out as a glance: where things stand, the code, a checklist; the detail folds away below. The
// socket is open only while this page, or the ride it leads to, is on screen.
Page {
    id: page

    objectName: "companionPage"

    readonly property var companion: core.state.companion || ({})
    readonly property var trainer: core.state.trainer || ({})
    readonly property bool trainerReady: !!trainer.connected
    readonly property var addresses: companion.addresses || []
    readonly property var paired: companion.paired || []
    readonly property bool running: !!companion.running
    readonly property bool connected: !!companion.controllerName
    property bool wasReceiving: false
    property bool handedOff: false

    // The ride takes this page's place, as on Android: leaving the ride goes home, not back here.
    function openRide() {
        if (handedOff)
            return
        handedOff = true
        pageStack.replace(Qt.resolvedUrl("RideScreenPage.qml"))
    }

    function copy(text, what) {
        Clipboard.text = text
        notice.show(qsTr("%1 copied").arg(what))
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
        running: page.status === PageStatus.Active && page.running
        onTriggered: core.send({ "cmd": "companion.refreshAddresses" })
    }

    SilicaFlickable {
        anchors.fill: parent
        contentHeight: column.height + Theme.paddingLarge

        PullDownMenu {
            MenuItem {
                text: qsTr("Find or build a companion")
                onClicked: Qt.openUrlExternally("https://indoorbike.app/companion/")
            }
            MenuItem {
                visible: !!page.companion.debug
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
                description: qsTr("A game on another screen rides the trainer")
            }

            // Where things stand, as one picture: announcing itself, someone connected, or stuck.
            Column {
                width: parent.width
                spacing: Theme.paddingMedium

                Radar {
                    anchors.horizontalCenter: parent.horizontalCenter
                    icon: page.companion.error ? "error" : "sports_esports"
                    tint: page.companion.error ? Theme.errorColor : page.connected ? Accent.success : Theme.highlightColor
                    searching: page.running && !page.connected && !page.companion.error
                    found: page.connected
                }
                Label {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.Wrap
                    color: page.companion.error ? Theme.errorColor : Theme.highlightColor
                    font.pixelSize: Theme.fontSizeLarge
                    text: page.companion.error ? qsTr("Could not start")
                          : !page.running ? qsTr("Getting ready")
                          : page.connected ? qsTr("%1 connected").arg(page.companion.controllerName)
                          : qsTr("Waiting for your game")
                }
                Label {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.Wrap
                    color: Theme.secondaryHighlightColor
                    font.pixelSize: Theme.fontSizeSmall
                    text: page.companion.error ? page.companion.error
                          : !page.running ? qsTr("One moment.")
                          : page.connected ? qsTr("The ride begins as soon as the game starts it.")
                          : qsTr("Open the game and choose this phone. Enter this code when it asks.")
                }
            }

            // The code, as big tiles: read across a room and typed into a game.
            Column {
                width: parent.width
                spacing: Theme.paddingMedium
                visible: page.running && !page.connected && !!page.companion.token
                opacity: visible ? 1 : 0

                Behavior on opacity { FadeAnimator { } }

                CodeTiles {
                    anchors.horizontalCenter: parent.horizontalCenter
                    code: page.companion.token || ""
                }
                Label {
                    anchors.horizontalCenter: parent.horizontalCenter
                    color: Theme.secondaryHighlightColor
                    font.pixelSize: Theme.fontSizeExtraSmall
                    text: page.companion.debug ? qsTr("Pairing code · the same in every debug build")
                                               : qsTr("Pairing code · new for every ride")
                }
            }

            // What has to be true for a game to get in.
            Column {
                width: parent.width

                SectionHeader { text: qsTr("Ready to ride") }

                SettingRow {
                    icon: "pedal_bike"
                    tint: Accent.power
                    title: page.trainerReady ? (page.trainer.name || qsTr("Trainer")) : qsTr("No trainer")
                    subtitle: page.trainerReady ? qsTr("Connected. The game will set its resistance.")
                                                : qsTr("A game can connect without one, but nothing would move the trainer. Tap to pair.")
                    status: page.trainerReady ? "ok" : "bad"
                    navigates: !page.trainerReady
                    onClicked: if (!page.trainerReady) pageStack.push(Qt.resolvedUrl("PairPage.qml"))
                }
                SettingRow {
                    icon: "wifi"
                    tint: Accent.sky
                    title: page.addresses.length > 0 ? qsTr("On the network") : qsTr("No network")
                    subtitle: page.addresses.length > 0 ? qsTr("The game has to be on the same Wi-Fi to find this phone.")
                                                        : qsTr("Join the same Wi-Fi as the game.")
                    status: !page.running ? "busy" : page.addresses.length > 0 ? "ok" : "bad"
                }
                SettingRow {
                    icon: "sports_esports"
                    tint: Accent.violet
                    title: page.connected ? page.companion.controllerName : qsTr("Game")
                    subtitle: page.connected ? qsTr("Connected from %1").arg(page.companion.controllerAddress || "")
                                             : qsTr("Waiting for it to connect")
                    status: page.connected ? "ok" : page.running ? "busy" : "off"
                }
            }

            // For a game that does not find the phone by itself.
            Expander {
                visible: page.running
                title: qsTr("Connect by hand")

                Note {
                    text: qsTr("Type this address into the game if it does not find the phone by itself. Tap it to copy. It changes for every ride.")
                }
                Repeater {
                    model: page.addresses

                    BackgroundItem {
                        id: addressItem

                        readonly property string url: "ws://" + modelData + ":" + page.companion.port + "/companion"

                        width: column.width
                        height: Theme.itemSizeSmall
                        onClicked: page.copy(url, qsTr("Address"))

                        Label {
                            anchors {
                                left: parent.left
                                leftMargin: Theme.horizontalPageMargin
                                right: copyIcon.left
                                rightMargin: Theme.paddingMedium
                                verticalCenter: parent.verticalCenter
                            }
                            truncationMode: TruncationMode.Fade
                            font.family: "monospace"
                            font.pixelSize: Theme.fontSizeSmall
                            color: addressItem.highlighted ? Theme.highlightColor : Theme.primaryColor
                            text: addressItem.url
                        }
                        Glyph {
                            id: copyIcon

                            anchors {
                                right: parent.right
                                rightMargin: Theme.horizontalPageMargin
                                verticalCenter: parent.verticalCenter
                            }
                            width: Theme.iconSizeSmall
                            height: width
                            name: "content_copy"
                            color: addressItem.highlighted ? Theme.highlightColor : Theme.secondaryColor
                        }
                    }
                }
                DetailItem {
                    label: qsTr("Address")
                    value: page.addresses.length > 0 ? page.addresses[0] : "-"
                }
                DetailItem {
                    label: qsTr("Port")
                    value: page.companion.port || "-"
                }
                DetailItem {
                    label: qsTr("Code")
                    value: page.companion.token || "-"
                }
            }

            // Games the rider let the phone remember: they connect without the code.
            Column {
                width: parent.width
                visible: page.paired.length > 0

                SectionHeader { text: qsTr("Remembered games") }

                Repeater {
                    model: page.paired

                    SettingRow {
                        id: pairedRow

                        icon: "sports_esports"
                        tint: Accent.violet
                        title: modelData.name
                        subtitle: !modelData.lastSeen ? qsTr("Remembered · connects without the code")
                                  : Date.now() - modelData.lastSeen < 60000 ? qsTr("Connected just now")
                                  : qsTr("Last connected %1").arg(Format.relative(modelData.lastSeen))

                        function forget() {
                            var id = modelData.deviceId
                            remorseDelete(function() { core.send({ "cmd": "companion.forget", "deviceId": id }) })
                        }

                        onClicked: openMenu()
                        menu: ContextMenu {
                            MenuItem {
                                text: qsTr("Forget")
                                onClicked: pairedRow.forget()
                            }
                        }

                        IconButton {
                            icon.source: "image://theme/icon-m-delete"
                            onClicked: pairedRow.forget()
                        }
                    }
                }
                Note {
                    text: qsTr("Forget a game to make it ask for the code again, for example when it says it has no key for this phone.")
                }
            }

            Expander {
                title: qsTr("What is companion mode?")

                Note {
                    text: qsTr("Companion mode hands the trainer to a game or app on another device, such as a computer by the bike. The game describes the road and the trainer rides it; the phone shows your gear, takes your shifts and saves each ride to your history.")
                }
                Button {
                    anchors.horizontalCenter: parent.horizontalCenter
                    text: qsTr("Find or build a companion")
                    onClicked: Qt.openUrlExternally("https://indoorbike.app/companion/")
                }
            }

            Expander {
                title: qsTr("For game developers")

                TextSwitch {
                    text: qsTr("Show every reading while riding")
                    description: qsTr("Gradient, gear, cadence, heart rate, speed, distance and climb as well as power. Changes nothing the game is sent.")
                    checked: !!page.companion.showAllData
                    automaticCheck: false
                    onClicked: core.send({ "cmd": "companion.showAllData", "value": !checked })
                }
            }
        }

        VerticalScrollDecorator { }
    }

    InfoNotice { id: notice }
}

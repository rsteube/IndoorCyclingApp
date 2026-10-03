import QtQuick 2.6
import Sailfish.Silica 1.0
import "../components"
import "../components/Format.js" as Format

// "What are you riding?": the first page. A workout, or just riding; the trainer and the heart rate
// monitor, and how they are doing; and a ride the app was stopped in the middle of.
Page {
    id: page

    objectName: "ridePage"

    readonly property var trainer: core.state.trainer || ({})
    readonly property var hrm: core.state.hrm || ({})
    readonly property var ride: core.state.ride || ({})
    readonly property var unfinished: core.state.unfinishedRide
    readonly property var bluetooth: core.state.bluetooth || ({})

    function openRide() {
        pageStack.push(Qt.resolvedUrl("RideScreenPage.qml"))
    }

    allowedOrientations: Orientation.All

    // History is one swipe forward: Sailfish's attached page, where Android has a tab.
    onStatusChanged: {
        if (status === PageStatus.Active && pageStack.nextPage(page) === null)
            pageStack.pushAttached(Qt.resolvedUrl("HistoryPage.qml"))
    }

    SilicaFlickable {
        anchors.fill: parent
        contentHeight: column.height + Theme.paddingLarge

        PullDownMenu {
            MenuItem {
                text: qsTr("Progress")
                onClicked: pageStack.push(Qt.resolvedUrl("ProgressPage.qml"))
            }
            MenuItem {
                text: qsTr("You: FTP and weight")
                onClicked: pageStack.push(Qt.resolvedUrl("RiderDialog.qml"))
            }
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
            spacing: Theme.paddingMedium

            PageHeader {
                title: qsTr("What are you riding?")
                description: appVersion
            }

            // A ride is going on: back to it.
            BackgroundItem {
                visible: !!page.ride.active
                height: Theme.itemSizeLarge
                onClicked: page.openRide()

                Label {
                    anchors {
                        left: parent.left
                        leftMargin: Theme.horizontalPageMargin
                        right: parent.right
                        rightMargin: Theme.horizontalPageMargin
                        verticalCenter: parent.verticalCenter
                    }
                    wrapMode: Text.Wrap
                    color: parent.highlighted ? Theme.highlightColor : Theme.primaryColor
                    text: qsTr("A ride is in progress. Back to it")
                }
            }

            // A ride the app was stopped in the middle of.
            Column {
                width: parent.width
                visible: !!page.unfinished && !page.ride.active
                spacing: Theme.paddingSmall

                SectionHeader { text: qsTr("Unfinished ride") }

                Label {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    wrapMode: Text.Wrap
                    color: Theme.highlightColor
                    text: page.unfinished
                          ? (page.unfinished.finished
                             ? qsTr("%1 finished, but was not saved. %2 ridden.").arg(page.unfinished.name)
                                   .arg(Format.duration(page.unfinished.rideSeconds))
                             : qsTr("%1 stopped after %2.").arg(page.unfinished.name)
                                   .arg(Format.duration(page.unfinished.rideSeconds)))
                          : ""
                }

                ButtonLayout {
                    Button {
                        visible: !!page.unfinished && !page.unfinished.finished && page.unfinished.kind !== "route"
                        text: qsTr("Continue")
                        onClicked: {
                            if (core.send({ "cmd": "unfinished.continue" }).ok)
                                page.openRide()
                        }
                    }
                    Button {
                        text: qsTr("Save")
                        onClicked: core.request({ "cmd": "unfinished.save" }, function(reply) {
                            if (!reply.ok)
                                saveError.text = reply.message
                        })
                    }
                    Button {
                        text: qsTr("Abandon")
                        onClicked: remorse.execute(qsTr("Abandoning the ride"), function() {
                            core.send({ "cmd": "unfinished.abandon" })
                        })
                    }
                }

                Label {
                    id: saveError

                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    visible: text.length > 0
                    wrapMode: Text.Wrap
                    color: Theme.errorColor
                    font.pixelSize: Theme.fontSizeSmall
                }
            }

            SectionHeader { text: qsTr("Ride") }

            ChoiceItem {
                title: qsTr("Workout")
                description: qsTr("A structured session from the library: the trainer holds each interval's power.")
                enabled: !page.ride.active
                onClicked: pageStack.push(Qt.resolvedUrl("WorkoutsPage.qml"))
            }

            ChoiceItem {
                title: qsTr("Just ride")
                description: qsTr("No plan. The trainer holds a power you move up and down as you go.")
                enabled: !page.ride.active
                onClicked: {
                    if (core.send({ "cmd": "ride.prepareFreeRide" }).ok)
                        page.openRide()
                }
            }

            SectionHeader { text: qsTr("Devices") }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: !page.bluetooth.available
                wrapMode: Text.Wrap
                color: Theme.errorColor
                font.pixelSize: Theme.fontSizeSmall
                text: qsTr("Bluetooth is off. Turn it on in the top menu or in Settings.")
            }

            DeviceItem {
                title: qsTr("Trainer")
                device: page.trainer
                emptyText: devBuild ? qsTr("Not paired. Pull down to pair one, or tap for the demo trainer.")
                                    : qsTr("Not paired. Pull down to pair one.")
                onClicked: {
                    if (!page.trainer.connected && devBuild)
                        core.send({ "cmd": "trainer.demo" })
                }
                detail: page.trainer.connected
                        ? qsTr("%1 W · %2 rpm").arg(page.trainer.power || 0).arg(page.trainer.cadence || 0)
                        : ""
            }

            DeviceItem {
                title: qsTr("Heart rate monitor")
                device: page.hrm
                emptyText: qsTr("Not paired. Pull down to pair one.")
                detail: page.hrm.connected ? qsTr("%1 bpm").arg(page.hrm.heartRate || "--") : ""
            }
        }

        VerticalScrollDecorator { }
    }

    RemorsePopup { id: remorse }
}

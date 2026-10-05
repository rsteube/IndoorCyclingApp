import QtQuick 2.6
import Sailfish.Silica 1.0
import "../components"
import "../components/Accent.js" as Accent
import "../components/Format.js" as Format

// "What are you riding?": the first page. A workout, a route, just riding or a companion; the
// trainer and the heart rate monitor, live; and a ride the app was stopped in the middle of.
Page {
    id: page

    objectName: "ridePage"

    readonly property var trainer: core.state.trainer || ({})
    readonly property var hrm: core.state.hrm || ({})
    readonly property var ride: core.state.ride || ({})
    readonly property var unfinished: core.state.unfinishedRide
    readonly property var bluetooth: core.state.bluetooth || ({})
    readonly property var settings: core.state.settings
    readonly property var progress: core.state.progress || ({})
    property bool welcomed

    function openRide() {
        pageStack.push(Qt.resolvedUrl("RideScreenPage.qml"))
    }

    function pairTrainer() {
        pageStack.push(Qt.resolvedUrl("PairPage.qml"))
    }

    function pairHeartRate() {
        pageStack.push(Qt.resolvedUrl("PairPage.qml"), {
            kind: "hrm",
            title: qsTr("Pair a heart rate monitor"),
            hint: qsTr("Put the strap on, or wet its contacts, so it starts sending.")
        })
    }

    // A ride needs the trainer: without one, pairing comes first, as on Android.
    function rideWith(command) {
        if (!page.trainer.connected) {
            pairTrainer()
            return
        }
        if (core.send(command).ok)
            page.openRide()
    }

    // The first start: once the settings have arrived and say the rider has not been welcomed.
    function welcomeIfNew() {
        if (welcomed || !settings || settings.onboarded || status !== PageStatus.Active)
            return
        welcomed = true
        pageStack.push(Qt.resolvedUrl("OnboardingDialog.qml"))
    }

    allowedOrientations: Orientation.All
    onSettingsChanged: welcomeIfNew()

    // History is one swipe forward: Sailfish's attached page, where Android has a tab.
    onStatusChanged: {
        if (status === PageStatus.Active && pageStack.nextPage(page) === null)
            pageStack.pushAttached(Qt.resolvedUrl("HistoryPage.qml"))
        welcomeIfNew()
    }

    SilicaFlickable {
        anchors.fill: parent
        contentHeight: column.height + Theme.paddingLarge

        PullDownMenu {
            MenuItem {
                text: qsTr("Settings")
                onClicked: pageStack.push(Qt.resolvedUrl("SettingsPage.qml"))
            }
            MenuItem {
                text: qsTr("Progress")
                onClicked: pageStack.push(Qt.resolvedUrl("ProgressPage.qml"))
            }
            MenuItem {
                visible: devBuild && !page.trainer.connected
                text: qsTr("Debug: demo trainer")
                onClicked: core.send({ "cmd": "trainer.demo" })
            }
        }

        Column {
            id: column

            width: parent.width
            spacing: Theme.paddingSmall

            PageHeader {
                title: qsTr("What are you riding?")
                description: (page.progress.rides || 0) > 0
                             ? qsTr("Level %1 · %2").arg(page.progress.level || 0).arg(page.progress.title || "")
                             : qsTr("Swipe left for your history")
            }

            // A ride is going on: back to it.
            BackgroundItem {
                id: backToRide

                visible: !!page.ride.active
                height: Theme.itemSizeLarge
                onClicked: page.openRide()

                Rectangle {
                    anchors.fill: parent
                    anchors.leftMargin: Theme.horizontalPageMargin - Theme.paddingSmall
                    anchors.rightMargin: anchors.leftMargin
                    radius: Theme.paddingLarge
                    color: Theme.rgba(Accent.success, backToRide.highlighted ? 0.3 : 0.16)
                }
                StatusDot {
                    id: liveDot

                    anchors {
                        left: parent.left
                        leftMargin: Theme.horizontalPageMargin + Theme.paddingMedium
                        verticalCenter: parent.verticalCenter
                    }
                    level: "busy"
                }
                Label {
                    anchors {
                        left: liveDot.right
                        leftMargin: Theme.paddingLarge
                        right: parent.right
                        rightMargin: Theme.horizontalPageMargin + Theme.paddingMedium
                        verticalCenter: parent.verticalCenter
                    }
                    wrapMode: Text.Wrap
                    color: backToRide.highlighted ? Theme.highlightColor : Theme.primaryColor
                    text: qsTr("A ride is in progress. Back to it")
                }
            }

            // A ride the app was stopped in the middle of.
            Column {
                width: parent.width
                visible: !!page.unfinished && !page.ride.active
                spacing: Theme.paddingMedium

                SectionHeader { text: qsTr("Unfinished ride") }

                SettingRow {
                    icon: "history"
                    tint: Accent.warning
                    enabled: false
                    title: page.unfinished ? page.unfinished.name : ""
                    subtitle: page.unfinished
                              ? (page.unfinished.finished
                                 ? qsTr("Finished, but not saved. %1 ridden.").arg(Format.duration(page.unfinished.rideSeconds))
                                 : qsTr("Stopped after %1.").arg(Format.duration(page.unfinished.rideSeconds)))
                              : ""
                }

                ButtonLayout {
                    Button {
                        visible: !!page.unfinished && !page.unfinished.finished
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

            // The first ride, suggested until it is ridden or turned down, as on Android.
            Column {
                width: parent.width
                visible: !!page.settings && page.settings.onboarded && !page.settings.starterDismissed
                         && (page.progress.rides || 0) === 0 && !page.ride.active
                spacing: Theme.paddingMedium

                SectionHeader { text: qsTr("Your first ride") }

                SettingRow {
                    icon: "flag"
                    tint: Accent.success
                    enabled: false
                    title: {
                        var workouts = core.list("workouts")
                        var revision = workouts.revision
                        return workouts.get("starter-15min").name || ""
                    }
                    subtitle: {
                        var workouts = core.list("workouts")
                        var revision = workouts.revision
                        return workouts.get("starter-15min").description || ""
                    }
                }

                ButtonLayout {
                    Button {
                        text: page.trainer.connected ? qsTr("Ride it") : qsTr("Pair a trainer to ride")
                        onClicked: page.rideWith({ "cmd": "ride.prepareWorkout", "id": "starter-15min" })
                    }
                    Button {
                        text: qsTr("Not now")
                        onClicked: core.send({ "cmd": "settings.set", "key": "starterDismissed", "value": true })
                    }
                }
            }

            SectionHeader { text: qsTr("Ride") }

            ChoiceItem {
                order: 0
                icon: "stacked_bar_chart"
                tint: Accent.power
                title: qsTr("Workout")
                description: qsTr("Structured intervals. The trainer holds each one's power.")
                enabled: !page.ride.active
                onClicked: pageStack.push(Qt.resolvedUrl("WorkoutsPage.qml"))
            }
            ChoiceItem {
                order: 1
                icon: "landscape"
                tint: Accent.green
                title: qsTr("Route")
                description: qsTr("A real road. The trainer gives you its hills; you shift as you would outside.")
                enabled: !page.ride.active
                onClicked: pageStack.push(Qt.resolvedUrl("RoutesPage.qml"))
            }
            ChoiceItem {
                order: 2
                icon: "speed"
                tint: Accent.sky
                title: qsTr("Just ride")
                description: page.trainer.connected
                             ? qsTr("No plan. Hold a power you move up and down as you go.")
                             : qsTr("No plan. Hold a power you move up and down as you go. Pair a trainer first.")
                enabled: !page.ride.active
                onClicked: page.rideWith({ "cmd": "ride.prepareFreeRide" })
            }
            ChoiceItem {
                order: 3
                icon: "sports_esports"
                tint: Accent.violet
                title: qsTr("Companion")
                description: qsTr("A game on another screen describes the road, and the trainer rides it.")
                enabled: !page.ride.active
                onClicked: pageStack.push(Qt.resolvedUrl("CompanionPage.qml"))
            }

            SectionHeader { text: qsTr("Devices") }

            SettingRow {
                visible: !page.bluetooth.available
                enabled: false
                icon: "bluetooth"
                tint: Theme.errorColor
                title: qsTr("Bluetooth is off")
                subtitle: qsTr("Turn it on in the top menu or in Settings.")
                status: "bad"
            }

            DeviceItem {
                title: qsTr("Trainer")
                icon: "pedal_bike"
                tint: Accent.power
                device: page.trainer
                emptyText: qsTr("Not paired. Tap to pair one.")
                reading: page.trainer.power || 0
                unit: "W"
                onClicked: if (!page.trainer.connected) page.pairTrainer()
            }

            DeviceItem {
                title: qsTr("Heart rate")
                icon: "favorite"
                tint: Accent.heart
                device: page.hrm
                emptyText: qsTr("Not paired. Tap to pair one.")
                reading: page.hrm.heartRate || 0
                unit: "bpm"
                heart: true
                onClicked: if (!page.hrm.connected) page.pairHeartRate()
            }
        }

        VerticalScrollDecorator { }
    }

    RemorsePopup { id: remorse }
}

import QtQuick 2.6
import Sailfish.Silica 1.0
import Nemo.KeepAlive 1.2
import "../components"
import "../components/Format.js" as Format

// The ride: power against its target in the zone's colour, cadence, heart rate, the interval and
// what comes next, the workout's shape with progress, and harder or easier. The ride itself runs
// in the shared core, so leaving this page does not stop it; the Ride page offers it back.
Page {
    id: page

    readonly property var ride: core.state.ride || ({})
    readonly property var rideWorkout: core.state.rideWorkout || ({})
    readonly property var trainer: core.state.trainer || ({})
    readonly property var hrm: core.state.hrm || ({})
    readonly property var settings: core.state.settings || ({})
    readonly property var zones: core.state.zones || []
    readonly property var segment: ride.segment || ({})
    readonly property var next: ride.next
    readonly property string rideState: ride.state || "Idle"
    readonly property bool structured: ride.kind === "workout"
    readonly property bool free: ride.kind === "free"
    readonly property int power: trainer.power || 0
    readonly property int heartRate: hrm.connected && hrm.heartRate > 0 ? hrm.heartRate : (trainer.heartRate || 0)
    readonly property int target: segment.targetWatts || 0
    readonly property int ftp: settings.ftp || 200
    readonly property color powerColor: Format.zoneColor(zones, Format.zoneForPct(zones, power * 100 / ftp),
                                                         Theme.primaryColor)

    // What the last completed ride came to, from the core's ride.saved.
    property var saved: null

    allowedOrientations: Orientation.All

    // The screen stays on for the whole ride, the way a ride screen on a stand is used.
    DisplayBlanking { preventBlanking: !!ride.active }

    Connections {
        target: core
        onEventReceived: {
            if (topic === "ride.saved")
                page.saved = payload
        }
    }

    SilicaFlickable {
        anchors.fill: parent
        contentHeight: Math.max(page.height, content.height + Theme.paddingLarge)

        PullDownMenu {
            visible: !!ride.active
            MenuItem {
                text: qsTr("End and save")
                onClicked: core.send({ "cmd": "ride.end" })
            }
            MenuItem {
                visible: page.structured
                text: qsTr("Skip this interval")
                onClicked: core.send({ "cmd": "ride.skip" })
            }
            MenuItem {
                text: page.rideState === "Paused" ? qsTr("Resume") : qsTr("Pause")
                onClicked: core.send({ "cmd": page.rideState === "Paused" ? "ride.resume" : "ride.pause" })
            }
        }

        Column {
            id: content

            width: parent.width
            spacing: Theme.paddingLarge
            visible: page.rideState !== "Completed"

            PageHeader {
                title: page.rideWorkout.name || qsTr("Ride")
                description: page.structured
                             ? qsTr("Interval %1 of %2").arg((ride.segmentIndex || 0) + 1).arg(ride.segmentCount || 0)
                             : ""
            }

            // Where the ride is: waiting for the pedals, counting down, paused, or riding.
            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                horizontalAlignment: Text.AlignHCenter
                visible: page.rideState === "Standby" || page.rideState === "Paused" || page.rideState === "Countdown"
                wrapMode: Text.Wrap
                color: Theme.highlightColor
                font.pixelSize: page.rideState === "Countdown" ? Theme.fontSizeHuge * 2 : Theme.fontSizeLarge
                text: page.rideState === "Countdown" ? (ride.countdown || "")
                      : page.rideState === "Paused" ? qsTr("Paused")
                      : (ride.rideSeconds > 0 ? qsTr("Pedal to carry on") : qsTr("Pedal to start"))
            }

            // Power, in the colour of the zone it is in, against what the ride asks for.
            Column {
                width: parent.width
                spacing: 0

                Label {
                    anchors.horizontalCenter: parent.horizontalCenter
                    text: trainer.connected ? page.power : "--"
                    color: page.powerColor
                    font.pixelSize: Theme.fontSizeHuge * (page.isLandscape ? 1.6 : 2.2)
                    font.family: Theme.fontFamilyHeading
                }
                Label {
                    anchors.horizontalCenter: parent.horizontalCenter
                    visible: page.target > 0
                    text: qsTr("of %1 W").arg(page.target)
                    color: Theme.secondaryColor
                    font.pixelSize: Theme.fontSizeMedium
                }
            }

            Row {
                width: parent.width

                Reading {
                    width: parent.width / 3
                    label: qsTr("Cadence")
                    value: trainer.connected ? (trainer.cadence || 0) : "--"
                    unit: "rpm"
                }
                Reading {
                    width: parent.width / 3
                    label: qsTr("Heart rate")
                    value: page.heartRate > 0 ? page.heartRate : "--"
                    unit: "bpm"
                }
                Reading {
                    width: parent.width / 3
                    label: page.structured ? qsTr("Interval left") : qsTr("Ride time")
                    value: page.structured ? Format.clock(page.segment.remaining) : Format.clock(ride.rideSeconds)
                }
            }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: !!page.next
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.Wrap
                color: Theme.highlightColor
                font.pixelSize: Theme.fontSizeSmall
                text: page.next ? qsTr("Next: %1 at %2 W for %3").arg(page.next.type).arg(page.next.targetWatts)
                                                                 .arg(Format.clock(page.next.seconds)) : ""
            }

            WorkoutProfile {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                height: Theme.itemSizeLarge
                visible: page.structured
                segments: page.rideWorkout.segments || []
                elapsed: ride.elapsed || 0
            }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: page.structured
                horizontalAlignment: Text.AlignHCenter
                color: Theme.secondaryColor
                font.pixelSize: Theme.fontSizeSmall
                text: qsTr("%1 left of %2").arg(Format.clock(ride.remainingSeconds)).arg(Format.clock(ride.totalSeconds))
            }

            // Harder or easier: the plan's intensity, or a free ride's target.
            Row {
                anchors.horizontalCenter: parent.horizontalCenter
                spacing: Theme.paddingLarge
                visible: page.structured || page.free

                IconButton {
                    icon.source: "image://theme/icon-m-remove"
                    onClicked: core.send({ "cmd": "ride.step", "direction": -1 })
                }
                Label {
                    anchors.verticalCenter: parent.verticalCenter
                    width: Theme.itemSizeExtraLarge
                    horizontalAlignment: Text.AlignHCenter
                    color: Theme.highlightColor
                    text: page.free ? qsTr("%1 W").arg(ride.freeRideTarget || 0)
                                    : (ride.biasPercent > 0 ? "+" : "") + (ride.biasPercent || 0) + " %"
                }
                IconButton {
                    icon.source: "image://theme/icon-m-add"
                    onClicked: core.send({ "cmd": "ride.step", "direction": 1 })
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

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: !trainer.connected
                wrapMode: Text.Wrap
                color: Theme.errorColor
                font.pixelSize: Theme.fontSizeSmall
                text: qsTr("The trainer is not connected. The ride starts when it is, and you pedal.")
            }
        }

        // The ride is done and saved.
        Column {
            width: parent.width
            spacing: Theme.paddingLarge
            visible: page.rideState === "Completed"

            PageHeader { title: qsTr("Ride complete") }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                wrapMode: Text.Wrap
                color: Theme.highlightColor
                text: page.saved === null ? qsTr("Saving the ride...")
                      : page.saved.ok ? qsTr("%1 ridden, %2 W on average. Saved to your history.")
                                        .arg(Format.duration(page.saved.rideSeconds)).arg(page.saved.averagePower)
                      : qsTr("The ride couldn't be saved.")
            }

            Button {
                anchors.horizontalCenter: parent.horizontalCenter
                text: qsTr("Done")
                onClicked: {
                    core.send({ "cmd": "ride.close" })
                    pageStack.pop(pageStack.find(function(p) { return p.objectName === "ridePage" }))
                }
            }
        }

        VerticalScrollDecorator { }
    }
}

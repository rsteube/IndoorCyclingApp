import QtQuick 2.6
import Sailfish.Silica 1.0
import Nemo.KeepAlive 1.2
import harbour.indoorbike 1.0
import "../components"
import "../components/Format.js" as Format
import "../components/Gradient.js" as Gradient

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
    readonly property var sim: core.state.simulation
    readonly property var road: core.state.rideRoute
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

    // A companion ride: the game on another device describes the road.
    readonly property bool isCompanion: ride.kind === "companion"
    readonly property var companion: core.state.companion || ({})
    readonly property var croad: core.state.companionRoad || ({})
    readonly property string phase: companion.phase || "LIVE"
    readonly property string companionName: companion.lookName || companion.controllerName || companion.companionName
                                             || qsTr("Companion")
    readonly property string companionNotice: {
        if (companion.screenshotMessage)
            return companion.screenshotMessage
        if (!companion.controllerName)
            return qsTr("No companion connected: the road is flat")
        if (croad.roadStale)
            return qsTr("%1 has gone quiet: flattening the road").arg(companionName)
        return ""
    }
    readonly property var controls: companion.controls || null

    function controlEnabled(control) {
        return !!controls && !!controls.enabled && controls.enabled.indexOf(control) >= 0
    }

    // Leave the ride screen once a companion session is over: back to the Companion page when it
    // is still open, which keeps the connection for the next session.
    function finishCompanion() {
        core.send({ "cmd": "ride.close" })
        var companionPage = pageStack.find(function(p) { return p.objectName === "companionPage" })
        if (companionPage) {
            pageStack.pop(companionPage)
        } else {
            core.send({ "cmd": "companion.stop" })
            pageStack.pop(pageStack.find(function(p) { return p.objectName === "ridePage" }))
        }
    }

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
            visible: !!ride.active && (!page.isCompanion || page.phase === "LIVE")
            MenuItem {
                visible: page.isCompanion && !!page.companion.controllerName
                text: qsTr("Take back control")
                onClicked: core.send({ "cmd": "companion.revoke" })
            }
            MenuItem {
                visible: page.isCompanion && !!page.companion.screenshotsOffered
                enabled: !page.companion.screenshotWaiting
                text: qsTr("Take a screenshot")
                onClicked: core.send({ "cmd": "companion.screenshot" })
            }
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
            visible: page.isCompanion ? page.phase === "LIVE" : page.rideState !== "Completed"

            PageHeader {
                title: page.isCompanion ? page.companionName : (page.rideWorkout.name || qsTr("Ride"))
                description: page.structured
                             ? qsTr("Interval %1 of %2").arg((ride.segmentIndex || 0) + 1).arg(ride.segmentCount || 0)
                             : page.isCompanion ? [page.companion.label, page.companion.world].filter(function(x) { return !!x }).join(" · ")
                                                : ""
            }

            // The game: its icon and colour, and what it is doing to the road.
            Row {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                spacing: Theme.paddingMedium
                visible: page.isCompanion && (!!page.companion.icon || page.companionNotice.length > 0)

                Image {
                    width: Theme.iconSizeMedium
                    height: width
                    visible: !!page.companion.icon
                    source: page.companion.icon ? "file://" + page.companion.icon : ""
                    sourceSize.width: width
                    sourceSize.height: height
                }
                Label {
                    width: parent.width - (page.companion.icon ? Theme.iconSizeMedium + Theme.paddingMedium : 0)
                    anchors.verticalCenter: parent.verticalCenter
                    wrapMode: Text.Wrap
                    color: page.companion.accent || Theme.highlightColor
                    font.pixelSize: Theme.fontSizeSmall
                    text: page.companionNotice
                }
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
                    visible: page.isCompanion && page.croad.powerTarget > 0
                    text: qsTr("Target %1 W").arg(page.croad.powerTarget || 0)
                    color: Theme.secondaryColor
                    font.pixelSize: Theme.fontSizeMedium
                }
                Label {
                    anchors.horizontalCenter: parent.horizontalCenter
                    visible: page.target > 0 && !page.isCompanion
                    text: qsTr("of %1 W").arg(page.target)
                    color: Theme.secondaryColor
                    font.pixelSize: Theme.fontSizeMedium
                }
            }

            // A companion ride: the road's gradient, the gear and the cadence, the rest on request.
            Row {
                width: parent.width
                visible: page.isCompanion

                Reading {
                    width: parent.width / 3
                    label: qsTr("Gradient")
                    value: (page.croad.gradientText || "0") + " %"
                    valueColor: Gradient.colors[page.croad.band] || Theme.highlightColor
                }
                Reading {
                    width: parent.width / 3
                    label: qsTr("Gear")
                    value: page.croad.gear || "--"
                }
                Reading {
                    width: parent.width / 3
                    label: qsTr("Cadence")
                    value: trainer.connected ? (trainer.cadence || 0) : "--"
                    unit: "rpm"
                }
            }
            Grid {
                width: parent.width
                columns: 2
                rowSpacing: Theme.paddingLarge
                visible: page.isCompanion && !!page.companion.showAllData

                Reading {
                    width: parent.width / 2
                    label: qsTr("Speed")
                    value: (page.croad.speedKmh || 0).toFixed(1)
                    unit: "km/h"
                }
                Reading {
                    width: parent.width / 2
                    label: qsTr("Distance")
                    value: ((page.croad.distanceMeters || 0) / 1000).toFixed(2)
                    unit: "km"
                }
                Reading {
                    width: parent.width / 2
                    label: qsTr("Climbed")
                    value: Math.round(page.croad.climbedMeters || 0)
                    unit: "m"
                }
                Reading {
                    width: parent.width / 2
                    label: qsTr("Heart rate")
                    value: page.heartRate > 0 ? page.heartRate : "--"
                    unit: "bpm"
                }
            }

            // The paddles: one gear easier or harder, big enough to hit while riding.
            Row {
                anchors.horizontalCenter: parent.horizontalCenter
                spacing: Theme.paddingLarge
                visible: page.isCompanion

                Repeater {
                    model: [{ "steps": -1, "label": qsTr("Easier"), "icon": "image://theme/icon-m-down" },
                            { "steps": 1, "label": qsTr("Harder"), "icon": "image://theme/icon-m-up" }]

                    BackgroundItem {
                        width: (page.width - 2 * Theme.horizontalPageMargin - Theme.paddingLarge) / 2
                        height: Theme.itemSizeHuge
                        enabled: page.rideState !== "Countdown"
                        onClicked: core.send({ "cmd": "companion.shift", "steps": modelData.steps })

                        Rectangle {
                            anchors.fill: parent
                            radius: Theme.paddingMedium
                            color: Theme.rgba(page.companion.accent || Theme.highlightBackgroundColor, 0.3)
                        }
                        Column {
                            anchors.centerIn: parent
                            Icon {
                                anchors.horizontalCenter: parent.horizontalCenter
                                source: modelData.icon
                            }
                            Label {
                                anchors.horizontalCenter: parent.horizontalCenter
                                color: Theme.highlightColor
                                text: modelData.label
                            }
                        }
                    }
                }
            }

            // The game's own menu, steered from the phone, when it offers it.
            Column {
                width: parent.width
                spacing: Theme.paddingSmall
                visible: page.isCompanion && !!page.controls && page.controls.enabled.length > 0

                Label {
                    anchors.horizontalCenter: parent.horizontalCenter
                    visible: !!page.controls && !!page.controls.title
                    color: Theme.highlightColor
                    font.pixelSize: Theme.fontSizeSmall
                    text: page.controls && page.controls.title ? page.controls.title : ""
                }
                Row {
                    anchors.horizontalCenter: parent.horizontalCenter
                    spacing: Theme.paddingMedium

                    IconButton {
                        anchors.verticalCenter: parent.verticalCenter
                        enabled: page.controlEnabled("left")
                        icon.source: "image://theme/icon-m-left"
                        onClicked: core.send({ "cmd": "companion.press", "control": "left" })
                    }
                    Column {
                        IconButton {
                            enabled: page.controlEnabled("up")
                            icon.source: "image://theme/icon-m-up"
                            onClicked: core.send({ "cmd": "companion.press", "control": "up" })
                        }
                        IconButton {
                            enabled: page.controlEnabled("enter")
                            icon.source: "image://theme/icon-m-acknowledge"
                            onClicked: core.send({ "cmd": "companion.press", "control": "enter" })
                        }
                        IconButton {
                            enabled: page.controlEnabled("down")
                            icon.source: "image://theme/icon-m-down"
                            onClicked: core.send({ "cmd": "companion.press", "control": "down" })
                        }
                    }
                    IconButton {
                        anchors.verticalCenter: parent.verticalCenter
                        enabled: page.controlEnabled("right")
                        icon.source: "image://theme/icon-m-right"
                        onClicked: core.send({ "cmd": "companion.press", "control": "right" })
                    }
                    IconButton {
                        anchors.verticalCenter: parent.verticalCenter
                        enabled: page.controlEnabled("back")
                        icon.source: "image://theme/icon-m-back"
                        onClicked: core.send({ "cmd": "companion.press", "control": "back" })
                    }
                }
                Label {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    visible: !!page.controls && !!page.controls.hint
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.Wrap
                    color: Theme.secondaryColor
                    font.pixelSize: Theme.fontSizeExtraSmall
                    text: page.controls && page.controls.hint ? page.controls.hint : ""
                }
            }

            Row {
                width: parent.width
                visible: !page.isCompanion

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

            // A route: the road under the wheels and ahead, where the rider is on it, the gear.
            Column {
                width: parent.width
                spacing: Theme.paddingMedium
                visible: ride.kind === "route" && !!page.sim

                Label {
                    anchors.horizontalCenter: parent.horizontalCenter
                    color: page.sim ? (Gradient.colors[page.sim.band] || Theme.highlightColor) : Theme.highlightColor
                    font.pixelSize: Theme.fontSizeHuge
                    text: page.sim ? page.sim.gradientText + " %" : ""
                }

                // The next 500 m, in 50 m steps.
                Row {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    Repeater {
                        model: page.sim ? page.sim.ahead : []
                        Rectangle {
                            width: (page.width - 2 * Theme.horizontalPageMargin) / 10
                            height: Theme.paddingMedium
                            color: Gradient.colors[modelData] || Theme.highlightColor
                        }
                    }
                }

                ElevationView {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    height: Theme.itemSizeLarge
                    profile: page.road ? page.road.profile : []
                    bands: page.road ? page.road.bands : []
                    bandColors: Gradient.colors
                    fraction: page.sim ? page.sim.fraction : -1
                    color: Theme.secondaryHighlightColor
                    markerColor: Theme.primaryColor
                }

                Row {
                    width: parent.width

                    Reading {
                        width: parent.width / 3
                        label: qsTr("Speed")
                        value: page.sim ? page.sim.speedKmh.toFixed(1) : "--"
                        unit: "km/h"
                    }
                    Reading {
                        width: parent.width / 3
                        label: qsTr("To go")
                        value: page.sim ? (page.sim.remainingMeters / 1000).toFixed(1) : "--"
                        unit: "km"
                    }
                    Reading {
                        width: parent.width / 3
                        label: qsTr("Climbed")
                        value: page.sim ? Math.round(page.sim.climbedMeters) : "--"
                        unit: "m"
                    }
                }

                // The virtual gear, shifted here or with a controller.
                Row {
                    anchors.horizontalCenter: parent.horizontalCenter
                    spacing: Theme.paddingLarge

                    IconButton {
                        icon.source: "image://theme/icon-m-remove"
                        onClicked: core.send({ "cmd": "sim.shift", "steps": -1 })
                    }
                    Label {
                        anchors.verticalCenter: parent.verticalCenter
                        width: Theme.itemSizeExtraLarge * 1.5
                        horizontalAlignment: Text.AlignHCenter
                        color: Theme.highlightColor
                        text: page.sim ? qsTr("Gear %1 of %2").arg(page.sim.gear).arg(page.sim.gears)
                                         + (page.sim.gearLabel ? "\n" + page.sim.gearLabel : "") : ""
                    }
                    IconButton {
                        icon.source: "image://theme/icon-m-add"
                        onClicked: core.send({ "cmd": "sim.shift", "steps": 1 })
                    }
                }

                RouteMap {
                    anchors.horizontalCenter: parent.horizontalCenter
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    height: Theme.itemSizeHuge * 1.5
                    points: page.road ? page.road.shape : []
                    fraction: page.sim ? page.sim.fraction : -1
                    color: Theme.secondaryHighlightColor
                    riddenColor: Theme.highlightColor
                    markerColor: Theme.primaryColor
                    lineWidth: Theme.paddingSmall / 2
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

        // A companion session between rides, or over: what was ridden, and the way out.
        Column {
            width: parent.width
            spacing: Theme.paddingLarge
            visible: page.isCompanion && page.phase !== "LIVE"

            PageHeader { title: page.phase === "ENDED" ? qsTr("Session ended") : qsTr("Ride paused") }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: page.phase === "COMPANION_AWAY"
                wrapMode: Text.Wrap
                color: Theme.highlightColor
                text: qsTr("%1 has disconnected. Waiting for it to reconnect. The ride is paused, and saved as a completed ride when you end it.")
                      .arg(page.companionName)
            }
            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                wrapMode: Text.Wrap
                color: Theme.secondaryHighlightColor
                text: !page.companion.sessionRides || page.companion.sessionRides.length === 0
                      ? qsTr("No rides were recorded in this session.")
                      : qsTr("Every ride below is saved in your history.")
            }
            Repeater {
                model: page.companion.sessionRides || []

                DetailItem {
                    label: modelData.name
                    value: Format.clock(modelData.durationSeconds) + " · " + qsTr("%1 W").arg(modelData.averagePower)
                }
            }
            ButtonLayout {
                Button {
                    visible: page.phase === "ENDED"
                    text: qsTr("Done")
                    onClicked: page.finishCompanion()
                }
                Button {
                    visible: page.phase !== "ENDED"
                    text: qsTr("End ride")
                    onClicked: core.send({ "cmd": "ride.end" })
                }
                Button {
                    visible: page.phase !== "ENDED"
                    text: qsTr("End session")
                    onClicked: core.send({ "cmd": "companion.endSession" })
                }
            }
        }

        // The ride is done and saved.
        Column {
            width: parent.width
            spacing: Theme.paddingLarge
            visible: page.rideState === "Completed" && !page.isCompanion

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

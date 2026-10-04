import QtQuick 2.6
import Sailfish.Silica 1.0
import Nemo.KeepAlive 1.2
import harbour.indoorbike 1.0
import "../components"
import "../components/Format.js" as Format
import "../components/Gradient.js" as Gradient

// The ride: power in its zone's colour against what the ride asks, cadence against its target,
// heart rate in its zone, the interval and what comes next, the workout's shape, the road on a
// route, and harder or easier. The ride itself runs in the shared core; the rules for what the
// rider may do in each state are the Android ride screen's.
Page {
    id: page

    objectName: "rideScreen"

    readonly property var ride: core.state.ride || ({})
    readonly property var rideWorkout: core.state.rideWorkout || ({})
    readonly property var trainer: core.state.trainer || ({})
    readonly property var hrm: core.state.hrm || ({})
    readonly property var settings: core.state.settings || ({})
    readonly property var zones: core.state.zones || []
    readonly property var heartRateZones: core.state.heartRateZones || []
    readonly property var segment: ride.segment || ({})
    readonly property var sim: core.state.simulation
    readonly property var road: core.state.rideRoute
    readonly property var next: ride.next
    readonly property string rideState: ride.state || "Idle"
    readonly property bool structured: ride.kind === "workout"
    readonly property bool free: ride.kind === "free"
    readonly property bool route: ride.kind === "route"
    readonly property bool started: !!ride.started
    readonly property int power: trainer.power || 0
    readonly property int cadence: trainer.cadence || 0
    readonly property int heartRate: hrm.connected && hrm.heartRate > 0 ? hrm.heartRate : (trainer.heartRate || 0)
    readonly property int ftp: settings.ftp || 200
    readonly property real weightKg: settings.weightKg || 0

    // What the trainer is asked to hold: the interval's watts, or a free ride's.
    readonly property int targetWatts: structured ? (segment.targetWatts || 0) : free ? (ride.freeRideTarget || 0) : 0
    readonly property bool ergLocked: rideState === "Active" && targetWatts > 0
    readonly property bool onTarget: ergLocked && !!trainer.connected
                                     && Math.abs(power - targetWatts) <= Math.max(5, targetWatts * 3 / 100)

    // The zone the power is drawn in, as on Android: on a road nothing sets a target, so it is the
    // effort the rider is making; on a free ride, the target they dialled in; otherwise the interval's.
    readonly property int heroZone: route || isCompanion ? Format.zoneForPct(zones, power * 100 / ftp)
                                    : free ? Format.zoneForPct(zones, targetWatts * 100 / ftp)
                                    : (segment.zone || 1)
    readonly property color powerColor: Format.zoneColor(zones, heroZone, Theme.primaryColor)

    readonly property var heartRateZone: {
        var maxHr = settings.maxHr || 0
        if (heartRate <= 0 || maxHr <= 0 || heartRate * 100 / maxHr < 50)
            return null
        var pct = Math.floor(heartRate * 100 / maxHr)
        for (var i = 0; i < heartRateZones.length; ++i) {
            if (pct <= heartRateZones[i].maxHrHighPct)
                return heartRateZones[i]
        }
        return null
    }

    readonly property var cadenceTarget: segment.cadence || null
    readonly property bool cadenceOff: !!cadenceTarget && cadence > 0
                                       && (cadence < cadenceTarget.low || cadence > cadenceTarget.high)

    // Paused, auto-paused and the like: worth a glance, not an error.
    readonly property color warningColor: "#F0C419"

    // End and save: where Android offers it, and also while a structured workout is riding along,
    // which Android reaches through its back button and a confirmation; here the remorse timer is
    // the confirmation.
    readonly property bool canEnd: !!ride.canEndAndSave || (structured && rideState === "Active" && started)

    readonly property string stateText: {
        switch (rideState) {
        case "Countdown":
            return ride.countdown > 0 ? String(ride.countdown) : qsTr("Go!")
        case "Standby":
            if (ride.canEndAndSave)
                return qsTr("Auto-paused · pedal to resume")
            return trainer.connected ? qsTr("Pedal to start") : qsTr("Waiting for the trainer")
        case "Paused":
            return qsTr("Paused · trainer at 0 W")
        case "Untracked":
            return ride.untracked ? qsTr("Not recording · %1").arg(ride.untracked) : qsTr("Not recording")
        default:
            return ""
        }
    }

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

    function backToRidePage() {
        pageStack.pop(pageStack.find(function(p) { return p.objectName === "ridePage" }))
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
            backToRidePage()
        }
    }

    // What the last completed ride came to, from the core's ride.saved.
    property var saved: null

    allowedOrientations: Orientation.All

    // Riding, a swipe back must not throw the ride away: the pull-down holds End and save. Before
    // the ride has started, and while it is paused, leaving is allowed, as Android's back is.
    backNavigation: isCompanion || !(rideState === "Active" && started)

    // Leaving the screen leaves the ride, as on Android: one that never started is gone, one that
    // had (paused, auto-paused) is kept and the Ride page offers it back as unfinished. A companion
    // ride belongs to its Companion page instead.
    Component.onDestruction: {
        if (!isCompanion && ride.active)
            core.send({ "cmd": "ride.leave" })
    }

    // The screen stays on for the whole ride, the way a ride screen on a stand is used.
    DisplayBlanking { preventBlanking: !!ride.active }

    Connections {
        target: core
        onEventReceived: {
            if (topic === "ride.saved")
                page.saved = payload
        }
    }

    RemorsePopup { id: remorse }

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
                visible: !page.isCompanion && !page.started
                text: qsTr("Leave without riding")
                onClicked: {
                    core.send({ "cmd": "ride.leave" })
                    page.backToRidePage()
                }
            }
            MenuItem {
                visible: page.canEnd || (page.isCompanion && page.started)
                text: qsTr("End and save")
                onClicked: remorse.execute(qsTr("Ending and saving the ride"), function() {
                    core.send({ "cmd": "ride.end" })
                })
            }
            MenuItem {
                visible: page.structured && (page.rideState === "Active" || page.rideState === "Paused")
                text: qsTr("Skip this interval")
                onClicked: core.send({ "cmd": "ride.skip" })
            }
            MenuItem {
                visible: page.rideState === "Active" || page.rideState === "Untracked" || page.rideState === "Paused"
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
                description: {
                    if (page.isCompanion)
                        return [page.companion.label, page.companion.world].filter(function(x) { return !!x }).join(" · ")
                    if (page.structured)
                        return Format.clock(ride.elapsed || 0) + " / " + Format.clock(ride.totalSeconds || 0) + " · "
                               + qsTr("Interval %1 of %2").arg((ride.segmentIndex || 0) + 1).arg(ride.segmentCount || 0)
                    return Format.clock(ride.rideSeconds || 0)
                }
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

            // Where the ride is when it is not simply riding: waiting, counting down, paused.
            Column {
                width: parent.width
                spacing: Theme.paddingMedium
                visible: page.stateText.length > 0

                Label {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.Wrap
                    color: page.rideState === "Paused" || !!ride.canEndAndSave || page.rideState === "Untracked"
                           ? page.warningColor : Theme.highlightColor
                    font.pixelSize: page.rideState === "Countdown" ? Theme.fontSizeHuge * 2 : Theme.fontSizeLarge
                    text: page.stateText
                }
                Label {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    visible: !page.started && page.rideState === "Standby" && !page.isCompanion
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.Wrap
                    color: Theme.secondaryHighlightColor
                    font.pixelSize: Theme.fontSizeExtraSmall
                    text: trainer.connected
                          ? qsTr("The ride starts with a countdown once you pedal. Pull down to leave without riding.")
                          : qsTr("It starts once the trainer is connected and you pedal. Pull down to leave without riding.")
                }
                Button {
                    anchors.horizontalCenter: parent.horizontalCenter
                    visible: !trainer.connected && !page.started && !page.isCompanion
                    text: qsTr("Pair a trainer")
                    onClicked: pageStack.push(Qt.resolvedUrl("PairPage.qml"))
                }
            }

            // Power, in its zone's colour, against what the ride asks for.
            Column {
                width: parent.width
                spacing: 0

                Label {
                    anchors.horizontalCenter: parent.horizontalCenter
                    visible: page.structured || page.free
                    color: page.onTarget ? page.powerColor : Theme.secondaryHighlightColor
                    font.pixelSize: Theme.fontSizeExtraSmall
                    font.capitalization: Font.AllUppercase
                    text: page.onTarget ? qsTr("Power · on target")
                          : page.ergLocked ? qsTr("Power · ERG locked") : qsTr("Power · ERG idle")
                }
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
                    visible: page.structured && page.targetWatts > 0
                    text: qsTr("of %1 W").arg(page.targetWatts)
                    color: Theme.secondaryColor
                    font.pixelSize: Theme.fontSizeMedium
                }
                Label {
                    anchors.horizontalCenter: parent.horizontalCenter
                    visible: page.weightKg > 0 && !!trainer.connected
                    text: qsTr("%1 W/kg").arg((page.power / page.weightKg).toFixed(1))
                    color: Theme.secondaryColor
                    font.pixelSize: Theme.fontSizeSmall
                }
            }

            // Harder or easier: the plan's intensity, or a free ride's target. A free ride's can be
            // set before the ride starts.
            Row {
                anchors.horizontalCenter: parent.horizontalCenter
                spacing: Theme.paddingLarge
                visible: page.structured || page.free

                IconButton {
                    icon.source: "image://theme/icon-m-remove"
                    onClicked: core.send({ "cmd": "ride.step", "direction": -1 })
                }
                Column {
                    anchors.verticalCenter: parent.verticalCenter
                    width: Theme.itemSizeExtraLarge * 1.4

                    Label {
                        width: parent.width
                        horizontalAlignment: Text.AlignHCenter
                        color: Theme.highlightColor
                        font.pixelSize: Theme.fontSizeLarge
                        text: page.free ? qsTr("%1 W").arg(ride.freeRideTarget || 0)
                                        : (ride.biasPercent > 0 ? "+" : "") + (ride.biasPercent || 0) + " %"
                    }
                    Label {
                        width: parent.width
                        horizontalAlignment: Text.AlignHCenter
                        color: Theme.secondaryHighlightColor
                        font.pixelSize: Theme.fontSizeExtraSmall
                        text: page.free ? qsTr("target") : qsTr("intensity")
                    }
                }
                IconButton {
                    icon.source: "image://theme/icon-m-add"
                    onClicked: core.send({ "cmd": "ride.step", "direction": 1 })
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
                    value: trainer.connected ? page.cadence : "--"
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

            // Cadence against the interval's target, heart rate in its zone, and the clock that
            // matters: the interval's on a workout, the ride's otherwise.
            Row {
                width: parent.width
                visible: !page.isCompanion

                Reading {
                    width: parent.width / 3
                    label: page.cadenceTarget ? qsTr("Cadence · %1").arg(page.cadenceTarget.label) : qsTr("Cadence")
                    value: trainer.connected ? page.cadence : "--"
                    valueColor: page.cadenceOff ? page.warningColor : Theme.primaryColor
                    unit: "rpm"
                }
                Reading {
                    width: parent.width / 3
                    label: page.heartRateZone ? qsTr("Heart rate · Z%1").arg(page.heartRateZone.number) : qsTr("Heart rate")
                    value: page.heartRate > 0 ? page.heartRate : "--"
                    valueColor: page.heartRateZone ? page.heartRateZone.color : Theme.primaryColor
                    unit: "bpm"
                }
                Reading {
                    width: parent.width / 3
                    label: page.structured ? qsTr("Interval left") : qsTr("Ride time")
                    value: page.structured ? Format.clock(page.segment.remaining) : Format.clock(ride.rideSeconds)
                    // The last five seconds of an interval: the change is coming.
                    valueColor: page.structured && page.rideState === "Active" && page.segment.remaining <= 5
                                ? page.warningColor : Theme.primaryColor
                }
            }

            // How far the effort would have carried the rider on a road, on a workout or a free ride.
            Row {
                width: parent.width
                visible: (page.structured || page.free) && page.weightKg > 0 && page.rideState !== "Countdown"
                         && ride.virtualSpeedKmh !== undefined && ride.virtualSpeedKmh !== null

                Reading {
                    width: parent.width / 2
                    label: qsTr("Speed")
                    value: (ride.virtualSpeedKmh || 0).toFixed(1)
                    unit: "km/h"
                }
                Reading {
                    width: parent.width / 2
                    label: qsTr("Distance")
                    value: ((ride.distanceMeters || 0) / 1000).toFixed(2)
                    unit: "km"
                }
            }

            // The interval being ridden, and the one after it.
            Column {
                width: parent.width
                spacing: Theme.paddingSmall
                visible: page.structured && !!page.segment.type

                Label {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    horizontalAlignment: Text.AlignHCenter
                    color: Format.zoneColor(page.zones, page.segment.zone || 1, Theme.highlightColor)
                    font.pixelSize: Theme.fontSizeMedium
                    text: qsTr("%1 · %2 % FTP").arg(page.segment.type || "").arg(page.segment.pct || 0)
                }
                Label {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    visible: !!page.next
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.Wrap
                    color: Theme.secondaryHighlightColor
                    font.pixelSize: Theme.fontSizeSmall
                    text: page.next ? qsTr("Up next · Z%1 %2 %3 · %4 W").arg(page.next.zone)
                                          .arg(page.zones[page.next.zone - 1] ? page.zones[page.next.zone - 1].label : "")
                                          .arg(Format.clock(page.next.seconds)).arg(page.next.targetWatts)
                                    : ""
                }
            }

            WorkoutProfile {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                height: Theme.itemSizeLarge
                visible: page.structured
                segments: page.rideWorkout.segments || []
                elapsed: ride.elapsed || 0
            }

            // A route: the road under the wheels and ahead, where the rider is on it, the gear.
            Column {
                width: parent.width
                spacing: Theme.paddingMedium
                visible: page.route && !!page.sim

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

                // The virtual gear, shifted here or with a controller, and what it asks of the legs.
                Row {
                    anchors.horizontalCenter: parent.horizontalCenter
                    spacing: Theme.paddingLarge

                    IconButton {
                        anchors.verticalCenter: parent.verticalCenter
                        enabled: page.rideState !== "Countdown"
                        icon.source: "image://theme/icon-m-remove"
                        onClicked: core.send({ "cmd": "sim.shift", "steps": -1 })
                    }
                    Column {
                        anchors.verticalCenter: parent.verticalCenter
                        width: Theme.itemSizeExtraLarge * 1.6

                        Label {
                            width: parent.width
                            horizontalAlignment: Text.AlignHCenter
                            color: Theme.highlightColor
                            font.pixelSize: Theme.fontSizeLarge
                            text: page.sim ? qsTr("Gear %1 of %2").arg(page.sim.gear).arg(page.sim.gears) : ""
                        }
                        Label {
                            width: parent.width
                            horizontalAlignment: Text.AlignHCenter
                            color: Theme.secondaryHighlightColor
                            font.pixelSize: Theme.fontSizeExtraSmall
                            text: !page.sim ? ""
                                  : (page.sim.gearLabel ? page.sim.gearLabel + " · " : "")
                                    + (page.sim.askedCadence > 0 ? qsTr("asks %1 rpm").arg(page.sim.askedCadence)
                                                                  : qsTr("roll to see rpm"))
                        }
                    }
                    IconButton {
                        anchors.verticalCenter: parent.verticalCenter
                        enabled: page.rideState !== "Countdown"
                        icon.source: "image://theme/icon-m-add"
                        onClicked: core.send({ "cmd": "sim.shift", "steps": 1 })
                    }
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
                        value: page.sim ? Math.round(page.sim.climbedMeters)
                                          + (page.road && page.road.ascentMeters ? " / " + Math.round(page.road.ascentMeters) : "")
                                        : "--"
                        unit: "m"
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

                // The map: close in on the rider, or the whole road; a tap switches.
                RouteMap {
                    id: map

                    anchors.horizontalCenter: parent.horizontalCenter
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    height: Theme.itemSizeHuge * 2
                    points: page.road ? page.road.shape : []
                    fraction: page.sim ? page.sim.fraction : -1
                    follow: true
                    riderLatitude: page.sim && page.sim.latitude !== undefined ? page.sim.latitude : NaN
                    riderLongitude: page.sim && page.sim.longitude !== undefined ? page.sim.longitude : NaN
                    color: Theme.secondaryColor
                    riddenColor: Theme.highlightColor
                    markerColor: Theme.primaryColor
                    lineWidth: Theme.paddingSmall / 2

                    MouseArea {
                        anchors.fill: parent
                        onClicked: map.follow = !map.follow
                    }
                    Label {
                        anchors {
                            left: parent.left
                            top: parent.top
                            margins: Theme.paddingSmall
                        }
                        color: Theme.highlightColor
                        font.pixelSize: Theme.fontSizeTiny
                        text: map.follow ? qsTr("Following you · tap for the whole route")
                                         : qsTr("Whole route · tap to follow you")
                    }
                }
            }

            // The trainer refused or lost the target it was given.
            BackgroundItem {
                width: parent.width
                height: ergLabel.height + 2 * Theme.paddingMedium
                visible: !!trainer.ergError
                onClicked: core.send({ "cmd": "trainer.clearErgError" })

                Label {
                    id: ergLabel

                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    anchors.verticalCenter: parent.verticalCenter
                    wrapMode: Text.Wrap
                    color: Theme.errorColor
                    font.pixelSize: Theme.fontSizeSmall
                    text: qsTr("ERG mode problem: %1 Tap to dismiss.").arg(trainer.ergError || "")
                }
            }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: !trainer.connected && page.started
                wrapMode: Text.Wrap
                color: Theme.errorColor
                font.pixelSize: Theme.fontSizeSmall
                text: qsTr("The trainer is not connected. The ride carries on when it is back and you pedal.")
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

        // The ride is done and saved: what it came to, and its page.
        Column {
            width: parent.width
            spacing: Theme.paddingLarge
            visible: page.rideState === "Completed" && !page.isCompanion

            PageHeader {
                title: qsTr("Ride complete")
                description: page.rideWorkout.name || ""
            }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: page.saved === null || !page.saved.ok
                wrapMode: Text.Wrap
                color: page.saved === null ? Theme.highlightColor : Theme.errorColor
                text: page.saved === null ? qsTr("Saving the ride...") : qsTr("The ride couldn't be saved.")
            }

            Row {
                width: parent.width
                visible: page.saved !== null && page.saved.ok

                Reading {
                    width: parent.width / 2
                    label: qsTr("Time")
                    value: page.saved ? Format.clock(page.saved.rideSeconds) : ""
                    large: true
                }
                Reading {
                    width: parent.width / 2
                    label: qsTr("Average")
                    value: page.saved ? page.saved.averagePower : ""
                    unit: "W"
                    large: true
                }
            }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: page.saved !== null && page.saved.ok
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.Wrap
                color: Theme.secondaryHighlightColor
                text: qsTr("Saved to your history.")
            }

            ButtonLayout {
                Button {
                    visible: page.saved !== null && page.saved.ok
                    text: qsTr("See the ride")
                    onClicked: {
                        var id = String(page.saved.id)
                        core.send({ "cmd": "ride.close" })
                        pageStack.replace(Qt.resolvedUrl("RideDetailPage.qml"), { rideId: id })
                    }
                }
                Button {
                    text: qsTr("Done")
                    onClicked: {
                        core.send({ "cmd": "ride.close" })
                        page.backToRidePage()
                    }
                }
            }
        }

        VerticalScrollDecorator { }
    }
}

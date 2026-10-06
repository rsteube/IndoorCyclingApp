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
            // A companion ride starts when the game says so, not on the pedals.
            if (isCompanion)
                return qsTr("Ready · start the ride in the game")
            return trainer.connected ? qsTr("Pedal to start") : qsTr("Waiting for the trainer")
        case "Paused":
            return qsTr("Paused · trainer at 0 W")
        case "Untracked":
            // What matters first: none of this goes into the ride. Then what the rider is doing,
            // and what they are held at, never a target the trainer is not holding.
            var text = qsTr("Not recording")
            if (ride.untracked)
                text += " · " + ride.untracked
            if (croad.powerReleased)
                text += " · " + qsTr("target let go, pedal to take it back")
            else if (croad.powerTarget > 0)
                text += " · " + qsTr("Target %1 W").arg(croad.powerTarget)
            return text
        default:
            return ""
        }
    }

    // A companion ride: the game on another device describes the road.
    readonly property bool isCompanion: ride.kind === "companion"
    readonly property var companion: core.state.companion || ({})
    readonly property var croad: core.state.companionRoad || ({})
    readonly property string phase: companion.phase || "LIVE"
    readonly property string companionName: companion.lookName || companion.companionName || companion.controllerName
                                             || rideWorkout.name || qsTr("Companion")
    readonly property var controls: companion.controls || null
    // The companion's own ride, saved by the host as each one ends: the rules Android's ride screen
    // keys its companion chrome on.
    readonly property bool hostSavesRide: isCompanion && !!companion.hosting
    readonly property var sessionRides: companion.sessionRides || []
    readonly property bool panelShowing: hostSavesRide && !!companion.companionName && phase !== "LIVE"
    readonly property bool sessionLive: hostSavesRide && !!companion.companionName && phase !== "ENDED"
    readonly property bool rideUnderway: started && rideState !== "Completed" && rideState !== "Idle"
    readonly property color successColor: "#1FB373"

    // "Saved to history" for a while after each ride the host saves, as on Android.
    property bool announcingSaved: false
    property int savedCount: 0
    onSessionRidesChanged: {
        if (sessionRides.length > savedCount) {
            announcingSaved = true
            savedAnnounce.restart()
        }
        savedCount = sessionRides.length
    }
    Timer {
        id: savedAnnounce
        interval: 6000
        onTriggered: page.announcingSaved = false
    }

    // What the header says above the ride, in Android's order: a ride just saved, the screenshot
    // asked for, no companion, a companion gone quiet.
    readonly property var companionNotice: {
        if (announcingSaved) {
            var last = sessionRides.length > 0 ? sessionRides[sessionRides.length - 1].name : ""
            return { "text": last ? qsTr("Saved to history: %1").arg(last) : qsTr("Ride saved to history"),
                     "color": successColor }
        }
        if (panelShowing)
            return null
        if (companion.screenshotMessage)
            return { "text": companion.screenshotMessage,
                     "color": companion.screenshotKind === "SAVED" ? successColor
                              : companion.screenshotKind === "FAILED" ? warningColor : Theme.highlightColor }
        if (!companion.controllerName)
            return { "text": qsTr("No companion connected - flat road"), "color": warningColor }
        if (croad.roadStale)
            return { "text": qsTr("%1 has gone quiet - flattening the road").arg(companionName), "color": warningColor }
        return null
    }

    // Under the companion's name: the course, its world, the rides saved, and where the game is.
    readonly property string companionSubtitle: {
        var parts = []
        var course = croad.courseName || companion.label
        var world = croad.courseWorld || companion.world
        if (course) parts.push(course)
        if (world) parts.push(world)
        if (sessionRides.length === 1) parts.push(qsTr("1 ride saved"))
        else if (sessionRides.length > 1) parts.push(qsTr("%1 rides saved").arg(sessionRides.length))
        if (companion.controllerAddress) parts.push(qsTr("from %1").arg(companion.controllerAddress))
        return parts.join(" · ")
    }

    function controlEnabled(control) {
        return !!controls && !!controls.enabled && controls.enabled.indexOf(control) >= 0
    }

    function backToRidePage() {
        pageStack.pop(pageStack.find(function(p) { return p.objectName === "ridePage" }))
    }

    // Done with a companion session: home, as on Android. Leaving closes the socket.
    function finishCompanion() {
        core.send({ "cmd": "ride.close" })
        backToRidePage()
    }

    // What the last completed ride came to, from the core's ride.saved.
    property var saved: null

    // The moments the screen marks: each second of the countdown, and each new interval.
    readonly property int countdownValue: rideState === "Countdown" ? (ride.countdown || 0) : -1
    readonly property int segmentIndex: ride.segmentIndex || 0
    onCountdownValueChanged: if (countdownValue >= 0) countdownPop.restart()
    onSegmentIndexChanged: if (structured && started && rideState === "Active") intervalFlash.restart()

    // The zone colours, for the confetti.
    readonly property var zoneColors: {
        var colors = []
        for (var i = 0; i < zones.length; ++i)
            colors.push(zones[i].color)
        return colors.length > 0 ? colors : ["#2E7DD7", "#1FB373", "#F0C419", "#F28C28", "#E5484D"]
    }

    // A ride the game left in the middle of: shown in the panel until it is ended.
    readonly property bool inProgress: phase === "COMPANION_AWAY" && rideUnderway

    // "12:30 · 4.2 km · 210 W avg", as Android's session panel lists a ride.
    function rideSummary(seconds, meters, watts) {
        var parts = [Format.clock(seconds)]
        if (meters >= 10)
            parts.push(qsTr("%1 km").arg((meters / 1000).toFixed(1)))
        if (watts > 0)
            parts.push(qsTr("%1 W avg").arg(watts))
        return parts.join(" · ")
    }

    allowedOrientations: Orientation.All

    // Riding, a swipe back must not throw the ride away: the pull-down holds End and save. Before
    // the ride has started, and while it is paused, leaving is allowed, as Android's back is. A
    // companion session that is still on is ended from the pull-down, which is what Android's back
    // does, and its summary stays on this screen.
    backNavigation: isCompanion ? !sessionLive : !(rideState === "Active" && started)

    // Leaving the screen leaves the ride, as on Android: one that never started is gone, one that
    // had (paused, auto-paused) is kept and the Ride page offers it back as unfinished. Leaving a
    // companion ride closes its socket too.
    Component.onDestruction: {
        if (ride.active)
            core.send({ "cmd": "ride.leave" })
        if (isCompanion)
            core.send({ "cmd": "companion.stop" })
    }

    // The screen stays on for the whole ride, the way a ride screen on a stand is used.
    DisplayBlanking { preventBlanking: !!ride.active }

    Connections {
        target: core
        onEventReceived: {
            if (topic === "ride.saved") {
                page.saved = payload
                if (payload && payload.ok)
                    confetti.burst()
            }
        }
    }

    RemorsePopup { id: remorse }

    SilicaFlickable {
        anchors.fill: parent
        contentHeight: Math.max(page.height, content.height + Theme.paddingLarge)

        PullDownMenu {
            visible: !!ride.active || (page.isCompanion && page.sessionLive)

            MenuItem {
                visible: page.hostSavesRide && !!page.companion.controllerName
                text: qsTr("Take back control")
                onClicked: core.send({ "cmd": "companion.revoke" })
            }
            MenuItem {
                visible: page.sessionLive && !page.panelShowing
                text: page.rideUnderway ? qsTr("End ride and session") : qsTr("End session")
                onClicked: remorse.execute(qsTr("Ending the session"), function() {
                    core.send({ "cmd": "companion.endSession" })
                })
            }
            MenuItem {
                visible: page.hostSavesRide && !!page.companion.screenshotsOffered && page.rideUnderway
                enabled: !page.companion.screenshotWaiting
                text: page.companion.screenshotWaiting ? qsTr("Asking for a screenshot") : qsTr("Take a screenshot")
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
                visible: page.canEnd && !page.panelShowing
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
                visible: (page.rideState === "Active" || page.rideState === "Untracked" || page.rideState === "Paused")
                         && !page.panelShowing
                text: page.rideState === "Paused" ? qsTr("Resume") : qsTr("Pause")
                onClicked: core.send({ "cmd": page.rideState === "Paused" ? "ride.resume" : "ride.pause" })
            }
        }

        // A companion ride's header in the game's colours, with a line of its accent under it.
        Rectangle {
            width: parent.width
            height: header.height
            visible: page.hostSavesRide && !!page.companion.header && content.visible
            color: page.companion.header || "transparent"

            Rectangle {
                anchors.bottom: parent.bottom
                width: parent.width
                height: Theme.paddingSmall / 2
                color: page.companion.accent || "transparent"
            }
        }

        Column {
            id: content

            width: parent.width
            spacing: Theme.paddingLarge
            visible: page.isCompanion ? page.phase === "LIVE" : page.rideState !== "Completed"

            PageHeader {
                id: header

                title: page.isCompanion ? page.companionName : (page.rideWorkout.name || qsTr("Ride"))
                description: {
                    if (page.isCompanion)
                        return page.companionSubtitle
                    if (page.structured)
                        return Format.clock(ride.elapsed || 0) + " / " + Format.clock(ride.totalSeconds || 0) + " · "
                               + qsTr("Interval %1 of %2").arg((ride.segmentIndex || 0) + 1).arg(ride.segmentCount || 0)
                    return Format.clock(ride.rideSeconds || 0)
                }
            }

            // The game: its mark, and what it is saying or doing to the road.
            Row {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                spacing: Theme.paddingMedium
                visible: page.hostSavesRide

                // Its icon, or its initial on its accent colour.
                Rectangle {
                    width: Theme.iconSizeMedium
                    height: width
                    radius: Theme.paddingSmall
                    color: page.companion.accent || Theme.rgba(Theme.highlightBackgroundColor, 0.4)

                    Image {
                        anchors.fill: parent
                        visible: !!page.companion.icon
                        source: page.companion.icon ? "file://" + page.companion.icon : ""
                        sourceSize.width: width
                        sourceSize.height: height
                    }
                    Label {
                        anchors.centerIn: parent
                        visible: !page.companion.icon
                        color: page.companion.onAccent || Theme.primaryColor
                        font.pixelSize: Theme.fontSizeLarge
                        text: page.companionName.charAt(0).toUpperCase()
                    }
                }
                Label {
                    width: parent.width - Theme.iconSizeMedium - Theme.paddingMedium
                    anchors.verticalCenter: parent.verticalCenter
                    wrapMode: Text.Wrap
                    color: page.companionNotice ? page.companionNotice.color : Theme.secondaryHighlightColor
                    font.pixelSize: Theme.fontSizeSmall
                    text: page.companionNotice ? page.companionNotice.text
                                               : (page.companion.controllerName ? qsTr("%1 is driving the ride").arg(page.companion.controllerName) : "")
                }
            }

            // Where the ride is when it is not simply riding: waiting, counting down, paused.
            Column {
                width: parent.width
                spacing: Theme.paddingMedium
                visible: page.stateText.length > 0

                Label {
                    id: stateLabel

                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.Wrap
                    color: page.rideState === "Paused" || !!ride.canEndAndSave || page.rideState === "Untracked"
                           ? page.warningColor : Theme.highlightColor
                    font.pixelSize: page.rideState === "Countdown" ? Theme.fontSizeHuge * 2 : Theme.fontSizeLarge
                    text: page.stateText

                    ParallelAnimation {
                        id: countdownPop

                        NumberAnimation { target: stateLabel; property: "scale"; from: 1.7; to: 1; duration: 520; easing.type: Easing.OutBack }
                        NumberAnimation { target: stateLabel; property: "opacity"; from: 0; to: 1; duration: 260 }
                    }
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

            // Power, in its zone's colour, against what the ride asks for. On a workout it sits in a
            // ring of how far through the interval the rider is, which glows while the trainer
            // holds the target.
            Item {
                id: powerHero

                anchors.horizontalCenter: parent.horizontalCenter
                width: page.structured
                       ? Math.min(parent.width - 2 * Theme.horizontalPageMargin, Theme.itemSizeHuge * (page.isLandscape ? 2 : 2.8))
                       : parent.width
                height: page.structured ? width : powerColumn.height

                Rectangle {
                    id: glow

                    anchors.centerIn: parent
                    visible: page.structured
                    width: parent.width * 0.84
                    height: width
                    radius: width / 2
                    color: page.powerColor
                    opacity: page.onTarget ? 0.14 : 0

                    Behavior on opacity { FadeAnimator { duration: 400 } }

                    SequentialAnimation on scale {
                        running: page.onTarget && Qt.application.active
                        loops: Animation.Infinite
                        NumberAnimation { from: 0.96; to: 1.02; duration: 1400; easing.type: Easing.InOutSine }
                        NumberAnimation { from: 1.02; to: 0.96; duration: 1400; easing.type: Easing.InOutSine }
                    }
                }

                ProgressRing {
                    anchors.fill: parent
                    visible: page.structured
                    value: page.segment.seconds > 0 ? 1 - page.segment.remaining / page.segment.seconds : 0
                    color: page.powerColor
                    trackColor: Theme.rgba(page.powerColor, 0.15)
                    lineWidth: Theme.paddingMedium * (page.onTarget ? 1.5 : 1)
                    // Remaining time moves a second at a time: sweep through each second evenly.
                    duration: 1000
                    easing: Easing.Linear

                    Behavior on lineWidth { NumberAnimation { duration: 300 } }
                }

            Column {
                id: powerColumn

                anchors.centerIn: parent
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
                AnimatedNumber {
                    anchors.horizontalCenter: parent.horizontalCenter
                    value: page.power
                    available: !!trainer.connected
                    color: page.powerColor
                    font.pixelSize: Theme.fontSizeHuge * (page.isLandscape ? 1.6 : 2.2)
                    font.family: Theme.fontFamilyHeading

                    Behavior on color { ColorAnimation { duration: 400 } }
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

            // A companion ride: the game draws the road on its own big screen, so the phone shows the
            // gear and what the legs make. Every reading for someone building a companion.
            Row {
                width: parent.width
                visible: page.isCompanion

                Reading {
                    width: parent.width / (page.companion.showAllData ? 3 : 1)
                    visible: !!page.companion.showAllData
                    label: qsTr("Gradient")
                    value: (page.croad.gradientText || "0") + " %"
                    valueColor: Gradient.colors[page.croad.band] || Theme.highlightColor
                }
                Reading {
                    width: parent.width / (page.companion.showAllData ? 3 : 1)
                    label: qsTr("Gear")
                    value: page.croad.gear || "--"
                    large: !page.companion.showAllData
                }
                Reading {
                    width: parent.width / 3
                    visible: !!page.companion.showAllData
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
                    label: page.heartRateZone ? qsTr("Heart rate · Z%1").arg(page.heartRateZone.number) : qsTr("Heart rate")
                    numeric: true
                    number: page.heartRate
                    available: page.heartRate > 0
                    beat: page.heartRate
                    valueColor: page.heartRateZone ? page.heartRateZone.color : Theme.primaryColor
                    unit: "bpm"
                }
                Reading {
                    width: parent.width / 2
                    label: qsTr("Speed")
                    value: (page.croad.speedKmh || 0).toFixed(1)
                    unit: "km/h"
                }
                Reading {
                    width: parent.width / 2
                    label: qsTr("Distance")
                    value: ((page.croad.rideDistanceMeters || 0) / 1000).toFixed(2)
                    unit: "km"
                }
                Reading {
                    width: parent.width / 2
                    label: qsTr("Climbed")
                    value: Math.round(page.croad.climbedMeters || 0)
                    unit: "m"
                }
            }

            // The paddles: one gear easier or harder, big enough to hit while riding.
            Row {
                anchors.horizontalCenter: parent.horizontalCenter
                spacing: Theme.paddingLarge
                visible: page.isCompanion

                Repeater {
                    model: [{ "steps": -1, "label": qsTr("Easier"), "icon": "image://theme/icon-m-remove" },
                            { "steps": 1, "label": qsTr("Harder"), "icon": "image://theme/icon-m-add" }]

                    // One gear, on the touch rather than its release, as a paddle under a finger on
                    // the bars should be. Dead at the end of the ladder and through the countdown.
                    BackgroundItem {
                        readonly property bool atEnd: modelData.steps < 0 ? (page.croad.gearIndex || 0) <= 0
                                                      : (page.croad.gearIndex || 0) >= (page.croad.gears || 1) - 1

                        width: (page.width - 2 * Theme.horizontalPageMargin - Theme.paddingLarge) / 2
                        height: Theme.itemSizeHuge
                        enabled: page.rideState !== "Countdown" && !atEnd
                        opacity: enabled ? 1 : 0.4
                        onPressed: core.send({ "cmd": "companion.shift", "steps": modelData.steps })

                        Rectangle {
                            anchors.fill: parent
                            radius: Theme.paddingMedium
                            color: parent.down ? (page.companion.accent || Theme.highlightBackgroundColor)
                                               : Theme.rgba(Theme.highlightBackgroundColor, 0.25)
                        }
                        Column {
                            anchors.centerIn: parent
                            Icon {
                                anchors.horizontalCenter: parent.horizontalCenter
                                source: modelData.icon
                            }
                            Label {
                                anchors.horizontalCenter: parent.horizontalCenter
                                color: parent.parent.down && page.companion.onAccent ? page.companion.onAccent : Theme.highlightColor
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
                // The game's menu as a cross, with Back across the bottom, as on Android. A button
                // the game did not enable stays where it is, dead, so the pad never moves under a
                // thumb.
                Grid {
                    anchors.horizontalCenter: parent.horizontalCenter
                    columns: 3

                    Item { width: Theme.itemSizeMedium; height: width }
                    PadButton { control: "up"; icon: "image://theme/icon-m-up" }
                    Item { width: Theme.itemSizeMedium; height: width }
                    PadButton { control: "left"; icon: "image://theme/icon-m-left" }
                    PadButton { control: "enter"; icon: "image://theme/icon-m-acknowledge" }
                    PadButton { control: "right"; icon: "image://theme/icon-m-right" }
                    Item { width: Theme.itemSizeMedium; height: width }
                    PadButton { control: "down"; icon: "image://theme/icon-m-down" }
                    Item { width: Theme.itemSizeMedium; height: width }
                }
                PadButton {
                    anchors.horizontalCenter: parent.horizontalCenter
                    width: Theme.itemSizeMedium * 3
                    control: "back"
                    icon: "image://theme/icon-m-back"
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
                    numeric: true
                    number: page.cadence
                    available: !!trainer.connected
                    valueColor: page.cadenceOff ? page.warningColor : Theme.primaryColor
                    unit: "rpm"
                }
                Reading {
                    width: parent.width / 3
                    label: page.heartRateZone ? qsTr("Heart rate · Z%1").arg(page.heartRateZone.number) : qsTr("Heart rate")
                    numeric: true
                    number: page.heartRate
                    available: page.heartRate > 0
                    beat: page.heartRate
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
                    text: qsTr("%1 · %2 % FTP").arg(page.segment.type || "").arg(page.segment.range || page.segment.pct || 0)
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

            // The game's accent across the top, as Android's panel has it.
            Rectangle {
                width: parent.width
                height: Theme.paddingSmall
                color: page.companion.accent || "transparent"
            }

            PageHeader {
                title: page.phase === "ENDED" ? qsTr("Session ended")
                       : page.inProgress ? qsTr("Ride paused")
                       : qsTr("%1 disconnected").arg(page.companionName)
            }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                wrapMode: Text.Wrap
                color: page.phase === "ENDED" ? Theme.highlightColor : page.warningColor
                text: page.phase === "ENDED"
                      ? (page.sessionRides.length === 0 ? qsTr("No rides were recorded. A ride is kept once the game has started one.")
                                                        : qsTr("Every ride below is saved in your history."))
                      : page.inProgress
                        ? qsTr("%1 disconnected before the ride finished. The trainer is off and nothing is being recorded. When it reconnects the ride carries on by itself; if it is not coming back, end the ride to save it.").arg(page.companionName)
                        : qsTr("Waiting for it to reconnect.")
            }

            SectionHeader {
                visible: page.sessionRides.length > 0 || page.inProgress
                text: qsTr("Rides this session")
            }
            Repeater {
                model: page.sessionRides

                SessionRideItem {
                    name: modelData.name
                    detail: page.rideSummary(modelData.durationSeconds, modelData.distanceMeters, modelData.averagePower)
                    screenshots: modelData.screenshots || []
                }
            }
            SessionRideItem {
                visible: page.inProgress
                name: page.croad.courseName || page.rideWorkout.name || qsTr("Companion ride")
                detail: page.rideSummary(ride.rideSeconds || 0, page.croad.rideDistanceMeters || 0, 0)
                note: qsTr("Paused. Saved as a completed ride when you end it.")
                screenshots: page.companion.rideScreenshots || []
            }

            Button {
                anchors.horizontalCenter: parent.horizontalCenter
                visible: page.phase === "ENDED"
                text: qsTr("Done")
                onClicked: page.finishCompanion()
            }
            Button {
                anchors.horizontalCenter: parent.horizontalCenter
                visible: page.phase !== "ENDED"
                // With the game gone, ending is the only thing to do, and it keeps the ride.
                text: page.inProgress ? qsTr("End ride") : qsTr("End session")
                onClicked: core.send({ "cmd": "companion.endSession" })
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

            IconTile {
                id: trophy

                anchors.horizontalCenter: parent.horizontalCenter
                width: Theme.itemSizeHuge
                name: "emoji_events"
                tint: "#F0C419"
                scale: page.saved !== null && page.saved.ok ? 1 : 0.4
                opacity: page.saved !== null && page.saved.ok ? 1 : 0

                Behavior on scale { NumberAnimation { duration: 600; easing.type: Easing.OutBack } }
                Behavior on opacity { FadeAnimator { duration: 300 } }
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
                    numeric: true
                    number: page.saved ? page.saved.averagePower : 0
                    duration: 1200
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

    // A new interval: the screen washes once in its zone's colour, so the change is felt.
    Rectangle {
        id: flash

        anchors.fill: parent
        color: page.powerColor
        opacity: 0
        enabled: false

        NumberAnimation {
            id: intervalFlash

            target: flash
            property: "opacity"
            from: 0.28
            to: 0
            duration: 900
            easing.type: Easing.OutQuad
        }
    }

    Confetti {
        id: confetti

        colors: page.zoneColors
    }
}

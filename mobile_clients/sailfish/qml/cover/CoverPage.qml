import QtQuick 2.6
import Sailfish.Silica 1.0
import "../components"
import "../components/Format.js" as Format

// On the home screen: the ride while there is one (power against its target, ringed on a workout by
// the interval, heart rate, the time that matters) with pause or resume as the cover's action;
// otherwise the trainer.
CoverBackground {
    id: cover

    readonly property var trainer: core.state.trainer || ({})
    readonly property var hrm: core.state.hrm || ({})
    readonly property var ride: core.state.ride || ({})
    readonly property var segment: ride.segment || ({})
    readonly property var zones: core.state.zones || []
    readonly property bool riding: !!ride.active
    readonly property bool workout: riding && ride.kind === "workout"
    readonly property int heartRate: hrm.connected && hrm.heartRate > 0 ? hrm.heartRate : (trainer.heartRate || 0)
    readonly property color zoneColor: workout ? Format.zoneColor(zones, segment.zone || 1, Theme.highlightColor) : Theme.highlightColor

    CoverPlaceholder {
        visible: !cover.riding && !trainer.connected
        text: qsTr("IndoorBike")
        icon.source: "/usr/share/icons/hicolor/86x86/apps/harbour-indoorbike.png"
    }

    Column {
        visible: cover.riding || !!trainer.connected
        anchors {
            left: parent.left
            right: parent.right
            margins: Theme.paddingLarge
            verticalCenter: parent.verticalCenter
            verticalCenterOffset: cover.riding ? -Theme.paddingLarge : 0
        }
        spacing: Theme.paddingSmall

        Item {
            anchors.horizontalCenter: parent.horizontalCenter
            width: Math.min(parent.width, Theme.coverSizeLarge.width * 0.62)
            height: cover.workout ? width : powerColumn.height

            ProgressRing {
                anchors.fill: parent
                visible: cover.workout
                value: cover.segment.seconds > 0 ? 1 - cover.segment.remaining / cover.segment.seconds : 0
                color: cover.zoneColor
                trackColor: Theme.rgba(cover.zoneColor, 0.2)
                lineWidth: Theme.paddingSmall * 1.5
                duration: 1000
                easing: Easing.Linear
            }

            Column {
                id: powerColumn

                anchors.centerIn: parent

                Label {
                    anchors.horizontalCenter: parent.horizontalCenter
                    text: trainer.connected ? (trainer.power || 0) : "--"
                    color: cover.workout ? cover.zoneColor : Theme.primaryColor
                    font.pixelSize: Theme.fontSizeHuge
                }
                Label {
                    anchors.horizontalCenter: parent.horizontalCenter
                    color: Theme.secondaryColor
                    font.pixelSize: Theme.fontSizeExtraSmall
                    text: cover.segment.targetWatts > 0 ? qsTr("of %1 W").arg(cover.segment.targetWatts) : qsTr("watts")
                }
            }
        }
        Row {
            anchors.horizontalCenter: parent.horizontalCenter
            visible: cover.heartRate > 0
            spacing: Theme.paddingSmall

            HeartBeat {
                anchors.verticalCenter: parent.verticalCenter
                width: Theme.iconSizeExtraSmall
                bpm: cover.heartRate
                color: "#E5484D"
            }
            Label {
                color: Theme.highlightColor
                text: qsTr("%1 bpm").arg(cover.heartRate)
            }
        }
        Label {
            anchors.horizontalCenter: parent.horizontalCenter
            visible: cover.riding
            color: Theme.highlightColor
            text: ride.state === "Paused" ? qsTr("Paused")
                  : ride.state === "Countdown" ? (ride.countdown > 0 ? String(ride.countdown) : qsTr("Go!"))
                  : ride.state === "Standby" ? (ride.canEndAndSave ? qsTr("Auto-paused") : qsTr("Pedal to start"))
                  : ride.kind === "workout" ? Format.clock(cover.segment.remaining)
                  : Format.clock(ride.rideSeconds)
        }
    }

    CoverActionList {
        enabled: cover.riding && (ride.state === "Active" || ride.state === "Paused")

        CoverAction {
            iconSource: ride.state === "Paused" ? "image://theme/icon-cover-play" : "image://theme/icon-cover-pause"
            onTriggered: core.send({ "cmd": ride.state === "Paused" ? "ride.resume" : "ride.pause" })
        }
    }
}

import QtQuick 2.6
import Sailfish.Silica 1.0
import "../components/Format.js" as Format

// On the home screen: the ride while there is one (power against its target, heart rate, the
// interval's time left) with pause or resume as the cover's action; otherwise the trainer.
CoverBackground {
    id: cover

    readonly property var trainer: core.state.trainer || ({})
    readonly property var hrm: core.state.hrm || ({})
    readonly property var ride: core.state.ride || ({})
    readonly property var segment: ride.segment || ({})
    readonly property bool riding: !!ride.active
    readonly property int heartRate: hrm.connected && hrm.heartRate > 0 ? hrm.heartRate : (trainer.heartRate || 0)

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

        Label {
            anchors.horizontalCenter: parent.horizontalCenter
            text: trainer.connected ? (trainer.power || 0) : "--"
            font.pixelSize: Theme.fontSizeHuge
        }
        Label {
            anchors.horizontalCenter: parent.horizontalCenter
            color: Theme.secondaryColor
            font.pixelSize: Theme.fontSizeSmall
            text: cover.segment.targetWatts > 0 ? qsTr("of %1 W").arg(cover.segment.targetWatts) : qsTr("watts")
        }
        Label {
            anchors.horizontalCenter: parent.horizontalCenter
            visible: cover.heartRate > 0
            color: Theme.highlightColor
            text: qsTr("%1 bpm").arg(cover.heartRate)
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

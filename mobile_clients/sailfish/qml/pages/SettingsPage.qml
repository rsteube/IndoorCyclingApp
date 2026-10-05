import QtQuick 2.6
import Sailfish.Silica 1.0
import "../components"
import "../components/Accent.js" as Accent

// The rider, their devices and their bike, where the rides go, and the way to tell us something is
// wrong. Every line is one row with an icon and how it stands; the detail is a tap further in.
Page {
    id: page

    readonly property var settings: core.state.settings || ({})
    readonly property var limits: settings.limits || ({})
    readonly property var connections: core.state.connections || ({})
    readonly property var sync: core.state.sync || ({})
    readonly property real ftp: settings.ftp || 200
    readonly property real weightKg: settings.weightKg || 75

    readonly property string drivetrainName: {
        var all = settings.drivetrains || []
        for (var i = 0; i < all.length; ++i) {
            if (all[i].id === settings.drivetrainId)
                return all[i].name
        }
        return all.length > 0 ? all[0].name : ""
    }

    readonly property var connected: {
        var names = []
        if (connections.intervals && connections.intervals.configured)
            names.push("Intervals.icu")
        if (connections.fitTrackee && connections.fitTrackee.connected)
            names.push("FitTrackee")
        return names
    }

    readonly property string syncState: {
        if (!sync.folder)
            return "off"
        var kind = sync.status ? sync.status.kind : ""
        if (kind === "failed")
            return "bad"
        if (kind === "needsReview" || sync.paused)
            return "paused"
        return kind === "syncing" ? "busy" : "ok"
    }

    function editRider(field) {
        pageStack.push(Qt.resolvedUrl("RiderDialog.qml"), { focusField: field })
    }

    allowedOrientations: Orientation.All

    SilicaFlickable {
        anchors.fill: parent
        contentHeight: column.height + Theme.paddingLarge

        Column {
            id: column

            width: parent.width

            PageHeader { title: qsTr("Settings") }

            // The three numbers everything is worked out from, big enough to check at a glance.
            SectionHeader { text: qsTr("You") }

            Row {
                x: Theme.horizontalPageMargin - Theme.paddingSmall / 2
                width: parent.width - 2 * x

                StatTile {
                    width: parent.width / 3
                    icon: "bolt"
                    tint: Accent.power
                    value: page.ftp
                    unit: "W"
                    label: qsTr("FTP")
                    onClicked: page.editRider("ftp")
                }
                StatTile {
                    width: parent.width / 3
                    icon: "monitor_weight"
                    tint: Accent.sky
                    value: page.weightKg
                    decimals: 1
                    unit: "kg"
                    label: qsTr("Weight")
                    onClicked: page.editRider("weightKg")
                }
                StatTile {
                    width: parent.width / 3
                    icon: "favorite"
                    tint: Accent.heart
                    value: page.settings.maxHr || 185
                    unit: "bpm"
                    label: qsTr("Max heart rate")
                    onClicked: page.editRider("maxHr")
                }
            }

            Item { width: 1; height: Theme.paddingMedium }

            Note {
                text: qsTr("%1 W/kg. Tap a number to change it.").arg((page.ftp / page.weightKg).toFixed(2))
            }

            SectionHeader { text: qsTr("Devices") }

            DeviceSetting {
                kind: "trainer"
                pairTitle: qsTr("Pair a trainer")
                pairHint: qsTr("Wake the trainer by turning the pedals. Close other apps that are connected to it.")
            }
            DeviceSetting {
                kind: "hrm"
                pairTitle: qsTr("Pair a heart rate monitor")
                pairHint: qsTr("Put the strap on, or wet its contacts, so it starts sending.")
            }
            DeviceSetting {
                kind: "controller"
                pairTitle: qsTr("Pair a controller")
                pairHint: qsTr("Press a button on the controller so it starts advertising.")
            }

            SectionHeader { text: qsTr("Bike") }

            SettingRow {
                icon: "scale"
                tint: Accent.sky
                title: qsTr("Bike weight")
                subtitle: qsTr("Adds to yours on a route's climbs.")
                value: qsTr("%1 kg").arg((page.settings.bikeWeightKg || 9).toFixed(1))
                navigates: true
                onClicked: pageStack.push(Qt.resolvedUrl("NumberDialog.qml"), {
                    title: qsTr("Bike weight"),
                    key: "bikeWeightKg",
                    label: qsTr("Bike weight in kg"),
                    value: page.settings.bikeWeightKg || 9,
                    limits: page.limits.bikeWeightKg || [4, 30],
                    decimals: 1
                })
            }
            SettingRow {
                id: gearing

                icon: "settings"
                tint: Accent.green
                title: qsTr("Gearing")
                subtitle: qsTr("The virtual gears a route shifts through.")
                value: page.drivetrainName
                onClicked: openMenu()

                menu: ContextMenu {
                    Repeater {
                        model: page.settings.drivetrains || []
                        MenuItem {
                            text: modelData.name
                            font.bold: modelData.id === page.settings.drivetrainId
                            onClicked: core.send({ "cmd": "settings.set", "key": "drivetrainId", "value": modelData.id })
                        }
                    }
                }
            }

            SectionHeader { text: qsTr("Your rides elsewhere") }

            SettingRow {
                icon: "cloud_upload"
                tint: Accent.sky
                muted: page.connected.length === 0
                title: qsTr("Connections")
                subtitle: page.connected.length > 0 ? page.connected.join(" · ")
                                                    : qsTr("Upload rides to Intervals.icu or FitTrackee")
                status: page.connected.length > 0 ? "ok" : ""
                navigates: true
                onClicked: pageStack.push(Qt.resolvedUrl("ConnectionsPage.qml"))
            }
            SettingRow {
                icon: page.syncState === "bad" ? "sync_problem" : "sync"
                tint: Accent.green
                muted: page.syncState === "off"
                title: qsTr("Backup and sync")
                subtitle: page.syncState === "off" ? qsTr("Keep your rides in a folder you already back up")
                          : page.syncState === "bad" ? qsTr("Couldn't sync")
                          : page.syncState === "paused" ? qsTr("Paused: needs your decision")
                          : page.syncState === "busy" ? qsTr("Syncing")
                          : qsTr("On")
                status: page.syncState === "off" ? "" : page.syncState === "paused" ? "bad" : page.syncState
                navigates: true
                onClicked: pageStack.push(Qt.resolvedUrl("SyncPage.qml"))
            }

            SectionHeader { text: qsTr("About") }

            SettingRow {
                icon: "bug_report"
                tint: Accent.warning
                title: qsTr("Report a problem")
                subtitle: qsTr("Tell us what went wrong, with the app's log attached")
                navigates: true
                onClicked: pageStack.push(Qt.resolvedUrl("ReportPage.qml"))
            }
            SettingRow {
                icon: "info"
                title: qsTr("IndoorBike")
                subtitle: qsTr("Free software under the GNU GPL, version 3. Your rides stay on this phone unless you send them somewhere.")
                value: appVersion
                onClicked: Qt.openUrlExternally("https://indoorbike.app")
            }
        }

        VerticalScrollDecorator { }
    }
}

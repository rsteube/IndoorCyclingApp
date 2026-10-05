import QtQuick 2.6
import Sailfish.Silica 1.0
import Sailfish.Pickers 1.0
import "../components"
import "../components/Accent.js" as Accent

// Backup and sync: one small file per device in a folder the rider already trusts, the same files the
// Android and iOS apps write, so a folder Nextcloud or Syncthing keeps in step joins them all. How it
// stands first; the how and why fold away.
Page {
    id: page

    readonly property var sync: core.state.sync || ({})
    readonly property var syncStatus: sync.status || ({ "kind": "idle" })
    readonly property bool reviewing: syncStatus.kind === "needsReview" || !!sync.paused

    function relative(epochMs) {
        var seconds = Math.max(0, Math.floor((Date.now() - epochMs) / 1000))
        if (seconds < 5) return qsTr("just now")
        if (seconds < 60) return qsTr("%1 s ago").arg(seconds)
        if (seconds < 3600) return qsTr("%1 min ago").arg(Math.floor(seconds / 60))
        if (seconds < 86400) return qsTr("%1 h ago").arg(Math.floor(seconds / 3600))
        return qsTr("%1 d ago").arg(Math.floor(seconds / 86400))
    }

    function statusTitle() {
        if (!sync.folder)
            return qsTr("Off")
        switch (syncStatus.kind) {
        case "syncing":
            return qsTr("Syncing")
        case "success":
            return qsTr("Synced %1").arg(relative(syncStatus.syncedAt))
        case "failed":
            return qsTr("Couldn't sync")
        case "needsReview":
            return qsTr("Paused")
        default:
            return sync.paused ? qsTr("Paused") : qsTr("Not synced yet")
        }
    }

    function statusDetail() {
        if (!sync.folder)
            return qsTr("Choose a folder to start")
        switch (syncStatus.kind) {
        case "success":
            var devices = syncStatus.devicesSeen === 1 ? qsTr("1 device in the folder") : qsTr("%1 devices in the folder").arg(syncStatus.devicesSeen)
            return devices + (syncStatus.slicesSkipped > 0 ? " · " + qsTr("%1 unreadable").arg(syncStatus.slicesSkipped) : "")
        case "failed":
            return syncStatus.message || ""
        case "needsReview":
            return qsTr("The data here looks reset. Choose below what to keep.")
        default:
            return sync.paused ? qsTr("The data here looks reset. Choose below what to keep.") : ""
        }
    }

    allowedOrientations: Orientation.All

    // "Synced 2 min ago" moves on by itself while the page is open.
    Timer {
        id: tick

        property int n
        interval: 30000
        repeat: true
        running: page.status === PageStatus.Active
        onTriggered: n += 1
    }

    Component {
        id: folderPicker

        FolderPickerDialog {
            title: qsTr("Sync folder")
            onAccepted: core.send({ "cmd": "sync.setFolder", "path": selectedPath })
        }
    }

    SilicaFlickable {
        anchors.fill: parent
        contentHeight: column.height + Theme.paddingLarge

        PullDownMenu {
            visible: !!page.sync.folder && !page.reviewing
            MenuItem {
                text: qsTr("Stop syncing")
                onClicked: Remorse.popupAction(page, qsTr("Stopped syncing"), function() {
                    core.send({ "cmd": "sync.setFolder", "path": "" })
                })
            }
            MenuItem {
                text: qsTr("Restore from backup")
                onClicked: core.send({ "cmd": "sync.restore" })
            }
            MenuItem {
                enabled: page.syncStatus.kind !== "syncing"
                text: qsTr("Sync now")
                onClicked: core.send({ "cmd": "sync.now" })
            }
        }

        Column {
            id: column

            width: parent.width
            spacing: Theme.paddingMedium

            PageHeader {
                title: qsTr("Backup and sync")
                description: qsTr("Your rides, in a folder you trust")
            }

            SettingRow {
                enabled: false
                icon: page.syncStatus.kind === "failed" ? "sync_problem" : page.sync.folder ? "sync" : "cloud_off"
                tint: page.syncStatus.kind === "failed" || page.reviewing ? Theme.errorColor : Accent.green
                muted: !page.sync.folder
                spinning: page.syncStatus.kind === "syncing"
                title: tick.n >= 0 ? page.statusTitle() : ""
                subtitle: tick.n >= 0 ? page.statusDetail() : ""
                status: !page.sync.folder ? ""
                        : page.syncStatus.kind === "failed" || page.reviewing ? "bad"
                        : page.syncStatus.kind === "syncing" ? "busy" : "ok"
            }

            SettingRow {
                icon: "folder"
                tint: Accent.sky
                title: page.sync.folder ? page.sync.folder.replace(StandardPaths.home + "/", "") : qsTr("Choose a folder")
                subtitle: page.sync.folder ? qsTr("Tap to choose another") : qsTr("In Documents or Downloads")
                navigates: true
                onClicked: pageStack.push(folderPicker, { path: page.sync.folder || StandardPaths.documents })
            }

            TextField {
                width: parent.width
                visible: !!page.sync.folder
                label: qsTr("This device's name in the backup")
                placeholderText: label
                text: (core.state.settings || {}).deviceLabel || ""
                onActiveFocusChanged: {
                    if (!activeFocus && text.trim().length > 0)
                        core.send({ "cmd": "settings.set", "key": "deviceLabel", "value": text.trim() })
                }
                EnterKey.iconSource: "image://theme/icon-m-enter-close"
                EnterKey.onClicked: focus = false
            }

            // The data here and the backup disagree too much to sync without asking.
            Column {
                width: parent.width
                visible: !!page.sync.folder && page.reviewing
                spacing: Theme.paddingMedium

                SectionHeader { text: qsTr("Choose what to keep") }

                Note {
                    emphasised: true
                    text: page.syncStatus.kind === "needsReview"
                          ? qsTr("This device has %1 rides and %2 workouts; the backup has %3 rides and %4 workouts. They disagree about too many at once, which usually means one device's data was reset. Backup is paused so nothing is deleted by mistake.")
                            .arg(page.syncStatus.localSessions).arg(page.syncStatus.localImports)
                            .arg(page.syncStatus.backupSessions).arg(page.syncStatus.backupImports)
                          : qsTr("This device and the backup disagree about too many rides at once. Backup is paused so nothing is deleted by mistake.")
                }

                SettingRow {
                    icon: "cloud_done"
                    tint: Accent.green
                    title: qsTr("Restore from backup")
                    subtitle: qsTr("Recommended if this device's data was lost by accident. This device comes to match the backup.")
                    onClicked: core.send({ "cmd": "sync.restore" })
                }
                SettingRow {
                    icon: "cloud_upload"
                    tint: Accent.warning
                    title: qsTr("Keep this device's data")
                    subtitle: qsTr("The backup comes to match this device. Rides this device doesn't have are removed from the backup, and from your other devices when they next sync.")
                    onClicked: Remorse.popupAction(page, qsTr("Keeping this device's data"), function() {
                        core.send({ "cmd": "sync.keepLocal" })
                    })
                }
            }

            Expander {
                title: qsTr("How backup works")
                // Open until there is a folder: before then it is the page's whole point.
                expanded: !page.sync.folder

                Note {
                    text: qsTr("Pick a folder in Documents or Downloads, such as one Nextcloud or Syncthing keeps in step with your other devices. Each device writes one small file into it, and every device pointed at the same folder shares your rides, workouts and settings. No accounts, no servers: just a file in a folder you already trust.")
                }
                Note {
                    text: qsTr("Stop syncing, in the pull-down menu, disconnects this device from the folder. The rides, settings and workouts here stay, and nothing in the folder is deleted.")
                }
            }
        }

        VerticalScrollDecorator { }
    }
}

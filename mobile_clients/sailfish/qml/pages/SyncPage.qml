import QtQuick 2.6
import Sailfish.Silica 1.0
import Sailfish.Pickers 1.0

// Backup and sync: one small file per device in a folder the rider already trusts, the same files the
// Android and iOS apps write, so a folder Nextcloud or Syncthing keeps in step joins them all.
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

    function statusLine() {
        switch (syncStatus.kind) {
        case "syncing":
            return qsTr("Syncing")
        case "success":
            var devices = syncStatus.devicesSeen === 1 ? qsTr("1 device seen") : qsTr("%1 devices seen").arg(syncStatus.devicesSeen)
            var skipped = syncStatus.slicesSkipped > 0 ? " · " + qsTr("%1 unreadable").arg(syncStatus.slicesSkipped) : ""
            return qsTr("Synced %1").arg(relative(syncStatus.syncedAt)) + " · " + devices + skipped
        case "failed":
            return qsTr("Couldn't sync: %1").arg(syncStatus.message)
        case "needsReview":
            return qsTr("Paused: the data here looks reset. See below.")
        case "notConfigured":
            return qsTr("No folder chosen")
        default:
            return sync.paused ? qsTr("Paused: the data here looks reset. See below.")
                               : qsTr("Not synced yet")
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

            PageHeader { title: qsTr("Backup and sync") }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                wrapMode: Text.Wrap
                color: Theme.secondaryHighlightColor
                font.pixelSize: Theme.fontSizeSmall
                text: qsTr("Pick a folder in Documents or Downloads, such as one Nextcloud or Syncthing keeps in step with your other devices. Each device writes one small file into it, and every device pointed at the same folder shares your rides, workouts and settings. No accounts, no servers: just a file in a folder you already trust.")
            }

            ValueButton {
                label: qsTr("Folder")
                value: page.sync.folder ? page.sync.folder.replace(StandardPaths.home + "/", "") : qsTr("None")
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

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: !!page.sync.folder
                wrapMode: Text.Wrap
                color: page.syncStatus.kind === "failed" ? Theme.errorColor : Theme.highlightColor
                font.pixelSize: Theme.fontSizeSmall
                text: tick.n >= 0 ? page.statusLine() : ""
            }

            Column {
                width: parent.width
                visible: !!page.sync.folder && page.reviewing
                spacing: Theme.paddingMedium

                SectionHeader { text: qsTr("Backup paused") }

                Label {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    wrapMode: Text.Wrap
                    color: Theme.highlightColor
                    font.pixelSize: Theme.fontSizeSmall
                    text: page.syncStatus.kind === "needsReview"
                          ? qsTr("This device has %1 rides and %2 workouts; the backup has %3 rides and %4 workouts. They disagree about too many at once, which usually means one device's data was reset. Backup is paused so nothing is deleted by mistake. Choose what to do:")
                            .arg(page.syncStatus.localSessions).arg(page.syncStatus.localImports)
                            .arg(page.syncStatus.backupSessions).arg(page.syncStatus.backupImports)
                          : qsTr("This device and the backup disagree about too many rides at once. Backup is paused so nothing is deleted by mistake. Choose what to do:")
                }
                Button {
                    anchors.horizontalCenter: parent.horizontalCenter
                    text: qsTr("Restore from backup")
                    onClicked: core.send({ "cmd": "sync.restore" })
                }
                Label {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    wrapMode: Text.Wrap
                    color: Theme.secondaryHighlightColor
                    font.pixelSize: Theme.fontSizeExtraSmall
                    text: qsTr("Makes this device match the backup: rides, workouts and settings missing here come back, and anything the backup has removed is removed here too. Recommended if this device's data was lost by accident.")
                }
                Button {
                    anchors.horizontalCenter: parent.horizontalCenter
                    text: qsTr("Keep this device's data")
                    onClicked: Remorse.popupAction(page, qsTr("Keeping this device's data"), function() {
                        core.send({ "cmd": "sync.keepLocal" })
                    })
                }
                Label {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    wrapMode: Text.Wrap
                    color: Theme.secondaryHighlightColor
                    font.pixelSize: Theme.fontSizeExtraSmall
                    text: qsTr("Keeps every ride and workout on this device as it is and updates the backup to match. Rides this device doesn't have are removed from the backup, and from your other devices when they next sync. Use only if that is what you want.")
                }
            }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: !!page.sync.folder
                wrapMode: Text.Wrap
                color: Theme.secondaryHighlightColor
                font.pixelSize: Theme.fontSizeExtraSmall
                text: qsTr("Stop syncing disconnects this device from the folder. The rides, settings and workouts here stay, and nothing in the folder is deleted.")
            }
        }

        VerticalScrollDecorator { }
    }
}

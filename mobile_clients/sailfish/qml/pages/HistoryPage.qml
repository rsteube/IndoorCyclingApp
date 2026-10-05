import QtQuick 2.6
import Sailfish.Silica 1.0
import "../components"
import "../components/Accent.js" as Accent
import "../components/Format.js" as Format

// Every saved ride, newest first, grouped by the week it was ridden in, each on a tile in the zone
// its average power reached. Reached from the first page by a forward swipe, Sailfish's attached
// page, where Android has a tab.
Page {
    id: page

    readonly property var progress: core.state.progress || ({})
    readonly property var rides: core.list("history")
    readonly property var zones: core.state.zones || []
    readonly property real ftp: (core.state.settings || {}).ftp || 200

    allowedOrientations: Orientation.All

    Component.onCompleted: rides.weekField = "start"

    SilicaListView {
        id: list

        anchors.fill: parent
        model: page.rides

        header: Column {
            width: list.width

            PageHeader { title: qsTr("History") }

            // The whole of it, at a glance.
            Row {
                x: Theme.horizontalPageMargin - Theme.paddingSmall / 2
                width: parent.width - 2 * x
                visible: page.rides.count > 0

                StatTile {
                    width: parent.width / 3
                    enabled: false
                    icon: "directions_bike"
                    tint: Accent.sky
                    value: page.progress.rides || 0
                    label: (page.progress.rides || 0) === 1 ? qsTr("ride") : qsTr("rides")
                }
                StatTile {
                    width: parent.width / 3
                    enabled: false
                    icon: "schedule"
                    tint: Accent.green
                    value: (page.progress.seconds || 0) / 3600
                    decimals: 1
                    unit: "h"
                    label: qsTr("ridden")
                }
                StatTile {
                    width: parent.width / 3
                    enabled: false
                    icon: "local_fire_department"
                    tint: Accent.power
                    value: page.progress.kilojoules || 0
                    unit: "kJ"
                    label: qsTr("of work")
                }
            }
            Item { width: 1; height: Theme.paddingMedium }
        }

        section {
            property: "week"
            delegate: SectionHeader {
                text: {
                    var monday = new Date(section + "T00:00:00")
                    return qsTr("Week of %1").arg(monday.toLocaleDateString(Qt.locale(), Locale.ShortFormat))
                }
            }
        }

        delegate: ListItem {
            id: item

            readonly property var ride: model.item
            readonly property int zone: Format.zoneForPct(page.zones, (item.ride.avgPower || 0) * 100 / page.ftp)

            contentHeight: Theme.itemSizeMedium
            onClicked: pageStack.push(Qt.resolvedUrl("RideDetailPage.qml"), { rideId: model.key })

            menu: ContextMenu {
                MenuItem {
                    text: qsTr("Delete")
                    onClicked: item.remorseDelete(function() {
                        core.request({ "cmd": "history.delete", "id": parseInt(model.key) }, null)
                    })
                }
            }

            IconTile {
                id: tile

                anchors {
                    left: parent.left
                    leftMargin: Theme.horizontalPageMargin
                    verticalCenter: parent.verticalCenter
                }
                name: "directions_bike"
                tint: Format.zoneColor(page.zones, item.zone, Theme.highlightColor)
                highlighted: item.highlighted
            }

            Column {
                anchors {
                    left: tile.right
                    leftMargin: Theme.paddingLarge
                    right: power.left
                    rightMargin: Theme.paddingMedium
                    verticalCenter: parent.verticalCenter
                }

                Label {
                    width: parent.width
                    truncationMode: TruncationMode.Fade
                    color: item.highlighted ? Theme.highlightColor : Theme.primaryColor
                    text: item.ride.name
                }
                Label {
                    width: parent.width
                    truncationMode: TruncationMode.Fade
                    color: Theme.secondaryColor
                    font.pixelSize: Theme.fontSizeExtraSmall
                    text: new Date(item.ride.startMs).toLocaleString(Qt.locale(), Locale.ShortFormat) + " · "
                          + Format.duration(item.ride.durationSeconds)
                }
            }

            Row {
                id: power

                anchors {
                    right: parent.right
                    rightMargin: Theme.horizontalPageMargin
                    verticalCenter: parent.verticalCenter
                }
                spacing: Theme.paddingSmall / 2

                Label {
                    id: watts

                    color: item.highlighted ? Theme.highlightColor : Theme.primaryColor
                    font.pixelSize: Theme.fontSizeMedium
                    font.family: Theme.fontFamilyHeading
                    text: item.ride.avgPower
                }
                Label {
                    anchors.baseline: watts.baseline
                    color: Theme.secondaryColor
                    font.pixelSize: Theme.fontSizeExtraSmall
                    text: "W"
                }
            }
        }

        ViewPlaceholder {
            enabled: page.rides.count === 0
            text: qsTr("No rides yet")
            hintText: qsTr("Rides you finish are kept here.")
        }

        VerticalScrollDecorator { }
    }
}

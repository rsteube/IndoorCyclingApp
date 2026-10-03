import QtQuick 2.6
import Sailfish.Silica 1.0
import "../components/Format.js" as Format

// Every saved ride, newest first, grouped by the week it was ridden in. Reached from the first
// page by a forward swipe, Sailfish's attached page, where Android has a tab.
Page {
    id: page

    readonly property var progress: core.state.progress || ({})
    readonly property var rides: core.list("history")

    allowedOrientations: Orientation.All

    Component.onCompleted: rides.weekField = "start"

    SilicaListView {
        id: list

        anchors.fill: parent
        model: page.rides

        header: Column {
            width: list.width

            PageHeader { title: qsTr("History") }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: page.rides.count > 0
                color: Theme.highlightColor
                font.pixelSize: Theme.fontSizeSmall
                text: (page.progress.rides === 1 ? qsTr("1 ride") : qsTr("%1 rides").arg(page.progress.rides || 0)) + " · "
                      + Format.duration(page.progress.seconds || 0) + " · "
                      + qsTr("%1 kJ").arg(page.progress.kilojoules || 0)
            }
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

            Column {
                anchors {
                    left: parent.left
                    leftMargin: Theme.horizontalPageMargin
                    right: parent.right
                    rightMargin: Theme.horizontalPageMargin
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
                          + Format.duration(item.ride.durationSeconds) + " · "
                          + qsTr("%1 W").arg(item.ride.avgPower)
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

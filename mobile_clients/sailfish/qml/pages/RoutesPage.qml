import QtQuick 2.6
import Sailfish.Silica 1.0
import Sailfish.Pickers 1.0
import harbour.indoorbike 1.0
import "../components/Format.js" as Format

// Every route the rider can ride here: the ones the app ships, and the ones they added.
Page {
    id: page

    property string error

    allowedOrientations: Orientation.All

    Component {
        id: gpxPicker

        FilePickerPage {
            title: qsTr("Choose a GPX file")
            nameFilters: ["*.gpx", "*.GPX"]
            onSelectedContentPropertiesChanged: {
                var path = selectedContentProperties.filePath
                if (!path)
                    return
                core.request({ "cmd": "routes.importFile", "path": path }, function(reply) {
                    page.error = reply.ok ? "" : reply.message
                })
            }
        }
    }

    SilicaListView {
        id: list

        anchors.fill: parent
        model: core.list("routes")

        PullDownMenu {
            MenuItem {
                text: qsTr("Get workouts and routes")
                onClicked: pageStack.push(Qt.resolvedUrl("CommunityPage.qml"))
            }
            MenuItem {
                text: qsTr("Add a GPX file")
                onClicked: pageStack.push(gpxPicker)
            }
        }

        header: Column {
            width: list.width

            PageHeader { title: qsTr("Routes") }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: page.error.length > 0
                wrapMode: Text.Wrap
                color: Theme.errorColor
                font.pixelSize: Theme.fontSizeSmall
                text: page.error
            }
        }

        delegate: ListItem {
            id: item

            readonly property var route: model.item

            contentHeight: Theme.itemSizeExtraLarge
            onClicked: pageStack.push(Qt.resolvedUrl("RoutePage.qml"), { routeId: model.key })

            menu: ContextMenu {
                MenuItem {
                    visible: item.route.imported
                    text: qsTr("Remove")
                    onClicked: item.remorseDelete(function() {
                        core.request({ "cmd": "routes.delete", "id": model.key }, null)
                    })
                }
            }

            RouteShape {
                id: shape

                anchors {
                    left: parent.left
                    leftMargin: Theme.horizontalPageMargin
                    verticalCenter: parent.verticalCenter
                }
                width: Theme.itemSizeLarge
                height: Theme.itemSizeLarge
                points: item.route.shape
                color: Theme.highlightColor
                lineWidth: 2
            }

            Column {
                anchors {
                    left: shape.right
                    leftMargin: Theme.paddingLarge
                    right: parent.right
                    rightMargin: Theme.horizontalPageMargin
                    verticalCenter: parent.verticalCenter
                }

                Label {
                    width: parent.width
                    truncationMode: TruncationMode.Fade
                    color: item.highlighted ? Theme.highlightColor : Theme.primaryColor
                    text: item.route.name
                }
                Label {
                    width: parent.width
                    truncationMode: TruncationMode.Fade
                    color: Theme.secondaryColor
                    font.pixelSize: Theme.fontSizeExtraSmall
                    text: qsTr("%1 km · %2 m up").arg((item.route.distanceMeters / 1000).toFixed(1))
                                                  .arg(Math.round(item.route.ascentMeters))
                          + (item.route.estimateSeconds ? " · " + qsTr("about %1").arg(Format.duration(item.route.estimateSeconds)) : "")
                }
                Label {
                    width: parent.width
                    visible: !!item.route.progressMeters
                    color: Theme.highlightColor
                    font.pixelSize: Theme.fontSizeExtraSmall
                    text: item.route.progressMeters ? qsTr("Left off at %1 km").arg((item.route.progressMeters / 1000).toFixed(1)) : ""
                }
            }
        }

        ViewPlaceholder {
            enabled: list.count === 0
            text: qsTr("No routes")
            hintText: qsTr("Pull down to add a GPX file.")
        }

        VerticalScrollDecorator { }
    }
}

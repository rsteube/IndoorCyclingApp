import QtQuick 2.6
import Sailfish.Silica 1.0
import "../components"
import "../components/Format.js" as Format

// One community repository: its workouts and its routes, each one tap from the rider's library.
Page {
    id: page

    property string manifestUrl
    property string repoId
    property string repoName
    property var workouts: []
    property var routes: []
    property string error
    property int loading: 2

    function load() {
        loading = 2
        core.request({ "cmd": "repo.workouts", "url": manifestUrl }, function(reply) {
            loading -= 1
            if (reply.ok)
                page.workouts = reply.value.workouts
            else
                page.error = reply.message
        })
        core.request({ "cmd": "repo.routes", "url": manifestUrl }, function(reply) {
            loading -= 1
            if (reply.ok)
                page.routes = reply.value.routes
        })
    }

    allowedOrientations: Orientation.All
    Component.onCompleted: load()

    SilicaFlickable {
        anchors.fill: parent
        contentHeight: column.height + Theme.paddingLarge

        Column {
            id: column

            width: parent.width

            PageHeader { title: page.repoName }

            BusyLabel {
                running: page.loading > 0
                text: qsTr("Loading")
                height: running ? Theme.itemSizeLarge : 0
            }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: page.error.length > 0
                wrapMode: Text.Wrap
                color: Theme.errorColor
                text: page.error
            }

            SectionHeader {
                visible: page.workouts.length > 0
                text: qsTr("Workouts")
            }

            Repeater {
                model: page.workouts

                ListItem {
                    id: workoutItem

                    property bool added: modelData.have

                    contentHeight: Theme.itemSizeExtraLarge
                    menu: ContextMenu {
                        MenuItem {
                            visible: !workoutItem.added
                            text: qsTr("Add to my workouts")
                            onClicked: core.request({ "cmd": "repo.addWorkout", "url": page.manifestUrl, "id": modelData.id,
                                                      "repoId": page.repoId, "repoName": page.repoName }, function(reply) {
                                if (reply.ok)
                                    workoutItem.added = true
                                else
                                    page.error = reply.message
                            })
                        }
                    }
                    onClicked: openMenu()

                    Column {
                        anchors {
                            left: parent.left
                            leftMargin: Theme.horizontalPageMargin
                            right: parent.right
                            rightMargin: Theme.horizontalPageMargin
                            verticalCenter: parent.verticalCenter
                        }
                        spacing: Theme.paddingSmall
                        Label {
                            width: parent.width
                            truncationMode: TruncationMode.Fade
                            color: Theme.primaryColor
                            text: modelData.name + (workoutItem.added ? " ✓" : "")
                        }
                        Label {
                            width: parent.width
                            color: Theme.secondaryColor
                            font.pixelSize: Theme.fontSizeExtraSmall
                            text: qsTr("%1 · %2 · TSS %3").arg(Format.duration(modelData.totalSeconds)).arg(modelData.tag).arg(modelData.tss)
                        }
                        WorkoutProfile {
                            width: parent.width
                            height: Theme.paddingLarge * 1.5
                            segments: modelData.segments
                        }
                    }
                }
            }

            SectionHeader {
                visible: page.routes.length > 0
                text: qsTr("Routes")
            }

            Repeater {
                model: page.routes

                ListItem {
                    id: routeItem

                    property bool added: modelData.have

                    contentHeight: Theme.itemSizeMedium
                    menu: ContextMenu {
                        MenuItem {
                            visible: !routeItem.added
                            text: qsTr("Add to my routes")
                            onClicked: core.request({ "cmd": "repo.addRoute", "url": page.manifestUrl, "id": modelData.id,
                                                      "repoId": page.repoId, "repoName": page.repoName }, function(reply) {
                                if (reply.ok)
                                    routeItem.added = true
                                else
                                    page.error = reply.message
                            })
                        }
                    }
                    onClicked: openMenu()

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
                            color: Theme.primaryColor
                            text: modelData.name + (routeItem.added ? " ✓" : "")
                        }
                        Label {
                            width: parent.width
                            truncationMode: TruncationMode.Fade
                            color: Theme.secondaryColor
                            font.pixelSize: Theme.fontSizeExtraSmall
                            text: qsTr("%1 km · %2 m up").arg((modelData.distanceMeters / 1000).toFixed(1)).arg(Math.round(modelData.ascentMeters))
                                  + (modelData.place ? " · " + modelData.place : "")
                        }
                    }
                }
            }
        }

        VerticalScrollDecorator { }
    }
}

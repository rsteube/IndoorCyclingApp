import QtQuick 2.6
import Sailfish.Silica 1.0
import "../components"
import "../components/Format.js" as Format

// One workout: its shape, its numbers at the rider's FTP, and riding it.
Page {
    id: page

    property string workoutId
    readonly property var workout: {
        var revision = workouts.revision
        return workouts.get(workoutId)
    }
    readonly property var workouts: core.list("workouts")

    readonly property bool trainerReady: !!(core.state.trainer || {}).connected

    // A ride needs the trainer: without one, pairing comes first, as on Android.
    function ride() {
        if (!trainerReady) {
            pageStack.push(Qt.resolvedUrl("PairPage.qml"))
            return
        }
        var reply = core.send({ "cmd": "ride.prepareWorkout", "id": workoutId })
        if (reply.ok)
            pageStack.push(Qt.resolvedUrl("RideScreenPage.qml"))
    }

    allowedOrientations: Orientation.All

    SilicaFlickable {
        anchors.fill: parent
        contentHeight: column.height + Theme.paddingLarge

        PullDownMenu {
            MenuItem {
                text: page.workout.favourite ? qsTr("Remove from favourites") : qsTr("Add to favourites")
                onClicked: core.send({ "cmd": "workouts.favourite", "id": page.workoutId,
                                       "favourite": !page.workout.favourite })
            }
            MenuItem {
                text: page.trainerReady ? qsTr("Ride this workout") : qsTr("Pair a trainer to ride")
                onClicked: page.ride()
            }
        }

        Column {
            id: column

            width: parent.width
            spacing: Theme.paddingLarge

            PageHeader {
                title: page.workout.name || ""
                description: page.workout.source || ""
            }

            WorkoutProfile {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                height: Theme.itemSizeHuge
                segments: page.workout.segments || []
            }

            Grid {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                columns: 3
                rowSpacing: Theme.paddingLarge

                Reading {
                    width: parent.width / 3
                    label: qsTr("Time")
                    value: Format.duration(page.workout.totalSeconds)
                }
                Reading {
                    width: parent.width / 3
                    label: qsTr("TSS")
                    value: page.workout.tss !== undefined ? page.workout.tss : "--"
                }
                Reading {
                    width: parent.width / 3
                    label: qsTr("IF")
                    value: page.workout.intensityFactor !== undefined ? page.workout.intensityFactor.toFixed(2) : "--"
                }
                Reading {
                    width: parent.width / 3
                    label: qsTr("Energy")
                    value: page.workout.kilojoules !== undefined ? page.workout.kilojoules : "--"
                    unit: "kJ"
                }
                Reading {
                    width: parent.width / 3
                    label: qsTr("NP")
                    value: page.workout.normalizedPower !== undefined ? page.workout.normalizedPower : "--"
                    unit: "W"
                }
                Reading {
                    width: parent.width / 3
                    label: qsTr("Focus")
                    value: page.workout.tag || ""
                }
            }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: text.length > 0
                wrapMode: Text.Wrap
                color: Theme.highlightColor
                font.pixelSize: Theme.fontSizeSmall
                text: page.workout.description || ""
            }

            Button {
                anchors.horizontalCenter: parent.horizontalCenter
                text: page.trainerReady ? qsTr("Ride this workout") : qsTr("Pair a trainer to ride")
                onClicked: page.ride()
            }

            SectionHeader { text: qsTr("Intervals") }

            Repeater {
                model: page.workout.segments || []

                Item {
                    width: column.width
                    height: Theme.itemSizeExtraSmall

                    Rectangle {
                        id: swatch

                        anchors {
                            left: parent.left
                            leftMargin: Theme.horizontalPageMargin
                            verticalCenter: parent.verticalCenter
                        }
                        width: Theme.paddingMedium
                        height: Theme.paddingLarge
                        radius: width / 4
                        color: Format.zoneColor(core.state.zones, modelData.zone, Theme.highlightColor)
                    }
                    Label {
                        anchors {
                            left: swatch.right
                            leftMargin: Theme.paddingMedium
                            right: time.left
                            verticalCenter: parent.verticalCenter
                        }
                        truncationMode: TruncationMode.Fade
                        color: Theme.highlightColor
                        font.pixelSize: Theme.fontSizeSmall
                        text: qsTr("%1 · %2 % FTP").arg(modelData.type).arg(modelData.pct)
                    }
                    Label {
                        id: time

                        anchors {
                            right: parent.right
                            rightMargin: Theme.horizontalPageMargin
                            verticalCenter: parent.verticalCenter
                        }
                        color: Theme.secondaryColor
                        font.pixelSize: Theme.fontSizeSmall
                        text: Format.clock(modelData.seconds)
                    }
                }
            }
        }

        VerticalScrollDecorator { }
    }
}

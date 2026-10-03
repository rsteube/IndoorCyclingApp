import QtQuick 2.6
import Sailfish.Silica 1.0
import "../components"
import "../components/Format.js" as Format

// The workout library: the rider's own first, then the bundled ones.
Page {
    id: page

    allowedOrientations: Orientation.All

    SilicaListView {
        id: list

        anchors.fill: parent
        model: core.list("workouts")

        header: PageHeader { title: qsTr("Workouts") }

        delegate: ListItem {
            id: item

            readonly property var workout: model.item

            contentHeight: Theme.itemSizeExtraLarge
            onClicked: pageStack.push(Qt.resolvedUrl("WorkoutPage.qml"), { workoutId: model.key })

            menu: ContextMenu {
                MenuItem {
                    text: item.workout.favourite ? qsTr("Remove from favourites") : qsTr("Add to favourites")
                    onClicked: core.send({ "cmd": "workouts.favourite", "id": model.key,
                                           "favourite": !item.workout.favourite })
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
                spacing: Theme.paddingSmall

                Label {
                    width: parent.width
                    truncationMode: TruncationMode.Fade
                    color: item.highlighted ? Theme.highlightColor : Theme.primaryColor
                    text: (item.workout.favourite ? "★ " : "") + item.workout.name
                }
                Label {
                    width: parent.width
                    truncationMode: TruncationMode.Fade
                    color: Theme.secondaryColor
                    font.pixelSize: Theme.fontSizeExtraSmall
                    text: qsTr("%1 · %2 · TSS %3").arg(Format.duration(item.workout.totalSeconds))
                                                  .arg(item.workout.tag).arg(item.workout.tss)
                }
                WorkoutProfile {
                    width: parent.width
                    height: Theme.paddingLarge * 1.5
                    segments: item.workout.segments
                }
            }
        }

        VerticalScrollDecorator { }
    }
}

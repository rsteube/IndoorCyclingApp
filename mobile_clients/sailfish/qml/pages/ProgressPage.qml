import QtQuick 2.6
import Sailfish.Silica 1.0
import "../components/Format.js" as Format

// Level and title, the streak of weeks ridden, and the badges: earned ones, and what the rest take.
// All worked out by shared/progression, as on Android.
Page {
    id: page

    readonly property var progress: core.state.progress || ({})

    allowedOrientations: Orientation.All

    SilicaFlickable {
        anchors.fill: parent
        contentHeight: column.height + Theme.paddingLarge

        Column {
            id: column

            width: parent.width
            spacing: Theme.paddingLarge

            PageHeader { title: qsTr("Progress") }

            Label {
                anchors.horizontalCenter: parent.horizontalCenter
                color: Theme.highlightColor
                font.pixelSize: Theme.fontSizeHuge
                text: qsTr("Level %1").arg(page.progress.level || 0)
            }
            Label {
                anchors.horizontalCenter: parent.horizontalCenter
                color: Theme.primaryColor
                font.pixelSize: Theme.fontSizeLarge
                text: page.progress.title || ""
            }

            ProgressBar {
                width: parent.width
                minimumValue: 0
                maximumValue: 1
                value: page.progress.levelProgress || 0
                label: qsTr("%1 XP · %2 to the next level").arg(page.progress.xp || "0").arg(page.progress.xpToNext || "0")
            }

            SectionHeader { text: qsTr("Weeks ridden") }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                wrapMode: Text.Wrap
                color: Theme.highlightColor
                text: (page.progress.streakWeeks === 1 ? qsTr("1 week in a row")
                                                       : qsTr("%1 weeks in a row").arg(page.progress.streakWeeks || 0))
                      + " · " + qsTr("longest %1").arg(page.progress.longestStreakWeeks || 0)
            }

            // The last fourteen weeks, oldest first: how many days of each had a ride.
            Row {
                x: Theme.horizontalPageMargin
                spacing: Theme.paddingSmall

                Repeater {
                    model: page.progress.weeks || []

                    Rectangle {
                        readonly property real cell: (column.width - 2 * Theme.horizontalPageMargin) / 14
                                                     - Theme.paddingSmall
                        width: cell
                        height: cell
                        radius: cell / 4
                        color: modelData > 0 ? Theme.highlightColor : Theme.rgba(Theme.primaryColor, Theme.opacityFaint)
                        opacity: modelData > 0 ? Math.min(1, 0.4 + modelData * 0.2) : 1
                    }
                }
            }

            SectionHeader { text: qsTr("Badges") }

            Repeater {
                model: page.progress.badges || []

                Item {
                    width: column.width
                    height: Theme.itemSizeMedium

                    Label {
                        id: mark

                        anchors {
                            left: parent.left
                            leftMargin: Theme.horizontalPageMargin
                            verticalCenter: parent.verticalCenter
                        }
                        font.pixelSize: Theme.fontSizeLarge
                        color: modelData.unlocked ? Theme.highlightColor : Theme.secondaryColor
                        opacity: modelData.unlocked ? 1 : Theme.opacityLow
                        text: modelData.unlocked ? "★" : "☆"
                    }
                    Column {
                        anchors {
                            left: mark.right
                            leftMargin: Theme.paddingLarge
                            right: parent.right
                            rightMargin: Theme.horizontalPageMargin
                            verticalCenter: parent.verticalCenter
                        }
                        opacity: modelData.unlocked ? 1 : Theme.opacityHigh

                        Label {
                            width: parent.width
                            color: Theme.highlightColor
                            text: modelData.title
                        }
                        Label {
                            width: parent.width
                            wrapMode: Text.Wrap
                            color: Theme.secondaryColor
                            font.pixelSize: Theme.fontSizeExtraSmall
                            text: modelData.unlocked && modelData.unlockedAt
                                  ? qsTr("Earned %1").arg(new Date(modelData.unlockedAt).toLocaleDateString(Qt.locale(), Locale.ShortFormat))
                                  : modelData.hint
                        }
                    }
                }
            }
        }

        VerticalScrollDecorator { }
    }
}

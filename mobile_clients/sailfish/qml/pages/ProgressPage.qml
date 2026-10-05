import QtQuick 2.6
import Sailfish.Silica 1.0
import "../components"
import "../components/Accent.js" as Accent

// Level and title, the streak of weeks ridden, and the badges: earned ones, and what the rest take.
// All worked out by shared/progression, as on Android. The level ring fills and the weeks light up
// one after another each time the page opens.
Page {
    id: page

    readonly property var progress: core.state.progress || ({})
    property bool shown

    allowedOrientations: Orientation.All
    onStatusChanged: if (status === PageStatus.Active) shown = true

    SilicaFlickable {
        anchors.fill: parent
        contentHeight: column.height + Theme.paddingLarge

        Column {
            id: column

            width: parent.width
            spacing: Theme.paddingLarge

            PageHeader { title: qsTr("Progress") }

            // The level, inside a ring of how far it is to the next one.
            Item {
                anchors.horizontalCenter: parent.horizontalCenter
                width: Theme.itemSizeHuge * 1.8
                height: width

                ProgressRing {
                    anchors.fill: parent
                    value: page.shown ? (page.progress.levelProgress || 0) : 0
                    duration: 1200
                    lineWidth: Theme.paddingLarge
                    color: Theme.highlightColor
                }
                Column {
                    anchors.centerIn: parent

                    Label {
                        anchors.horizontalCenter: parent.horizontalCenter
                        color: Theme.secondaryHighlightColor
                        font.pixelSize: Theme.fontSizeExtraSmall
                        font.capitalization: Font.AllUppercase
                        text: qsTr("Level")
                    }
                    AnimatedNumber {
                        anchors.horizontalCenter: parent.horizontalCenter
                        value: page.shown ? (page.progress.level || 0) : 0
                        duration: 900
                        color: Theme.highlightColor
                        font.pixelSize: Theme.fontSizeHuge * 1.3
                        font.family: Theme.fontFamilyHeading
                    }
                }
            }

            Column {
                width: parent.width
                spacing: Theme.paddingSmall

                Label {
                    anchors.horizontalCenter: parent.horizontalCenter
                    color: Theme.primaryColor
                    font.pixelSize: Theme.fontSizeLarge
                    text: page.progress.title || ""
                }
                Label {
                    anchors.horizontalCenter: parent.horizontalCenter
                    color: Theme.secondaryHighlightColor
                    font.pixelSize: Theme.fontSizeSmall
                    text: qsTr("%1 XP · %2 to the next level").arg(page.progress.xp || "0").arg(page.progress.xpToNext || "0")
                }
            }

            SectionHeader { text: qsTr("Weeks ridden") }

            Row {
                x: Theme.horizontalPageMargin
                spacing: Theme.paddingLarge

                IconTile {
                    anchors.verticalCenter: parent.verticalCenter
                    name: "local_fire_department"
                    tint: Accent.power
                    muted: (page.progress.streakWeeks || 0) === 0
                }
                Column {
                    anchors.verticalCenter: parent.verticalCenter

                    Label {
                        color: Theme.highlightColor
                        text: page.progress.streakWeeks === 1 ? qsTr("1 week in a row")
                                                              : qsTr("%1 weeks in a row").arg(page.progress.streakWeeks || 0)
                    }
                    Label {
                        color: Theme.secondaryHighlightColor
                        font.pixelSize: Theme.fontSizeExtraSmall
                        text: qsTr("Longest: %1").arg(page.progress.longestStreakWeeks || 0)
                    }
                }
            }

            // The last fourteen weeks, oldest first: how many days of each had a ride. They light
            // up from the oldest, a beat apart.
            Row {
                x: Theme.horizontalPageMargin
                spacing: Theme.paddingSmall

                Repeater {
                    model: page.progress.weeks || []

                    Rectangle {
                        id: cell

                        readonly property real size: (column.width - 2 * Theme.horizontalPageMargin) / 14
                                                     - Theme.paddingSmall
                        width: size
                        height: size
                        radius: size / 4
                        color: modelData > 0 ? Theme.highlightColor : Theme.rgba(Theme.primaryColor, Theme.opacityFaint)
                        opacity: modelData > 0 ? Math.min(1, 0.4 + modelData * 0.2) : 1
                        scale: page.shown ? 1 : 0

                        Behavior on scale {
                            SequentialAnimation {
                                PauseAnimation { duration: 200 + index * 35 }
                                NumberAnimation { duration: 320; easing.type: Easing.OutBack }
                            }
                        }
                    }
                }
            }

            SectionHeader { text: qsTr("Badges") }

            Repeater {
                model: page.progress.badges || []

                Item {
                    width: column.width
                    height: Math.max(Theme.itemSizeMedium, badgeTexts.height + 2 * Theme.paddingMedium)

                    IconTile {
                        id: mark

                        anchors {
                            left: parent.left
                            leftMargin: Theme.horizontalPageMargin
                            verticalCenter: parent.verticalCenter
                        }
                        name: "emoji_events"
                        tint: Accent.warning
                        muted: !modelData.unlocked
                    }
                    Column {
                        id: badgeTexts

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

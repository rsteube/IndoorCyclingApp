import QtQuick 2.6
import Sailfish.Silica 1.0
import "../components"
import "../components/Accent.js" as Accent

// The first start: what the app does, the two numbers every ride is worked out from, and where
// the trainer comes in.
Dialog {
    id: dialog

    readonly property var settings: core.state.settings || ({})
    readonly property var limits: settings.limits || ({})

    canAccept: ftpField.acceptableInput && weightField.acceptableInput
    onAccepted: {
        core.send({ "cmd": "settings.set", "key": "ftp", "value": parseInt(ftpField.text) })
        core.send({ "cmd": "settings.set", "key": "weightKg", "value": parseFloat(weightField.text.replace(",", ".")) })
        core.send({ "cmd": "settings.set", "key": "onboarded", "value": true })
    }
    // Backing out is a choice too: the defaults stand, and this is not asked again.
    onRejected: core.send({ "cmd": "settings.set", "key": "onboarded", "value": true })

    SilicaFlickable {
        anchors.fill: parent
        contentHeight: column.height + Theme.paddingLarge

        Column {
            id: column

            width: parent.width
            spacing: Theme.paddingMedium

            DialogHeader {
                title: qsTr("Welcome")
                acceptText: qsTr("Start")
            }

            // The three ways to ride, arriving one after another.
            Row {
                anchors.horizontalCenter: parent.horizontalCenter
                spacing: Theme.paddingLarge

                Repeater {
                    model: [{ "icon": "stacked_bar_chart", "tint": Accent.power },
                            { "icon": "landscape", "tint": Accent.green },
                            { "icon": "sports_esports", "tint": Accent.violet }]

                    IconTile {
                        id: welcomeTile

                        width: Theme.itemSizeLarge
                        name: modelData.icon
                        tint: modelData.tint
                        scale: 0

                        SequentialAnimation {
                            running: true
                            PauseAnimation { duration: 250 + index * 140 }
                            NumberAnimation { target: welcomeTile; property: "scale"; to: 1; duration: 480; easing.type: Easing.OutBack }
                        }
                    }
                }
            }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                wrapMode: Text.Wrap
                horizontalAlignment: Text.AlignHCenter
                color: Theme.highlightColor
                text: qsTr("IndoorBike rides structured workouts, routes and games on a smart trainer. Your rides stay on this phone.")
            }

            SectionHeader { text: qsTr("You") }

            TextField {
                id: ftpField

                width: parent.width
                label: qsTr("FTP in watts")
                placeholderText: label
                inputMethodHints: Qt.ImhDigitsOnly
                text: dialog.settings.ftp || 200
                validator: IntValidator {
                    bottom: (dialog.limits.ftp || [50])[0]
                    top: (dialog.limits.ftp || [0, 600])[1]
                }
                EnterKey.iconSource: "image://theme/icon-m-enter-next"
                EnterKey.onClicked: weightField.focus = true
            }

            TextField {
                id: weightField

                width: parent.width
                label: qsTr("Weight in kg")
                placeholderText: label
                inputMethodHints: Qt.ImhFormattedNumbersOnly
                text: dialog.settings.weightKg ? Math.round(dialog.settings.weightKg * 10) / 10 : 75
                validator: DoubleValidator {
                    bottom: (dialog.limits.weightKg || [30])[0]
                    top: (dialog.limits.weightKg || [0, 250])[1]
                    decimals: 1
                    notation: DoubleValidator.StandardNotation
                }
                EnterKey.iconSource: "image://theme/icon-m-enter-close"
                EnterKey.onClicked: focus = false
            }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                wrapMode: Text.Wrap
                color: Theme.secondaryHighlightColor
                font.pixelSize: Theme.fontSizeSmall
                text: qsTr("FTP is the power you can hold for about an hour. Workouts are set as shares of it. If you do not know yours, start a little low: it is easier to raise than to suffer through.")
            }

            SectionHeader { text: qsTr("Your trainer") }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                wrapMode: Text.Wrap
                color: Theme.highlightColor
                font.pixelSize: Theme.fontSizeSmall
                text: qsTr("Turn Bluetooth on and wake the trainer by pedalling. Then tap the trainer on the first page to pair it. A heart rate strap pairs the same way. Once paired, both are found again by themselves.")
            }
        }

        VerticalScrollDecorator { }
    }
}

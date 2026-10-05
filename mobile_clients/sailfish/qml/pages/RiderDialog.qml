import QtQuick 2.6
import Sailfish.Silica 1.0
import "../components"

// FTP, weight and maximum heart rate: what every target, every W/kg and every heart rate zone is
// worked out from. `focusField` is the one the rider tapped to get here.
Dialog {
    id: dialog

    property string focusField: "ftp"

    readonly property var settings: core.state.settings || ({})
    readonly property var limits: settings.limits || ({})

    canAccept: ftpField.acceptableInput && weightField.acceptableInput && maxHrField.acceptableInput
    onAccepted: {
        core.send({ "cmd": "settings.set", "key": "ftp", "value": parseInt(ftpField.text) })
        core.send({ "cmd": "settings.set", "key": "weightKg", "value": parseFloat(weightField.text.replace(",", ".")) })
        core.send({ "cmd": "settings.set", "key": "maxHr", "value": parseInt(maxHrField.text) })
    }
    onStatusChanged: {
        if (status === PageStatus.Active) {
            var field = focusField === "weightKg" ? weightField : focusField === "maxHr" ? maxHrField : ftpField
            field.forceActiveFocus()
            field.selectAll()
        }
    }

    SilicaFlickable {
        anchors.fill: parent
        contentHeight: column.height + Theme.paddingLarge

        Column {
            id: column

            width: parent.width
            spacing: Theme.paddingMedium

            DialogHeader { title: qsTr("You") }

            TextField {
                id: ftpField

                width: parent.width
                label: qsTr("FTP in watts")
                placeholderText: label
                inputMethodHints: Qt.ImhDigitsOnly
                text: dialog.settings.ftp || ""
                validator: IntValidator {
                    bottom: (dialog.limits.ftp || [50])[0]
                    top: (dialog.limits.ftp || [0, 600])[1]
                }
                EnterKey.iconSource: "image://theme/icon-m-enter-next"
                EnterKey.onClicked: weightField.focus = true
            }
            Note {
                text: qsTr("The power you can hold for about an hour. Every workout target is a share of it. Not sure? Start a little low: it is easier to raise than to suffer through.")
            }

            TextField {
                id: weightField

                width: parent.width
                label: qsTr("Weight in kg")
                placeholderText: label
                inputMethodHints: Qt.ImhFormattedNumbersOnly
                text: dialog.settings.weightKg ? Math.round(dialog.settings.weightKg * 10) / 10 : ""
                validator: DoubleValidator {
                    bottom: (dialog.limits.weightKg || [30])[0]
                    top: (dialog.limits.weightKg || [0, 250])[1]
                    decimals: 1
                    notation: DoubleValidator.StandardNotation
                }
                EnterKey.iconSource: "image://theme/icon-m-enter-next"
                EnterKey.onClicked: maxHrField.focus = true
            }
            Note {
                text: qsTr("For W/kg, and for the speed a route gives you on a climb.")
            }

            TextField {
                id: maxHrField

                width: parent.width
                label: qsTr("Maximum heart rate")
                placeholderText: label
                inputMethodHints: Qt.ImhDigitsOnly
                text: dialog.settings.maxHr || ""
                validator: IntValidator {
                    bottom: (dialog.limits.maxHr || [100])[0]
                    top: (dialog.limits.maxHr || [0, 230])[1]
                }
                EnterKey.iconSource: "image://theme/icon-m-enter-accept"
                EnterKey.onClicked: if (dialog.canAccept) dialog.accept()
            }
            Note {
                text: qsTr("Sets your heart rate zones.")
            }
        }

        VerticalScrollDecorator { }
    }
}

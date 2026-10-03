import QtQuick 2.6
import Sailfish.Silica 1.0

// FTP and weight: what every target and every calorie is worked out from.
Dialog {
    id: dialog

    readonly property var settings: core.state.settings || ({})
    readonly property var limits: settings.limits || ({})

    canAccept: ftpField.acceptableInput && weightField.acceptableInput
    onAccepted: {
        core.send({ "cmd": "settings.set", "key": "ftp", "value": parseInt(ftpField.text) })
        core.send({ "cmd": "settings.set", "key": "weightKg", "value": parseFloat(weightField.text.replace(",", ".")) })
    }

    Column {
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
            EnterKey.iconSource: "image://theme/icon-m-enter-accept"
            EnterKey.onClicked: dialog.accept()
        }

        Label {
            x: Theme.horizontalPageMargin
            width: parent.width - 2 * Theme.horizontalPageMargin
            wrapMode: Text.Wrap
            color: Theme.highlightColor
            font.pixelSize: Theme.fontSizeSmall
            text: qsTr("FTP is the power you can hold for about an hour. Workouts are set as shares of it. If you do not know yours, start a little low: it is easier to raise than to suffer through.")
        }
    }
}

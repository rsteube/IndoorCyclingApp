import QtQuick 2.6
import Sailfish.Silica 1.0
import "../components"

// One number setting on its own: a field, what it is for, and accept or back out.
Dialog {
    id: dialog

    property string title
    property string key
    property string label
    property string hint
    property real value
    property var limits: [0, 1000]
    property int decimals: 0

    canAccept: field.acceptableInput
    onAccepted: {
        var parsed = decimals > 0 ? parseFloat(field.text.replace(",", ".")) : parseInt(field.text)
        core.send({ "cmd": "settings.set", "key": key, "value": parsed })
    }
    onStatusChanged: {
        if (status === PageStatus.Active) {
            field.forceActiveFocus()
            field.selectAll()
        }
    }

    Column {
        width: parent.width
        spacing: Theme.paddingMedium

        DialogHeader { title: dialog.title }

        TextField {
            id: field

            width: parent.width
            label: dialog.label
            placeholderText: label
            inputMethodHints: dialog.decimals > 0 ? Qt.ImhFormattedNumbersOnly : Qt.ImhDigitsOnly
            text: dialog.decimals > 0 ? (Math.round(dialog.value * 10) / 10).toString() : Math.round(dialog.value).toString()
            validator: DoubleValidator {
                bottom: dialog.limits[0]
                top: dialog.limits[1]
                decimals: dialog.decimals
                notation: DoubleValidator.StandardNotation
            }
            EnterKey.iconSource: "image://theme/icon-m-enter-accept"
            EnterKey.onClicked: if (dialog.canAccept) dialog.accept()
        }
        Note {
            visible: dialog.hint.length > 0
            text: dialog.hint
        }
    }
}

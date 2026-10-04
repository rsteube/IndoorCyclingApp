import QtQuick 2.6
import Sailfish.Silica 1.0

// A new name for something of the rider's own.
Dialog {
    id: dialog

    property string title
    property string name

    canAccept: field.text.trim().length > 0 && field.text.trim() !== name
    onAccepted: name = field.text.trim()

    Column {
        width: parent.width

        DialogHeader { title: dialog.title }

        TextField {
            id: field

            width: parent.width
            label: qsTr("Name")
            placeholderText: label
            text: dialog.name
            focus: true
            EnterKey.enabled: dialog.canAccept
            EnterKey.iconSource: "image://theme/icon-m-enter-accept"
            EnterKey.onClicked: dialog.accept()
        }
    }
}

import QtQuick 2.6
import Sailfish.Silica 1.0

// One text setting of a connection, saved when the field is left, as the Android and iOS apps save
// theirs as they are typed. Empty clears it.
TextField {
    id: field

    property string key
    property string value

    function save() {
        if (text.trim() !== value)
            core.send({ "cmd": "connections.set", "key": key, "value": text.trim() })
    }

    width: parent ? parent.width : 0
    placeholderText: label
    inputMethodHints: Qt.ImhNoAutoUppercase | Qt.ImhNoPredictiveText
    text: value
    onValueChanged: if (!activeFocus) text = value
    onActiveFocusChanged: if (!activeFocus) save()

    EnterKey.iconSource: "image://theme/icon-m-enter-close"
    EnterKey.onClicked: focus = false
}

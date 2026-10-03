import QtQuick 2.6
import Sailfish.Silica 1.0

// One number setting, saved as soon as it is a valid one and put back when it is left invalid, as
// the Android and iOS apps do.
TextField {
    id: field

    property string key
    property real value
    property var limits: [0, 1000]
    property int decimals: 0

    function save() {
        var parsed = decimals > 0 ? parseFloat(text.replace(",", ".")) : parseInt(text)
        if (!isNaN(parsed) && parsed >= limits[0] && parsed <= limits[1] && parsed !== value)
            core.send({ "cmd": "settings.set", "key": key, "value": parsed })
        else
            text = format(value)
    }

    function format(v) {
        return decimals > 0 ? (Math.round(v * 10) / 10).toString() : Math.round(v).toString()
    }

    width: parent ? parent.width : 0
    placeholderText: label
    inputMethodHints: decimals > 0 ? Qt.ImhFormattedNumbersOnly : Qt.ImhDigitsOnly
    text: format(value)
    onValueChanged: if (!activeFocus) text = format(value)
    onActiveFocusChanged: if (!activeFocus) save()

    EnterKey.iconSource: "image://theme/icon-m-enter-close"
    EnterKey.onClicked: focus = false
}

import QtQuick 2.6
import Sailfish.Silica 1.0

// A number that glides to its new value rather than jumping, so a reading that changes four times
// a second reads as a needle, not as flicker. `placeholder` stands in while there is nothing to show.
Label {
    id: root

    property real value
    property int decimals: 0
    property bool available: true
    property string placeholder: "--"
    property int duration: 220

    property real shown: value

    Behavior on shown {
        enabled: root.available && root.visible
        NumberAnimation { duration: root.duration; easing.type: Easing.OutCubic }
    }

    text: available ? shown.toFixed(decimals) : placeholder
}

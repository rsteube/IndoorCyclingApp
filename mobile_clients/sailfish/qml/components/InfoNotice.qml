import QtQuick 2.6
import Sailfish.Silica 1.0

// A line that says what just happened, at the foot of a page, and goes again by itself.
Rectangle {
    id: root

    function show(message) {
        label.text = message
        opacity = 1
        hide.restart()
    }

    anchors {
        left: parent.left
        right: parent.right
        bottom: parent.bottom
    }
    height: label.height + 2 * Theme.paddingLarge
    color: Theme.rgba(Theme.highlightDimmerColor, 0.9)
    opacity: 0
    visible: opacity > 0

    Behavior on opacity { FadeAnimator { } }

    Label {
        id: label

        anchors.centerIn: parent
        width: parent.width - 2 * Theme.horizontalPageMargin
        wrapMode: Text.Wrap
        horizontalAlignment: Text.AlignHCenter
        color: Theme.highlightColor
    }

    Timer {
        id: hide

        interval: 3000
        onTriggered: root.opacity = 0
    }
}

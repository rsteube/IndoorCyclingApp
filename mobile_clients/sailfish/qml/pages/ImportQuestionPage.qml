import QtQuick 2.6
import Sailfish.Silica 1.0

// An import would replace workouts the rider already has: replace them, keep both, or import nothing.
// The shared importer waits for the answer.
Page {
    id: page

    property var names: []
    property bool answered

    function answer(choice) {
        answered = true
        core.send({ "cmd": "import.answer", "answer": choice })
        pageStack.pop()
    }

    // Backing out is choosing nothing.
    onStatusChanged: if (status === PageStatus.Deactivating && !answered) core.send({ "cmd": "import.answer", "answer": "cancel" })

    Column {
        width: parent.width
        spacing: Theme.paddingLarge

        PageHeader { title: qsTr("You already have this") }

        Label {
            x: Theme.horizontalPageMargin
            width: parent.width - 2 * Theme.horizontalPageMargin
            wrapMode: Text.Wrap
            color: Theme.highlightColor
            text: page.names.length === 1
                  ? qsTr("Your library already has %1. Replace it with the one you are importing, or keep both?").arg(page.names[0])
                  : qsTr("Your library already has %1. Replace them with the ones you are importing, or keep both?").arg(page.names.join(", "))
        }

        ButtonLayout {
            Button {
                text: qsTr("Replace")
                onClicked: page.answer("replace")
            }
            Button {
                text: qsTr("Keep both")
                onClicked: page.answer("keepBoth")
            }
            Button {
                text: qsTr("Cancel")
                onClicked: page.answer("cancel")
            }
        }
    }
}

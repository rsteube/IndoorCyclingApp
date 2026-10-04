import QtQuick 2.6
import Sailfish.Silica 1.0

// A companion proved this ride's pairing code and asks to be remembered, so it can connect
// without one next time.
Dialog {
    id: dialog

    property string name

    signal done()

    acceptDestinationAction: PageStackAction.Pop
    onAccepted: core.send({ "cmd": "companion.acceptPairing" })
    onRejected: core.send({ "cmd": "companion.declinePairing" })
    onStatusChanged: if (status === PageStatus.Inactive && (result === DialogResult.Accepted || result === DialogResult.Rejected)) done()

    Column {
        width: parent.width
        spacing: Theme.paddingLarge

        DialogHeader {
            acceptText: qsTr("Remember")
            cancelText: qsTr("Not now")
        }

        Label {
            x: Theme.horizontalPageMargin
            width: parent.width - 2 * Theme.horizontalPageMargin
            wrapMode: Text.Wrap
            color: Theme.highlightColor
            font.pixelSize: Theme.fontSizeLarge
            text: qsTr("Remember %1?").arg(dialog.name || qsTr("this companion"))
        }
        Label {
            x: Theme.horizontalPageMargin
            width: parent.width - 2 * Theme.horizontalPageMargin
            wrapMode: Text.Wrap
            color: Theme.secondaryHighlightColor
            text: qsTr("It will connect without a code from now on. You can forget it on the Companion page.")
        }
    }
}

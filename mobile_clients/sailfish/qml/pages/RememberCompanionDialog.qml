import QtQuick 2.6
import Sailfish.Silica 1.0

// A companion proved this ride's pairing code and asks to be remembered, so it can connect
// without one next time.
Dialog {
    id: dialog

    property string name

    // The request the host is holding. It goes when the companion withdraws it or disconnects,
    // and then there is nothing left to answer: the question goes too, as on Android.
    readonly property var pending: (core.state.companion || {}).pendingPairing || null
    property bool withdrawn: false

    signal answered()

    acceptDestinationAction: PageStackAction.Pop
    onAccepted: core.send({ "cmd": "companion.acceptPairing" })
    onRejected: if (!withdrawn) core.send({ "cmd": "companion.declinePairing" })
    onStatusChanged: if (status === PageStatus.Inactive && (result === DialogResult.Accepted || result === DialogResult.Rejected)) answered()
    onPendingChanged: {
        if (!pending && status === PageStatus.Active) {
            withdrawn = true
            reject()
        }
    }

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
            text: qsTr("Remember this companion?")
        }
        Label {
            x: Theme.horizontalPageMargin
            width: parent.width - 2 * Theme.horizontalPageMargin
            wrapMode: Text.Wrap
            color: Theme.secondaryHighlightColor
            text: qsTr("\"%1\" wants to pair with this phone permanently. After this it can connect on its own whenever the two are on the same network, with no pairing code, and drive your trainer.").arg(dialog.name || qsTr("A companion"))
        }
        Label {
            x: Theme.horizontalPageMargin
            width: parent.width - 2 * Theme.horizontalPageMargin
            wrapMode: Text.Wrap
            color: Theme.secondaryColor
            font.pixelSize: Theme.fontSizeSmall
            text: qsTr("Only agree if this is a program you set up yourself. You can undo it later from the Companion page.")
        }
    }
}

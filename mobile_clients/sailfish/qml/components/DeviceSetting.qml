import QtQuick 2.6
import Sailfish.Silica 1.0

// A trainer, strap or controller in Settings: how it is, in the shared wording, and pairing,
// disconnecting or forgetting it from its menu.
ListItem {
    id: root

    property string kind
    property string pairTitle
    property string pairHint
    readonly property var device: core.state[kind] || ({})

    function pair() {
        pageStack.push(Qt.resolvedUrl("../pages/PairPage.qml"), { kind: kind, title: pairTitle, hint: pairHint })
    }

    width: parent ? parent.width : 0
    contentHeight: Math.max(Theme.itemSizeMedium, texts.height + 2 * Theme.paddingSmall)
    onClicked: if (!device.connected && !device.pairedId) pair()

    menu: ContextMenu {
        MenuItem {
            text: root.device.pairedId ? qsTr("Pair another") : qsTr("Pair")
            onClicked: root.pair()
        }
        MenuItem {
            visible: !!root.device.connected
            text: qsTr("Disconnect")
            onClicked: core.send({ "cmd": root.kind + ".disconnect" })
        }
        MenuItem {
            visible: !!root.device.pairedId
            text: qsTr("Forget")
            onClicked: root.remorseAction(qsTr("Forgetting"), function() {
                core.send({ "cmd": root.kind + ".forget" })
            })
        }
    }

    Column {
        id: texts

        anchors {
            left: parent.left
            leftMargin: Theme.horizontalPageMargin
            right: parent.right
            rightMargin: Theme.horizontalPageMargin
            verticalCenter: parent.verticalCenter
        }

        Label {
            width: parent.width
            truncationMode: TruncationMode.Fade
            color: root.highlighted ? Theme.highlightColor : Theme.primaryColor
            text: root.device.title || root.pairTitle
        }
        Label {
            width: parent.width
            wrapMode: Text.Wrap
            color: root.device.tone === "Bad" ? Theme.errorColor : Theme.secondaryColor
            font.pixelSize: Theme.fontSizeExtraSmall
            text: (root.device.status || "") + (root.device.helper ? "\n" + root.device.helper : "")
        }
    }
}

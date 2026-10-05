import QtQuick 2.6
import Sailfish.Silica 1.0
import "Accent.js" as Accent

// A trainer, strap or controller in Settings: what it is, how it is doing, in the shared wording.
// Tapping one that is not set up pairs it; tapping one that is opens what can be done with it.
SettingRow {
    id: root

    property string kind
    property string pairTitle
    property string pairHint
    readonly property var device: core.state[kind] || ({})
    readonly property bool known: !!device.connected || !!device.pairedId

    function pair() {
        pageStack.push(Qt.resolvedUrl("../pages/PairPage.qml"), { kind: kind, title: pairTitle, hint: pairHint })
    }

    icon: kind === "trainer" ? "pedal_bike" : kind === "hrm" ? "favorite" : "gamepad"
    tint: kind === "trainer" ? Accent.power : kind === "hrm" ? Accent.heart : Accent.violet
    muted: !known
    title: known ? (device.title || device.name || pairTitle) : pairTitle
    // What kinds of device fit is worth saying before one is set up, not after.
    subtitle: (device.status || "") + (device.helper && !known ? "\n" + device.helper : "")
    subtitleColor: device.tone === "Bad" ? Theme.errorColor : Theme.secondaryColor
    status: device.connected ? "ok"
            : device.state === "Connecting" || device.state === "Scanning" ? "busy"
            : device.tone === "Bad" ? "bad"
            : known ? "off" : ""

    onClicked: known ? openMenu() : pair()

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
}

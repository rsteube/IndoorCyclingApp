import QtQuick 2.6
import Sailfish.Silica 1.0
import "../components"

// Intervals.icu by athlete ID and personal API key: the way in for a build without the OAuth client,
// and the fallback for one with it, since the Sailfish browser does not always load Intervals.icu's
// approval page. `intervals` is the core's connections.intervals.
Column {
    id: fields

    property var intervals: ({})

    width: parent ? parent.width : 0
    spacing: Theme.paddingMedium

    TextSetting {
        key: "intervalsAthleteId"
        label: qsTr("Athlete ID, for example i12345")
        value: fields.intervals.athleteId || ""
    }
    PasswordField {
        width: parent.width
        label: qsTr("API key")
        placeholderText: label
        text: fields.intervals.apiKey || ""
        onActiveFocusChanged: {
            if (!activeFocus && text.trim() !== (fields.intervals.apiKey || ""))
                core.send({ "cmd": "connections.set", "key": "intervalsApiKey", "value": text.trim() })
        }
        EnterKey.iconSource: "image://theme/icon-m-enter-close"
        EnterKey.onClicked: focus = false
    }
    Note {
        text: qsTr("Both are on your Intervals.icu settings page, under Developer settings. The key is kept in a file only this app can read.")
    }
}

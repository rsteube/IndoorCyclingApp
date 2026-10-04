import QtQuick 2.6
import Sailfish.Silica 1.0
import "../components"

// Where finished rides can be sent. Each service is off until it is set up, and nothing is
// contacted before then.
Page {
    id: page
    objectName: "connections"

    readonly property var intervals: (core.state.connections || {}).intervals || ({})
    readonly property var fitTrackee: (core.state.connections || {}).fitTrackee || ({})
    property var sports: []
    property string message

    function set(key, value) {
        core.send({ "cmd": "connections.set", "key": key, "value": value })
    }

    function loadSports() {
        if (!fitTrackee.connected)
            return
        core.request({ "cmd": "fitTrackee.sports" }, function(reply) {
            page.sports = reply.ok ? reply.value : []
        })
    }

    function signIn() {
        // Leave the fields first, so what was just typed is what the browser is sent with.
        page.focus = true
        var reply = core.send({ "cmd": "fitTrackee.authorizeUrl" })
        if (!reply.ok) {
            message = reply.message
            return
        }
        message = ""
        Qt.openUrlExternally(reply.value)
    }

    function connectIntervals() {
        var reply = core.send({ "cmd": "intervals.authorizeUrl" })
        if (!reply.ok) {
            message = reply.message
            return
        }
        message = ""
        Qt.openUrlExternally(reply.value)
    }

    function completeIntervals(url) {
        core.request({ "cmd": "intervals.complete", "url": url }, function(reply) {
            if (reply.ok) {
                page.message = ""
                intervalsPasted.text = ""
                notice.show(qsTr("Connected to Intervals.icu"))
            } else {
                page.message = reply.message
            }
        })
    }

    function complete(url) {
        core.request({ "cmd": "fitTrackee.complete", "url": url }, function(reply) {
            if (reply.ok) {
                page.message = ""
                pasted.text = ""
                notice.show(qsTr("Connected to FitTrackee"))
            } else {
                page.message = reply.message
            }
        })
    }

    property bool wasConnected: false

    allowedOrientations: Orientation.All
    Component.onCompleted: {
        wasConnected = !!fitTrackee.connected
        loadSports()
    }
    // Signed in from the browser's redirect while this page was open, or signed out.
    onFitTrackeeChanged: {
        if (!!fitTrackee.connected !== wasConnected) {
            wasConnected = !!fitTrackee.connected
            loadSports()
        }
    }

    // The browser hands its redirect to the app, which finishes the sign-in and says how it went.
    Connections {
        target: core
        onEventReceived: {
            if (topic === "intervals.signedIn" && payload) {
                if (payload.ok) {
                    page.message = ""
                    notice.show(qsTr("Connected to Intervals.icu"))
                } else {
                    page.message = payload.message
                }
                return
            }
            if (topic !== "fitTrackee.signedIn" || !payload)
                return
            if (payload.ok) {
                page.message = ""
                notice.show(qsTr("Connected to FitTrackee"))
            } else {
                page.message = payload.message
            }
        }
    }

    SilicaFlickable {
        anchors.fill: parent
        contentHeight: column.height + Theme.paddingLarge

        Column {
            id: column

            width: parent.width
            spacing: Theme.paddingMedium

            PageHeader { title: qsTr("Connections") }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                wrapMode: Text.Wrap
                color: Theme.secondaryHighlightColor
                font.pixelSize: Theme.fontSizeSmall
                text: qsTr("Finished rides can be uploaded to a training platform. Each one is off until you set it up, and nothing is contacted before then.")
            }

            SectionHeader { text: "Intervals.icu" }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                wrapMode: Text.Wrap
                color: Theme.secondaryHighlightColor
                font.pixelSize: Theme.fontSizeExtraSmall
                text: qsTr("Send your finished rides to Intervals.icu. Nothing is contacted until you connect, and after that only when a ride is uploaded.")
            }

            // Signing in, in a build that carries the OAuth client, as on Android.
            Column {
                width: parent.width
                visible: !page.intervals.signedIn && !!page.intervals.signInAvailable
                spacing: Theme.paddingMedium

                Label {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    wrapMode: Text.Wrap
                    color: Theme.highlightColor
                    font.pixelSize: Theme.fontSizeSmall
                    text: qsTr("You approve IndoorBike on Intervals.icu in your browser, so your password and API key never reach this app. It can upload rides and nothing else, and you can disconnect it here or in your Intervals.icu settings.")
                }
                Button {
                    anchors.horizontalCenter: parent.horizontalCenter
                    text: qsTr("Connect Intervals.icu")
                    onClicked: page.connectIntervals()
                }
                Label {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    visible: !!page.intervals.athleteId && !!page.intervals.apiKey
                    wrapMode: Text.Wrap
                    color: Theme.secondaryHighlightColor
                    font.pixelSize: Theme.fontSizeExtraSmall
                    text: qsTr("Connecting replaces the API key saved on this phone.")
                }
                Label {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    wrapMode: Text.Wrap
                    color: Theme.secondaryHighlightColor
                    font.pixelSize: Theme.fontSizeExtraSmall
                    text: qsTr("After you approve, the page Intervals.icu opens has a button back to IndoorBike. If it does not come back, copy that page's address and paste it here within two minutes:")
                }
                TextField {
                    id: intervalsPasted

                    width: parent.width
                    label: qsTr("Address of the page Intervals.icu opened")
                    placeholderText: label
                    inputMethodHints: Qt.ImhUrlCharactersOnly | Qt.ImhNoAutoUppercase | Qt.ImhNoPredictiveText
                    EnterKey.enabled: text.trim().length > 0
                    EnterKey.iconSource: "image://theme/icon-m-enter-accept"
                    EnterKey.onClicked: page.completeIntervals(text)
                }
            }

            // The athlete id and personal API key, in a build without the OAuth client.
            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: !page.intervals.signedIn && !page.intervals.signInAvailable
                wrapMode: Text.Wrap
                color: Theme.secondaryHighlightColor
                font.pixelSize: Theme.fontSizeExtraSmall
                text: qsTr("Both are on your Intervals.icu settings page, under Developer settings. The key is kept in a file only this app can read.")
            }

            TextSetting {
                visible: !page.intervals.signedIn && !page.intervals.signInAvailable
                key: "intervalsAthleteId"
                label: qsTr("Athlete ID, for example i12345")
                value: page.intervals.athleteId || ""
            }
            PasswordField {
                id: apiKey

                width: parent.width
                visible: !page.intervals.signedIn && !page.intervals.signInAvailable
                label: qsTr("API key")
                placeholderText: label
                text: page.intervals.apiKey || ""
                onActiveFocusChanged: {
                    if (!activeFocus && text.trim() !== (page.intervals.apiKey || ""))
                        page.set("intervalsApiKey", text.trim())
                }
                EnterKey.iconSource: "image://theme/icon-m-enter-close"
                EnterKey.onClicked: focus = false
            }
            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: !!page.intervals.signedIn
                wrapMode: Text.Wrap
                color: Theme.highlightColor
                text: page.intervals.athleteName ? qsTr("Connected as %1").arg(page.intervals.athleteName)
                                                 : qsTr("Connected")
            }
            TextSwitch {
                text: qsTr("Upload finished rides automatically")
                description: page.intervals.configured ? qsTr("Each finished ride is sent as soon as it is saved.")
                             : page.intervals.signInAvailable ? qsTr("Connect above first.")
                             : qsTr("Enter the athlete ID and key first.")
                enabled: !!page.intervals.configured
                checked: !!page.intervals.autoUpload
                automaticCheck: false
                onClicked: page.set("intervalsAutoUpload", !checked)
            }
            Button {
                anchors.horizontalCenter: parent.horizontalCenter
                visible: !!page.intervals.signedIn
                text: qsTr("Disconnect")
                onClicked: core.request({ "cmd": "intervals.disconnect" }, function(reply) {
                    notice.show(reply.ok && reply.value ? qsTr("Disconnected from Intervals.icu")
                                                        : qsTr("Disconnected on this phone. Intervals.icu couldn't be reached, so remove IndoorBike in your Intervals.icu settings as well."))
                })
            }

            SectionHeader { text: "FitTrackee" }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                wrapMode: Text.Wrap
                color: Theme.secondaryHighlightColor
                font.pixelSize: Theme.fontSizeExtraSmall
                text: qsTr("Your own FitTrackee server. Worth knowing before you set it up: FitTrackee is built for outdoor activities and only accepts files with GPS coordinates, which an indoor ride has none of. Rides therefore arrive as an entry with the date, duration and estimated distance, with the power, cadence and heart rate written into the notes. There is no power graph. Export a FIT file if you need one.")
            }

            Column {
                width: parent.width
                visible: !page.fitTrackee.connected
                spacing: Theme.paddingMedium

                Label {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    wrapMode: Text.Wrap
                    color: Theme.highlightColor
                    font.pixelSize: Theme.fontSizeSmall
                    text: qsTr("You approve this in your browser, on your own server, so the app never sees your password. In FitTrackee, go to Profile, Apps, Create an app, allow it to write workouts, and give it this redirect URI:")
                }
                BackgroundItem {
                    width: parent.width
                    onClicked: {
                        Clipboard.text = page.fitTrackee.redirectUri
                        notice.show(qsTr("Redirect URI copied"))
                    }
                    Label {
                        x: Theme.horizontalPageMargin
                        anchors.verticalCenter: parent.verticalCenter
                        font.family: "monospace"
                        color: Theme.highlightColor
                        text: page.fitTrackee.redirectUri || ""
                    }
                    Icon {
                        anchors {
                            right: parent.right
                            rightMargin: Theme.horizontalPageMargin
                            verticalCenter: parent.verticalCenter
                        }
                        source: "image://theme/icon-m-clipboard"
                    }
                }
                TextSetting {
                    key: "fitTrackeeUrl"
                    label: qsTr("Server address")
                    placeholderText: "https://fittrackee.example.org"
                    inputMethodHints: Qt.ImhUrlCharactersOnly | Qt.ImhNoAutoUppercase
                    value: page.fitTrackee.url || ""
                }
                TextSetting {
                    key: "fitTrackeeClientId"
                    label: qsTr("Client ID")
                    value: page.fitTrackee.clientId || ""
                }
                PasswordField {
                    width: parent.width
                    label: qsTr("Client secret")
                    placeholderText: label
                    text: page.fitTrackee.clientSecret || ""
                    onActiveFocusChanged: {
                        if (!activeFocus && text.trim() !== (page.fitTrackee.clientSecret || ""))
                            page.set("fitTrackeeClientSecret", text.trim())
                    }
                    EnterKey.iconSource: "image://theme/icon-m-enter-close"
                    EnterKey.onClicked: focus = false
                }
                Button {
                    anchors.horizontalCenter: parent.horizontalCenter
                    text: qsTr("Connect in the browser")
                    onClicked: page.signIn()
                }
                Label {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    wrapMode: Text.Wrap
                    color: Theme.secondaryHighlightColor
                    font.pixelSize: Theme.fontSizeExtraSmall
                    text: qsTr("After you approve, the browser comes back to IndoorBike. If it says it cannot open the address instead, copy that address, the one starting with %1, and paste it here:").arg(page.fitTrackee.redirectUri || "")
                }
                TextField {
                    id: pasted

                    width: parent.width
                    label: qsTr("Address the browser couldn't open")
                    placeholderText: label
                    inputMethodHints: Qt.ImhUrlCharactersOnly | Qt.ImhNoAutoUppercase | Qt.ImhNoPredictiveText
                    EnterKey.enabled: text.trim().length > 0
                    EnterKey.iconSource: "image://theme/icon-m-enter-accept"
                    EnterKey.onClicked: page.complete(text)
                }
            }

            Column {
                width: parent.width
                visible: !!page.fitTrackee.connected
                spacing: Theme.paddingMedium

                Label {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    truncationMode: TruncationMode.Fade
                    color: Theme.highlightColor
                    text: qsTr("Connected to %1").arg(page.fitTrackee.url || "")
                }
                ComboBox {
                    width: parent.width
                    visible: page.sports.length > 0
                    label: qsTr("File rides as")
                    currentIndex: {
                        for (var i = 0; i < page.sports.length; ++i) {
                            if (page.sports[i].id === page.fitTrackee.sportId)
                                return i
                        }
                        return -1
                    }
                    menu: ContextMenu {
                        Repeater {
                            model: page.sports
                            MenuItem {
                                text: modelData.label
                                onClicked: page.set("fitTrackeeSportId", modelData.id)
                            }
                        }
                    }
                }
                Label {
                    x: Theme.horizontalPageMargin
                    width: parent.width - 2 * Theme.horizontalPageMargin
                    visible: page.sports.length === 0
                    wrapMode: Text.Wrap
                    color: Theme.secondaryHighlightColor
                    font.pixelSize: Theme.fontSizeExtraSmall
                    text: qsTr("Rides are filed as Cycling (Virtual). Your server didn't share its list of sports, which is normal and doesn't stop anything.")
                }
                TextSwitch {
                    text: qsTr("Upload finished rides automatically")
                    description: qsTr("Each finished ride is sent as soon as it is saved.")
                    checked: !!page.fitTrackee.autoUpload
                    automaticCheck: false
                    onClicked: page.set("fitTrackeeAutoUpload", !checked)
                }
                Button {
                    anchors.horizontalCenter: parent.horizontalCenter
                    text: qsTr("Disconnect")
                    onClicked: {
                        core.send({ "cmd": "fitTrackee.signOut" })
                        page.sports = []
                    }
                }
            }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: page.message.length > 0
                wrapMode: Text.Wrap
                color: Theme.errorColor
                text: page.message
            }
        }

        VerticalScrollDecorator { }
    }

    InfoNotice { id: notice }
}

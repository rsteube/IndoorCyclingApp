import QtQuick 2.6
import Sailfish.Silica 1.0
import "../components"
import "../components/Accent.js" as Accent

// Where finished rides can be sent. Each service is one row saying how it stands and the one thing
// to do next; how it works, and what to do when the browser does not come back, fold away below.
// Nothing is contacted before a service is set up.
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

            PageHeader {
                title: qsTr("Connections")
                description: qsTr("Where your finished rides go")
            }

            // What went wrong, where it is seen: at the top, not under the last field.
            SettingRow {
                visible: page.message.length > 0
                enabled: false
                icon: "error"
                tint: Theme.errorColor
                title: qsTr("That didn't work")
                subtitle: page.message
                status: "bad"
            }

            // ---- Intervals.icu ----
            SectionHeader { text: "Intervals.icu" }

            SettingRow {
                enabled: false
                icon: "cloud_upload"
                tint: Accent.sky
                muted: !page.intervals.configured
                title: page.intervals.signedIn
                       ? (page.intervals.athleteName ? qsTr("Connected as %1").arg(page.intervals.athleteName) : qsTr("Connected"))
                       : page.intervals.configured ? qsTr("Connected with your API key")
                       : qsTr("Not connected")
                subtitle: qsTr("Training analysis for your rides: fitness, fatigue and every power curve.")
                status: page.intervals.configured ? "ok" : ""
            }

            // Signing in, in a build that carries the OAuth client, as on Android.
            Column {
                width: parent.width
                visible: !page.intervals.signedIn && !!page.intervals.signInAvailable
                spacing: Theme.paddingMedium

                Button {
                    anchors.horizontalCenter: parent.horizontalCenter
                    preferredWidth: Theme.buttonWidthLarge
                    text: qsTr("Connect Intervals.icu")
                    onClicked: page.connectIntervals()
                }
                Note {
                    visible: !!page.intervals.athleteId && !!page.intervals.apiKey
                    text: qsTr("Connecting replaces the API key saved on this phone.")
                }
                Note {
                    text: qsTr("If the Intervals.icu page stays empty in the browser, connect with your API key instead, below.")
                }
                Expander {
                    title: qsTr("How it works")

                    Note {
                        text: qsTr("You approve IndoorBike on Intervals.icu in your browser, so your password and API key never reach this app. It can upload rides and nothing else, and you can disconnect it here or in your Intervals.icu settings.")
                    }
                }
                Expander {
                    title: qsTr("The browser didn't come back?")

                    Note {
                        text: qsTr("After you approve, the page Intervals.icu opens has a button back to IndoorBike. If that does not work, copy the page's address and paste it here within two minutes.")
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
            }

            // The athlete id and personal API key: the way in for a build without the OAuth client,
            // and the fallback for one with it, since the Sailfish browser does not always load
            // Intervals.icu's approval page.
            Expander {
                id: apiKeyFallback

                visible: !page.intervals.signedIn && !!page.intervals.signInAvailable
                title: qsTr("Connect with an API key instead")
                expanded: !!page.intervals.athleteId || !!page.intervals.apiKey

                IntervalsKeyFields { intervals: page.intervals }
            }
            IntervalsKeyFields {
                visible: !page.intervals.signedIn && !page.intervals.signInAvailable
                intervals: page.intervals
            }

            TextSwitch {
                visible: !!page.intervals.configured
                text: qsTr("Upload finished rides automatically")
                description: qsTr("Each ride is sent as soon as it is saved. Otherwise, send one from its page in your history.")
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

            // ---- FitTrackee ----
            SectionHeader { text: "FitTrackee" }

            SettingRow {
                enabled: false
                icon: "dns"
                tint: Accent.violet
                muted: !page.fitTrackee.connected
                title: page.fitTrackee.connected ? qsTr("Connected") : qsTr("Not connected")
                subtitle: page.fitTrackee.connected ? (page.fitTrackee.url || "")
                                                    : qsTr("Your own server, for a log of your rides.")
                status: page.fitTrackee.connected ? "ok" : ""
            }

            Column {
                width: parent.width
                visible: !page.fitTrackee.connected
                spacing: Theme.paddingMedium

                Expander {
                    title: qsTr("What arrives on FitTrackee")

                    Note {
                        text: qsTr("FitTrackee is built for outdoor activities and only accepts files with GPS coordinates, which an indoor ride has none of. Rides therefore arrive as an entry with the date, duration and estimated distance, with power, cadence and heart rate written into the notes. There is no power graph: export a FIT file if you need one.")
                    }
                }

                Step {
                    number: 1
                    title: qsTr("Create an app in FitTrackee")
                    detail: qsTr("Profile, Apps, Create an app. Allow it to write workouts, and give it this redirect URI. Tap it to copy.")
                }
                BackgroundItem {
                    id: redirectItem

                    width: parent.width
                    onClicked: {
                        Clipboard.text = page.fitTrackee.redirectUri
                        notice.show(qsTr("Redirect URI copied"))
                    }
                    Label {
                        x: Theme.horizontalPageMargin + Theme.iconSizeSmallPlus + Theme.paddingLarge
                        anchors.verticalCenter: parent.verticalCenter
                        font.family: "monospace"
                        color: redirectItem.highlighted ? Theme.highlightColor : Theme.primaryColor
                        text: page.fitTrackee.redirectUri || ""
                    }
                    Glyph {
                        anchors {
                            right: parent.right
                            rightMargin: Theme.horizontalPageMargin
                            verticalCenter: parent.verticalCenter
                        }
                        width: Theme.iconSizeSmall
                        height: width
                        name: "content_copy"
                        color: redirectItem.highlighted ? Theme.highlightColor : Theme.secondaryColor
                    }
                }

                Step {
                    number: 2
                    title: qsTr("Enter its details")
                    done: !!page.fitTrackee.url && !!page.fitTrackee.clientId && !!page.fitTrackee.clientSecret
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

                Step {
                    number: 3
                    title: qsTr("Approve it in the browser")
                    detail: qsTr("On your own server, so the app never sees your password.")
                }
                Button {
                    anchors.horizontalCenter: parent.horizontalCenter
                    preferredWidth: Theme.buttonWidthLarge
                    text: qsTr("Connect FitTrackee")
                    onClicked: page.signIn()
                }
                Expander {
                    title: qsTr("The browser didn't come back?")

                    Note {
                        text: qsTr("If the browser says it cannot open an address starting with %1, copy that address and paste it here.").arg(page.fitTrackee.redirectUri || "")
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
            }

            Column {
                width: parent.width
                visible: !!page.fitTrackee.connected
                spacing: Theme.paddingMedium

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
                Note {
                    visible: page.sports.length === 0
                    text: qsTr("Rides are filed as Cycling (Virtual). Your server didn't share its list of sports, which is normal and doesn't stop anything.")
                }
                TextSwitch {
                    text: qsTr("Upload finished rides automatically")
                    description: qsTr("Each ride is sent as soon as it is saved.")
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
        }

        VerticalScrollDecorator { }
    }

    InfoNotice { id: notice }
}

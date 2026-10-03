import QtQuick 2.6
import Sailfish.Silica 1.0
import Sailfish.Share 1.0

// Telling us something is wrong: what happened, in the rider's words, and the app's own log of
// this run and the last, which never leaves the phone unless they send it.
Page {
    id: page

    property var report: null

    function build() {
        var reply = core.send({ "cmd": "app.bugReport", "context": what.text })
        page.report = reply.ok ? reply.value : null
        return page.report
    }

    allowedOrientations: Orientation.All

    ShareAction {
        id: shareAction

        title: qsTr("Send the report")
        mimeType: "text/plain"
    }

    SilicaFlickable {
        anchors.fill: parent
        contentHeight: column.height + Theme.paddingLarge

        Column {
            id: column

            width: parent.width
            spacing: Theme.paddingMedium

            PageHeader { title: qsTr("Report a problem") }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                wrapMode: Text.Wrap
                color: Theme.highlightColor
                font.pixelSize: Theme.fontSizeSmall
                text: qsTr("The report carries the app's log of this run and the last one: what it connected to and what went wrong. Device addresses are shortened in it. Nothing is sent until you send it.")
            }

            TextArea {
                id: what

                width: parent.width
                label: qsTr("What happened")
                placeholderText: label
            }

            ButtonLayout {
                Button {
                    text: qsTr("Email it")
                    onClicked: {
                        var r = page.build()
                        if (r)
                            Qt.openUrlExternally("mailto:" + r.address + "?subject=" + encodeURIComponent(r.subject)
                                                 + "&body=" + encodeURIComponent(r.text))
                    }
                }
                Button {
                    text: qsTr("Share it")
                    onClicked: {
                        var r = page.build()
                        if (!r)
                            return
                        shareAction.resources = [{ "data": r.text, "name": "indoorbike-report.txt" }]
                        shareAction.trigger()
                    }
                }
                Button {
                    text: qsTr("Copy it")
                    onClicked: {
                        var r = page.build()
                        if (r)
                            Clipboard.text = r.text
                    }
                }
            }
        }

        VerticalScrollDecorator { }
    }
}

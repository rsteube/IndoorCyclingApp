import QtQuick 2.6
import Sailfish.Silica 1.0
import "pages"
import "cover"
import "components"

ApplicationWindow {
    id: window

    // Something handed to the app from outside: the browser coming back from a FitTrackee
    // sign-in, a GPX route, or a workout file.
    function open(uri) {
        if (uri.indexOf("indoorbike://oauth") === 0) {
            // The connections page says how it went; bring it up if the rider left it.
            core.send({ "cmd": "fitTrackee.redirect", "url": uri })
            if (!pageStack.currentPage || pageStack.currentPage.objectName !== "connections")
                pageStack.push(Qt.resolvedUrl("pages/ConnectionsPage.qml"))
        } else if (uri.indexOf("indoorbike:") === 0) {
            notice.show(qsTr("IndoorBike doesn't know what to do with that link."))
        } else if (/\.gpx$/i.test(uri)) {
            core.request({ "cmd": "routes.importFile", "path": uri }, function(reply) {
                if (reply.ok)
                    pageStack.push(Qt.resolvedUrl("pages/RoutePage.qml"), { routeId: reply.value })
                else
                    notice.show(reply.message)
            })
        } else {
            core.request({ "cmd": "workouts.importFile", "path": uri }, function(reply) {
                if (!reply.ok)
                    notice.show(reply.message)
                else if (reply.value.kind === "workouts")
                    notice.show(reply.value.names.length === 1 ? qsTr("Added %1").arg(reply.value.names[0])
                                                               : qsTr("Added %1 workouts").arg(reply.value.names.length))
            })
        }
    }

    initialPage: Component { RidePage { } }
    cover: Component { CoverPage { } }
    allowedOrientations: defaultAllowedOrientations
    _defaultPageOrientations: Orientation.All

    // An import that would replace one of the rider's workouts waits for their answer, whichever
    // page started it.
    Connections {
        target: core
        onEventReceived: {
            if (topic === "importQuestion" && payload && payload.names)
                pageStack.push(Qt.resolvedUrl("pages/ImportQuestionPage.qml"), { names: payload.names })
        }
    }

    Connections {
        target: appService
        onUriOpened: window.open(uri)
    }

    InfoNotice { id: notice }
}

import QtQuick 2.6
import Sailfish.Silica 1.0
import "pages"
import "cover"

ApplicationWindow {
    id: window

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
}

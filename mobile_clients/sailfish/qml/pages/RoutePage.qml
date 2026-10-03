import QtQuick 2.6
import Sailfish.Silica 1.0
import harbour.indoorbike 1.0
import "../components"
import "../components/Format.js" as Format

// One route: its shape and its profile, how far and how high, and riding it from the start or from
// where the rider left it.
Page {
    id: page

    property string routeId
    readonly property var routes: core.list("routes")
    readonly property var route: {
        var revision = routes.revision
        return routes.get(routeId)
    }

    function ride(fromStart) {
        if (core.send({ "cmd": "ride.prepareRoute", "id": routeId, "fromStart": fromStart }).ok)
            pageStack.push(Qt.resolvedUrl("RideScreenPage.qml"))
    }

    allowedOrientations: Orientation.All

    SilicaFlickable {
        anchors.fill: parent
        contentHeight: column.height + Theme.paddingLarge

        PullDownMenu {
            MenuItem {
                visible: !!page.route.progressMeters
                text: qsTr("Ride from the start")
                onClicked: page.ride(true)
            }
            MenuItem {
                text: page.route.progressMeters ? qsTr("Carry on") : qsTr("Ride this route")
                onClicked: page.ride(false)
            }
        }

        Column {
            id: column

            width: parent.width
            spacing: Theme.paddingLarge

            PageHeader { title: page.route.name || "" }

            RouteShape {
                anchors.horizontalCenter: parent.horizontalCenter
                width: parent.width - 2 * Theme.horizontalPageMargin
                height: Math.min(width, Theme.itemSizeHuge * 2)
                points: page.route.shape || []
                fraction: page.route.progressMeters ? page.route.progressMeters / page.route.distanceMeters : -1
                color: Theme.secondaryHighlightColor
                riddenColor: Theme.highlightColor
                markerColor: Theme.primaryColor
                lineWidth: Theme.paddingSmall / 2
            }

            ElevationView {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                height: Theme.itemSizeLarge
                profile: page.route.profile || []
                color: Theme.secondaryHighlightColor
            }

            Grid {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                columns: 3

                Reading {
                    width: parent.width / 3
                    label: qsTr("Distance")
                    value: page.route.distanceMeters ? (page.route.distanceMeters / 1000).toFixed(1) : "--"
                    unit: "km"
                }
                Reading {
                    width: parent.width / 3
                    label: qsTr("Climbing")
                    value: page.route.ascentMeters !== undefined ? Math.round(page.route.ascentMeters) : "--"
                    unit: "m"
                }
                Reading {
                    width: parent.width / 3
                    label: qsTr("For you")
                    value: page.route.estimateSeconds ? Format.duration(page.route.estimateSeconds) : "--"
                }
            }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: !!page.route.progressMeters
                wrapMode: Text.Wrap
                color: Theme.highlightColor
                text: page.route.progressMeters
                      ? qsTr("You left off at %1 km. Carry on from there, or start again.").arg((page.route.progressMeters / 1000).toFixed(1))
                      : ""
            }

            ButtonLayout {
                Button {
                    text: page.route.progressMeters ? qsTr("Carry on") : qsTr("Ride this route")
                    onClicked: page.ride(false)
                }
                Button {
                    visible: !!page.route.progressMeters
                    text: qsTr("From the start")
                    onClicked: page.ride(true)
                }
            }
        }

        VerticalScrollDecorator { }
    }
}

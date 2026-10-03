import QtQuick 2.6
import Sailfish.Silica 1.0
import Sailfish.Share 1.0
import harbour.indoorbike 1.0
import "../components"
import "../components/Format.js" as Format

// One saved ride: its numbers at today's FTP and weight, power and heart rate against the plan,
// time in each zone, and taking it elsewhere as FIT or TCX.
Page {
    id: page

    property string rideId
    property var ride: null
    property string error

    readonly property var zones: core.state.zones || []
    readonly property var connections: core.state.connections || ({})
    readonly property var uploads: core.state.uploads || []
    readonly property bool intervalsOn: !!connections.intervals && !!connections.intervals.configured
    readonly property bool fitTrackeeOn: !!connections.fitTrackee && !!connections.fitTrackee.connected

    function upload(cmd) {
        core.request({ "cmd": cmd, "id": rideId }, function(reply) {
            notice.show(reply.ok ? reply.value : reply.message)
        })
    }

    function load() {
        core.request({ "cmd": "history.ride", "id": parseInt(rideId) }, function(reply) {
            if (reply.ok)
                page.ride = reply.value
            else
                page.error = reply.message
        })
    }

    function exportTo(format, dir, then) {
        core.request({ "cmd": "history.export", "id": parseInt(rideId), "format": format, "dir": dir },
                     function(reply) {
                         if (reply.ok)
                             then(reply.value)
                         else
                             page.error = reply.message
                     })
    }

    function share(format) {
        exportTo(format, StandardPaths.cache, function(path) {
            shareAction.mimeType = format === "fit" ? "application/vnd.ant.fit" : "application/vnd.garmin.tcx+xml"
            shareAction.resources = [path]
            shareAction.trigger()
        })
    }

    allowedOrientations: Orientation.All
    Component.onCompleted: load()

    // An upload of this ride was recorded, by a tap here or the automatic one.
    Connections {
        target: core
        onEventReceived: if (topic === "uploads.recorded" && payload && payload.id === page.rideId) page.load()
    }

    ShareAction {
        id: shareAction

        title: qsTr("Share the ride")
    }

    SilicaFlickable {
        anchors.fill: parent
        contentHeight: column.height + Theme.paddingLarge

        PullDownMenu {
            visible: page.ride !== null && page.ride.canExport
            MenuItem {
                text: qsTr("Save as FIT in Downloads")
                onClicked: page.exportTo("fit", StandardPaths.download, function(path) {
                    notice.show(qsTr("Saved %1").arg(path.split("/").pop()))
                })
            }
            MenuItem {
                text: qsTr("Share as TCX")
                onClicked: page.share("tcx")
            }
            MenuItem {
                text: qsTr("Share as FIT")
                onClicked: page.share("fit")
            }
        }

        Column {
            id: column

            width: parent.width
            spacing: Theme.paddingLarge

            PageHeader {
                title: page.ride ? page.ride.name : ""
                description: page.ride ? new Date(page.ride.startMs).toLocaleString(Qt.locale(), Locale.LongFormat) : ""
            }

            BusyLabel {
                running: page.ride === null && page.error.length === 0
                text: qsTr("Loading")
            }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: page.error.length > 0
                wrapMode: Text.Wrap
                color: Theme.errorColor
                text: page.error
            }

            Grid {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: page.ride !== null
                columns: 3
                rowSpacing: Theme.paddingLarge

                Reading {
                    width: parent.width / 3
                    label: qsTr("Time")
                    value: page.ride ? Format.clock(page.ride.durationSeconds) : ""
                }
                Reading {
                    width: parent.width / 3
                    label: qsTr("Average")
                    value: page.ride ? page.ride.avgPower : ""
                    unit: "W"
                }
                Reading {
                    width: parent.width / 3
                    label: qsTr("NP")
                    value: page.ride ? page.ride.normalizedPower : ""
                    unit: "W"
                }
                Reading {
                    width: parent.width / 3
                    label: qsTr("IF")
                    value: page.ride ? page.ride.intensityFactor.toFixed(2) : ""
                }
                Reading {
                    width: parent.width / 3
                    label: qsTr("TSS")
                    value: page.ride ? page.ride.tss : ""
                }
                Reading {
                    width: parent.width / 3
                    label: qsTr("Energy")
                    value: page.ride ? page.ride.kilojoules : ""
                    unit: "kJ"
                }
                Reading {
                    width: parent.width / 3
                    label: qsTr("Distance")
                    value: page.ride && page.ride.distanceMeters > 0 ? (page.ride.distanceMeters / 1000).toFixed(1) : "--"
                    unit: "km"
                }
                Reading {
                    width: parent.width / 3
                    label: qsTr("Cadence")
                    value: page.ride ? page.ride.avgCadence : ""
                    unit: "rpm"
                }
                Reading {
                    width: parent.width / 3
                    label: qsTr("Heart rate")
                    value: page.ride && page.ride.avgHeartRate > 0 ? page.ride.avgHeartRate : "--"
                    unit: "bpm"
                }
            }

            SectionHeader {
                visible: page.ride !== null && page.ride.power.length > 1
                text: qsTr("Power and heart rate")
            }

            RideChart {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                height: Theme.itemSizeHuge * 1.5
                visible: page.ride !== null && page.ride.power.length > 1
                power: page.ride ? page.ride.power : []
                heartRate: page.ride ? page.ride.heartRate : []
                plan: page.ride && page.ride.plan ? page.ride.plan : []
                ftp: page.ride ? page.ride.ftp : 200
                powerColor: Theme.highlightColor
                heartRateColor: Theme.errorColor
                planColor: Theme.secondaryColor
            }

            SectionHeader {
                visible: page.ride !== null && (page.intervalsOn || page.fitTrackeeOn)
                text: qsTr("Uploads")
            }

            UploadItem {
                visible: page.ride !== null && page.intervalsOn
                title: "Intervals.icu"
                uploadedAt: page.ride ? page.ride.intervalsUploadedAt : 0
                autoUpload: !!page.connections.intervals && !!page.connections.intervals.autoUpload
                uploading: page.uploads.indexOf(page.rideId + ":INTERVALS") >= 0
                onUpload: page.upload("upload.intervals")
            }
            UploadItem {
                visible: page.ride !== null && page.fitTrackeeOn
                title: "FitTrackee"
                uploadedAt: page.ride ? page.ride.fitTrackeeUploadedAt : 0
                autoUpload: !!page.connections.fitTrackee && !!page.connections.fitTrackee.autoUpload
                uploading: page.uploads.indexOf(page.rideId + ":FITTRACKEE") >= 0
                onUpload: page.upload("upload.fitTrackee")
            }

            SectionHeader {
                visible: page.ride !== null
                text: qsTr("Time in zone")
            }

            Repeater {
                model: page.ride ? page.ride.timeInZone : []

                Item {
                    readonly property int total: page.ride ? Math.max(1, page.ride.durationSeconds) : 1

                    width: column.width
                    height: Theme.itemSizeExtraSmall
                    visible: modelData > 0

                    Label {
                        id: zoneLabel

                        anchors {
                            left: parent.left
                            leftMargin: Theme.horizontalPageMargin
                            verticalCenter: parent.verticalCenter
                        }
                        width: Theme.itemSizeExtraLarge * 1.6
                        truncationMode: TruncationMode.Fade
                        color: Theme.highlightColor
                        font.pixelSize: Theme.fontSizeSmall
                        text: "Z" + (index + 1) + " " + (page.zones[index] ? page.zones[index].label : "")
                    }
                    Rectangle {
                        anchors {
                            left: zoneLabel.right
                            verticalCenter: parent.verticalCenter
                        }
                        height: Theme.paddingLarge
                        width: (column.width - zoneLabel.width - time.width - 3 * Theme.horizontalPageMargin)
                               * modelData / parent.total
                        radius: height / 4
                        color: Format.zoneColor(page.zones, index + 1, Theme.highlightColor)
                    }
                    Label {
                        id: time

                        anchors {
                            right: parent.right
                            rightMargin: Theme.horizontalPageMargin
                            verticalCenter: parent.verticalCenter
                        }
                        color: Theme.secondaryColor
                        font.pixelSize: Theme.fontSizeSmall
                        text: Format.clock(modelData)
                    }
                }
            }
        }

        VerticalScrollDecorator { }
    }

    InfoNotice { id: notice }
}

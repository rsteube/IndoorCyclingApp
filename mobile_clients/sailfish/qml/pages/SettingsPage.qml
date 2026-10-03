import QtQuick 2.6
import Sailfish.Silica 1.0
import "../components"

// The rider, their devices and their bike, and the way to tell us something is wrong.
Page {
    id: page

    readonly property var settings: core.state.settings || ({})
    readonly property var limits: settings.limits || ({})

    function pair(kind, title, hint) {
        pageStack.push(Qt.resolvedUrl("PairPage.qml"), { kind: kind, title: title, hint: hint })
    }

    allowedOrientations: Orientation.All

    SilicaFlickable {
        anchors.fill: parent
        contentHeight: column.height + Theme.paddingLarge

        PullDownMenu {
            MenuItem {
                text: qsTr("Report a problem")
                onClicked: pageStack.push(Qt.resolvedUrl("ReportPage.qml"))
            }
        }

        Column {
            id: column

            width: parent.width

            PageHeader { title: qsTr("Settings") }

            SectionHeader { text: qsTr("You") }

            NumberField {
                key: "ftp"
                label: qsTr("FTP in watts")
                value: page.settings.ftp || 200
                limits: page.limits.ftp || [50, 600]
            }
            NumberField {
                key: "weightKg"
                label: qsTr("Weight in kg")
                decimals: 1
                value: page.settings.weightKg || 75
                limits: page.limits.weightKg || [30, 250]
            }
            NumberField {
                key: "maxHr"
                label: qsTr("Maximum heart rate")
                value: page.settings.maxHr || 185
                limits: page.limits.maxHr || [100, 230]
            }

            SectionHeader { text: qsTr("Devices") }

            DeviceSetting {
                kind: "trainer"
                pairTitle: qsTr("Pair a trainer")
                pairHint: qsTr("Wake the trainer by turning the pedals. Close other apps that are connected to it.")
            }
            DeviceSetting {
                kind: "hrm"
                pairTitle: qsTr("Pair a heart rate monitor")
                pairHint: qsTr("Put the strap on, or wet its contacts, so it starts sending.")
            }
            DeviceSetting {
                kind: "controller"
                pairTitle: qsTr("Pair a controller")
                pairHint: qsTr("Press a button on the controller so it starts advertising.")
            }

            SectionHeader { text: qsTr("Bike") }

            NumberField {
                key: "bikeWeightKg"
                label: qsTr("Bike weight in kg")
                decimals: 1
                value: page.settings.bikeWeightKg || 9
                limits: page.limits.bikeWeightKg || [4, 30]
            }

            ComboBox {
                id: drivetrain

                width: parent.width
                label: qsTr("Gearing")
                description: qsTr("The virtual gears a route ride shifts through.")
                currentIndex: {
                    var all = page.settings.drivetrains || []
                    for (var i = 0; i < all.length; ++i) {
                        if (all[i].id === page.settings.drivetrainId)
                            return i
                    }
                    return 0
                }
                menu: ContextMenu {
                    Repeater {
                        model: page.settings.drivetrains || []
                        MenuItem {
                            text: modelData.name
                            onClicked: core.send({ "cmd": "settings.set", "key": "drivetrainId", "value": modelData.id })
                        }
                    }
                }
            }

            SectionHeader { text: qsTr("About") }

            DetailItem {
                label: qsTr("Version")
                value: appVersion
            }
            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                wrapMode: Text.Wrap
                color: Theme.secondaryHighlightColor
                font.pixelSize: Theme.fontSizeExtraSmall
                text: qsTr("IndoorBike is free software under the GNU GPL, version 3. It keeps your rides on this phone and sends nothing anywhere unless you ask it to.")
            }
        }

        VerticalScrollDecorator { }
    }
}

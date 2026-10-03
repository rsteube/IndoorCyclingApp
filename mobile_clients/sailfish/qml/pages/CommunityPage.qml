import QtQuick 2.6
import Sailfish.Silica 1.0
import Sailfish.Pickers 1.0

// Getting workouts and routes: from a link or a file, from the repositories the rider follows,
// and from the community registry at indoorbike.app.
Page {
    id: page

    property var registry: []
    property string registryError
    property bool loading: true
    property string message

    readonly property var followed: core.state.followed || ({})

    function loadRegistry() {
        loading = true
        core.request({ "cmd": "repo.registry" }, function(reply) {
            loading = false
            if (reply.ok) {
                page.registry = reply.value.repositories
                page.registryError = reply.value.error || ""
            } else {
                page.registryError = reply.message
            }
        })
    }

    function imported(reply) {
        if (!reply.ok) {
            message = reply.message
        } else if (reply.value.kind === "workouts") {
            message = reply.value.names.length === 1 ? qsTr("Added %1").arg(reply.value.names[0])
                                                     : qsTr("Added %1 workouts").arg(reply.value.names.length)
        } else if (reply.value.kind === "repository") {
            message = qsTr("That is the repository %1. Follow it below to see what it has.").arg(reply.value.name)
        } else {
            message = ""
        }
    }

    function openRepo(url, id, name) {
        core.send({ "cmd": "repo.markSeen", "id": id })
        pageStack.push(Qt.resolvedUrl("RepositoryPage.qml"), { manifestUrl: url, repoId: id, repoName: name })
    }

    allowedOrientations: Orientation.All
    Component.onCompleted: {
        loadRegistry()
        core.send({ "cmd": "repo.refresh", "force": false })
    }

    Component {
        id: filePicker

        FilePickerPage {
            title: qsTr("Choose a workout file")
            nameFilters: ["*.json", "*.JSON"]
            onSelectedContentPropertiesChanged: {
                if (selectedContentProperties.filePath)
                    core.request({ "cmd": "workouts.importFile", "path": selectedContentProperties.filePath }, page.imported)
            }
        }
    }

    SilicaFlickable {
        anchors.fill: parent
        contentHeight: column.height + Theme.paddingLarge

        PullDownMenu {
            busy: page.loading || !!page.followed.refreshing
            MenuItem {
                text: qsTr("Check for new")
                onClicked: {
                    core.send({ "cmd": "repo.refresh", "force": true })
                    page.loadRegistry()
                }
            }
            MenuItem {
                text: qsTr("Import a workout file")
                onClicked: pageStack.push(filePicker)
            }
        }

        Column {
            id: column

            width: parent.width

            PageHeader { title: qsTr("Get workouts and routes") }

            TextField {
                id: link

                width: parent.width
                label: qsTr("Workout link or repository address")
                placeholderText: label
                inputMethodHints: Qt.ImhUrlCharactersOnly | Qt.ImhNoPredictiveText
                EnterKey.enabled: text.length > 0
                EnterKey.iconSource: "image://theme/icon-m-enter-accept"
                EnterKey.onClicked: {
                    var text = link.text.trim()
                    focus = false
                    page.message = qsTr("Reading the link...")
                    if (/manifest\.json$|repo\.json$/i.test(text)) {
                        core.request({ "cmd": "repo.followUrl", "url": text }, function(reply) {
                            page.message = reply.ok ? qsTr("Following %1").arg(reply.value) : reply.message
                        })
                    } else {
                        core.request({ "cmd": "workouts.importLink", "link": text }, page.imported)
                    }
                }
            }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: page.message.length > 0
                wrapMode: Text.Wrap
                color: Theme.highlightColor
                font.pixelSize: Theme.fontSizeSmall
                text: page.message
            }

            SectionHeader {
                visible: (page.followed.repos || []).length > 0
                text: qsTr("Following")
            }

            Repeater {
                model: page.followed.repos || []

                ListItem {
                    contentHeight: Theme.itemSizeMedium
                    onClicked: page.openRepo(modelData.manifestUrl, modelData.id, modelData.name)
                    menu: ContextMenu {
                        MenuItem {
                            text: qsTr("Stop following")
                            onClicked: core.send({ "cmd": "repo.unfollow", "id": modelData.id })
                        }
                    }

                    Column {
                        anchors {
                            left: parent.left
                            leftMargin: Theme.horizontalPageMargin
                            right: parent.right
                            rightMargin: Theme.horizontalPageMargin
                            verticalCenter: parent.verticalCenter
                        }
                        Label {
                            width: parent.width
                            truncationMode: TruncationMode.Fade
                            color: Theme.primaryColor
                            text: modelData.name
                        }
                        Label {
                            width: parent.width
                            color: modelData.newWorkouts + modelData.newRoutes > 0 ? Theme.highlightColor : Theme.secondaryColor
                            font.pixelSize: Theme.fontSizeExtraSmall
                            text: modelData.newWorkouts + modelData.newRoutes > 0
                                  ? qsTr("%1 new").arg(modelData.newWorkouts + modelData.newRoutes)
                                  : (modelData.author || "")
                        }
                    }
                }
            }

            SectionHeader { text: qsTr("Community") }

            BusyLabel {
                running: page.loading
                text: qsTr("Loading")
                height: running ? Theme.itemSizeLarge : 0
            }

            Label {
                x: Theme.horizontalPageMargin
                width: parent.width - 2 * Theme.horizontalPageMargin
                visible: page.registryError.length > 0
                wrapMode: Text.Wrap
                color: Theme.errorColor
                font.pixelSize: Theme.fontSizeSmall
                text: page.registry.length > 0 ? qsTr("Showing the list from last time: %1").arg(page.registryError)
                                               : page.registryError
            }

            Repeater {
                model: page.registry

                ListItem {
                    contentHeight: Math.max(Theme.itemSizeLarge, texts.height + 2 * Theme.paddingMedium)
                    onClicked: page.openRepo(modelData.manifestUrl, modelData.id, modelData.name)
                    menu: ContextMenu {
                        MenuItem {
                            visible: !modelData.followed
                            text: qsTr("Follow")
                            onClicked: {
                                core.send({ "cmd": "repo.follow", "id": modelData.id })
                                page.loadRegistry()
                            }
                        }
                    }

                    Column {
                        id: texts

                        anchors {
                            left: parent.left
                            leftMargin: Theme.horizontalPageMargin
                            right: parent.right
                            rightMargin: Theme.horizontalPageMargin
                            verticalCenter: parent.verticalCenter
                        }
                        Label {
                            width: parent.width
                            truncationMode: TruncationMode.Fade
                            color: Theme.primaryColor
                            text: modelData.name + (modelData.followed ? " ✓" : "")
                        }
                        Label {
                            width: parent.width
                            wrapMode: Text.Wrap
                            maximumLineCount: 3
                            elide: Text.ElideRight
                            color: Theme.secondaryColor
                            font.pixelSize: Theme.fontSizeExtraSmall
                            text: modelData.description || modelData.author || ""
                        }
                    }
                }
            }
        }

        VerticalScrollDecorator { }
    }
}

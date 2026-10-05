import QtQuick 2.6
import Sailfish.Silica 1.0

// The detail a page should have but not lead with: one line saying what is inside, and the rest
// sliding open on a tap. Keeps explanations a tap away instead of a wall above every control.
Column {
    id: root

    property string title
    property bool expanded
    default property alias content: body.data

    // Qt 5.6 does not lay a Column out again when an ancestor that hid it at creation is shown, so
    // the folded part kept a height of 0 and showed a sliver. A change of spacing makes it lay out.
    function relayout() {
        body.spacing = 0
        body.spacing = Theme.paddingMedium
    }

    width: parent ? parent.width : 0
    onVisibleChanged: if (visible) relayout()
    onExpandedChanged: relayout()

    BackgroundItem {
        id: toggle

        width: parent.width
        height: Theme.itemSizeSmall
        onClicked: root.expanded = !root.expanded

        Label {
            anchors {
                left: parent.left
                leftMargin: Theme.horizontalPageMargin
                right: chevron.left
                rightMargin: Theme.paddingMedium
                verticalCenter: parent.verticalCenter
            }
            truncationMode: TruncationMode.Fade
            color: toggle.highlighted ? Theme.highlightColor : Theme.primaryColor
            font.pixelSize: Theme.fontSizeSmall
            text: root.title
        }
        Glyph {
            id: chevron

            anchors {
                right: parent.right
                rightMargin: Theme.horizontalPageMargin
                verticalCenter: parent.verticalCenter
            }
            width: Theme.iconSizeSmall
            height: width
            name: "expand_more"
            color: toggle.highlighted ? Theme.highlightColor : Theme.secondaryColor
            rotation: root.expanded ? 180 : 0

            Behavior on rotation { NumberAnimation { duration: 220; easing.type: Easing.InOutQuad } }
        }
    }

    Item {
        width: parent.width
        height: root.expanded ? body.height + Theme.paddingMedium : 0
        clip: true

        Behavior on height { NumberAnimation { duration: 240; easing.type: Easing.InOutQuad } }

        Column {
            id: body

            width: parent.width
            spacing: Theme.paddingMedium
            opacity: root.expanded ? 1 : 0

            Behavior on opacity { FadeAnimator { duration: 200 } }
        }
    }
}

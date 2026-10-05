import QtQuick 2.6
import Sailfish.Silica 1.0

// One row of a settings list: an icon tile, what it is, how it stands, and where it leads. The
// shape every row in Settings, Connections and Companion shares, so a page reads as a list rather
// than as paragraphs.
ListItem {
    id: row

    property string icon
    property color tint: Theme.highlightColor
    property bool muted
    property bool spinning
    property string title
    property string subtitle
    // Short, on the right: "250 W", "On".
    property string value
    // "ok", "busy", "bad", "off", or "" for no dot.
    property string status
    // A chevron: this row opens a page.
    property bool navigates
    property color subtitleColor: status === "bad" ? Theme.errorColor : Theme.secondaryColor
    // Anything else on the right, such as a button.
    default property alias trailing: trailingSlot.data

    width: parent ? parent.width : 0
    contentHeight: Math.max(Theme.itemSizeMedium, texts.height + 2 * Theme.paddingMedium)

    IconTile {
        id: tile

        anchors {
            left: parent.left
            leftMargin: Theme.horizontalPageMargin
            verticalCenter: parent.verticalCenter
        }
        visible: row.icon.length > 0
        name: row.icon
        tint: row.tint
        muted: row.muted
        spinning: row.spinning
        highlighted: row.highlighted
    }

    Column {
        id: texts

        anchors {
            left: tile.visible ? tile.right : parent.left
            leftMargin: tile.visible ? Theme.paddingLarge : Theme.horizontalPageMargin
            right: endRow.left
            rightMargin: Theme.paddingMedium
            verticalCenter: parent.verticalCenter
        }
        spacing: Theme.paddingSmall / 2

        Row {
            width: parent.width
            spacing: Theme.paddingMedium

            Label {
                id: titleLabel

                width: Math.min(implicitWidth, parent.width - (dot.visible ? dot.width + parent.spacing : 0))
                truncationMode: TruncationMode.Fade
                // Something that only informs reads as a label, not as a control.
                color: row.highlighted || !row.enabled ? Theme.highlightColor : Theme.primaryColor
                text: row.title
            }
            StatusDot {
                id: dot

                anchors.verticalCenter: titleLabel.verticalCenter
                visible: row.status.length > 0
                level: row.status
            }
        }
        Label {
            width: parent.width
            visible: text.length > 0
            wrapMode: Text.Wrap
            maximumLineCount: 3
            elide: Text.ElideRight
            color: row.highlighted ? Theme.secondaryHighlightColor : row.subtitleColor
            font.pixelSize: Theme.fontSizeExtraSmall
            text: row.subtitle
        }
    }

    Row {
        id: endRow

        anchors {
            right: parent.right
            rightMargin: row.navigates ? Theme.paddingMedium : Theme.horizontalPageMargin
            verticalCenter: parent.verticalCenter
        }
        spacing: Theme.paddingSmall

        Label {
            anchors.verticalCenter: parent.verticalCenter
            visible: row.value.length > 0
            color: row.highlighted ? Theme.highlightColor : Theme.secondaryHighlightColor
            font.pixelSize: Theme.fontSizeSmall
            text: row.value
        }
        Item {
            id: trailingSlot

            anchors.verticalCenter: parent.verticalCenter
            width: childrenRect.width
            height: childrenRect.height
        }
        Glyph {
            anchors.verticalCenter: parent.verticalCenter
            visible: row.navigates
            width: Theme.iconSizeSmall
            height: width
            name: "chevron_right"
            color: row.highlighted ? Theme.highlightColor : Theme.secondaryColor
        }
    }
}

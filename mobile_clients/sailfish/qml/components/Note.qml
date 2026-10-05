import QtQuick 2.6
import Sailfish.Silica 1.0

// A paragraph of explanation, inset to the page margins: the one style every page's small print
// shares.
Label {
    property bool emphasised

    x: Theme.horizontalPageMargin
    width: (parent ? parent.width : 0) - 2 * Theme.horizontalPageMargin
    wrapMode: Text.Wrap
    color: emphasised ? Theme.highlightColor : Theme.secondaryHighlightColor
    font.pixelSize: emphasised ? Theme.fontSizeSmall : Theme.fontSizeExtraSmall
    linkColor: Theme.primaryColor
    onLinkActivated: Qt.openUrlExternally(link)
}

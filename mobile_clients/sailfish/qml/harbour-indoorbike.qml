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
}

#ifndef MAPTILES_H
#define MAPTILES_H

#include <QQuickImageProvider>

// Map tiles for QML as image://maptile/<path>: a tile the core has fetched and cached, read from
// that file and put through the Android app's night filter. OpenStreetMap's standard style is a
// bright daylight map, and dropped into a ride screen read in a dim room it is the brightest thing
// there by a wide margin; the filter inverts it, takes most of the colour out, and warms and dims
// it towards the app's palette.
class MapTileProvider : public QQuickImageProvider
{
public:
    MapTileProvider();

    QImage requestImage(const QString &id, QSize *size, const QSize &requestedSize) override;
};

#endif // MAPTILES_H

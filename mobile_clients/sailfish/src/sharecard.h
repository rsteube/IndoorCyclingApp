#ifndef SHARECARD_H
#define SHARECARD_H

#include <QObject>
#include <QVariantMap>

// The 1080 x 1080 picture a ride is shared as, laid out like the Android and iOS cards: date and
// name, three figures, the power chart, the toughest interval, and the app's name at the foot.
// Drawn with QPainter into a PNG, because a QML item can only be grabbed while it is on screen.
class ShareCard : public QObject
{
    Q_OBJECT

public:
    explicit ShareCard(QObject *parent = nullptr);

    // Draws [card] and writes it to [path] as PNG; true when it was written. The card holds the
    // texts as the page shows them ("date", "name", "duration", "avgPower", "tss", "peak"), the
    // chart ("power", "plan", "ftp", "powerColor", "planColor") and, for a planned ride,
    // "highlight" ({"zone", "label", "seconds", "pct", "color"}).
    Q_INVOKABLE bool render(const QVariantMap &card, const QString &path) const;

    // Copies [source] into the IndoorBike album in Pictures as [name], where Gallery shows it and
    // any app's own picker finds it; an existing file of that name is kept, and this one gets a
    // number. The path it was saved to, or empty when it could not be.
    Q_INVOKABLE QString saveToPictures(const QString &source, const QString &name) const;
};

#endif // SHARECARD_H

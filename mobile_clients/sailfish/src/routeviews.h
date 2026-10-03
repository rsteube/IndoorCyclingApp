#ifndef ROUTEVIEWS_H
#define ROUTEVIEWS_H

#include <QColor>
#include <QQuickPaintedItem>
#include <QVariantList>
#include <QVariantMap>

// The two drawings a route ride shows, painted in C++: a QML Canvas loses its picture when Sailfish
// releases the window's graphics in the background, and these must survive a rider glancing at
// another app mid-ride.

// The road's shape from above, from its [latitude, longitude] points, with the part already ridden
// and where the rider is. Projected with the latitude's cosine, which keeps a ride-sized road true
// to shape without a map.
class RouteShape : public QQuickPaintedItem
{
    Q_OBJECT
    Q_PROPERTY(QVariantList points READ points WRITE setPoints NOTIFY changed)
    // 0..1 along the road; negative for no marker.
    Q_PROPERTY(qreal fraction READ fraction WRITE setFraction NOTIFY changed)
    Q_PROPERTY(QColor color READ color WRITE setColor NOTIFY changed)
    Q_PROPERTY(QColor riddenColor READ riddenColor WRITE setRiddenColor NOTIFY changed)
    Q_PROPERTY(QColor markerColor READ markerColor WRITE setMarkerColor NOTIFY changed)
    Q_PROPERTY(qreal lineWidth READ lineWidth WRITE setLineWidth NOTIFY changed)

public:
    explicit RouteShape(QQuickItem *parent = nullptr);
    void paint(QPainter *painter) override;

    QVariantList points() const { return m_points; }
    void setPoints(const QVariantList &points);
    qreal fraction() const { return m_fraction; }
    void setFraction(qreal fraction);
    QColor color() const { return m_color; }
    void setColor(const QColor &color);
    QColor riddenColor() const { return m_riddenColor; }
    void setRiddenColor(const QColor &color);
    QColor markerColor() const { return m_markerColor; }
    void setMarkerColor(const QColor &color);
    qreal lineWidth() const { return m_lineWidth; }
    void setLineWidth(qreal width);

signals:
    void changed();

private:
    QVariantList m_points;
    qreal m_fraction = -1;
    QColor m_color = Qt::gray;
    QColor m_riddenColor = Qt::white;
    QColor m_markerColor = Qt::white;
    qreal m_lineWidth = 3;
};

// The road's height along its length, each stretch in its gradient band's colour, with where the
// rider is.
class ElevationView : public QQuickPaintedItem
{
    Q_OBJECT
    // Heights scaled 0..1, evenly along the road.
    Q_PROPERTY(QVariantList profile READ profile WRITE setProfile NOTIFY changed)
    // A gradient band name per sample (shared/route/RouteFormat's GradientBand), or none.
    Q_PROPERTY(QVariantList bands READ bands WRITE setBands NOTIFY changed)
    // The band names' colours.
    Q_PROPERTY(QVariantMap bandColors READ bandColors WRITE setBandColors NOTIFY changed)
    Q_PROPERTY(qreal fraction READ fraction WRITE setFraction NOTIFY changed)
    Q_PROPERTY(QColor color READ color WRITE setColor NOTIFY changed)
    Q_PROPERTY(QColor markerColor READ markerColor WRITE setMarkerColor NOTIFY changed)

public:
    explicit ElevationView(QQuickItem *parent = nullptr);
    void paint(QPainter *painter) override;

    QVariantList profile() const { return m_profile; }
    void setProfile(const QVariantList &profile);
    QVariantList bands() const { return m_bands; }
    void setBands(const QVariantList &bands);
    QVariantMap bandColors() const { return m_bandColors; }
    void setBandColors(const QVariantMap &colors);
    qreal fraction() const { return m_fraction; }
    void setFraction(qreal fraction);
    QColor color() const { return m_color; }
    void setColor(const QColor &color);
    QColor markerColor() const { return m_markerColor; }
    void setMarkerColor(const QColor &color);

signals:
    void changed();

private:
    QVariantList m_profile;
    QVariantList m_bands;
    QVariantMap m_bandColors;
    qreal m_fraction = -1;
    QColor m_color = Qt::gray;
    QColor m_markerColor = Qt::white;
};

#endif // ROUTEVIEWS_H

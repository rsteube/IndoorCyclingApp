#ifndef RIDECHART_H
#define RIDECHART_H

#include <QColor>
#include <QQuickPaintedItem>
#include <QVariantList>

// A ride's power over time as a filled area, the plan it rode as a stepped line, and its heart
// rate as a thin line on a scale of its own. Painted in C++ because a QML Canvas loses its picture
// when Sailfish releases the window's graphics in the background, and does not repaint on return.
class RideChart : public QQuickPaintedItem
{
    Q_OBJECT
    // Watts, evenly spread over the ride.
    Q_PROPERTY(QVariantList power READ power WRITE setPower NOTIFY changed)
    // Beats per minute, the same way; empty for none.
    Q_PROPERTY(QVariantList heartRate READ heartRate WRITE setHeartRate NOTIFY changed)
    // The workout's segments, as the core encodes them ({"seconds", "pct"}), and the FTP they are
    // shares of; empty for a ride with no plan.
    Q_PROPERTY(QVariantList plan READ plan WRITE setPlan NOTIFY changed)
    Q_PROPERTY(int ftp READ ftp WRITE setFtp NOTIFY changed)
    Q_PROPERTY(QColor powerColor READ powerColor WRITE setPowerColor NOTIFY changed)
    Q_PROPERTY(QColor heartRateColor READ heartRateColor WRITE setHeartRateColor NOTIFY changed)
    Q_PROPERTY(QColor planColor READ planColor WRITE setPlanColor NOTIFY changed)

public:
    explicit RideChart(QQuickItem *parent = nullptr);

    void paint(QPainter *painter) override;

    // The chart into [rect], for a picture as well as for this item. [lineScale] thickens the
    // lines for a picture drawn larger than the screen.
    static void draw(QPainter *painter, const QRectF &rect, const QVariantList &power,
                     const QVariantList &heartRate, const QVariantList &plan, int ftp, const QColor &powerColor,
                     const QColor &heartRateColor, const QColor &planColor, qreal lineScale = 1);

    QVariantList power() const { return m_power; }
    void setPower(const QVariantList &values);
    QVariantList heartRate() const { return m_heartRate; }
    void setHeartRate(const QVariantList &values);
    QVariantList plan() const { return m_plan; }
    void setPlan(const QVariantList &segments);
    int ftp() const { return m_ftp; }
    void setFtp(int ftp);
    QColor powerColor() const { return m_powerColor; }
    void setPowerColor(const QColor &color);
    QColor heartRateColor() const { return m_heartRateColor; }
    void setHeartRateColor(const QColor &color);
    QColor planColor() const { return m_planColor; }
    void setPlanColor(const QColor &color);

signals:
    void changed();

private:
    void touch();

    QVariantList m_power;
    QVariantList m_heartRate;
    QVariantList m_plan;
    int m_ftp = 200;
    QColor m_powerColor = Qt::white;
    QColor m_heartRateColor = Qt::red;
    QColor m_planColor = Qt::gray;
};

#endif // RIDECHART_H

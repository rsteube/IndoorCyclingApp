#include "routeviews.h"

#include <QPainter>
#include <QPainterPath>
#include <QtMath>

#include <algorithm>

// --- RouteShape -----------------------------------------------------------------------------

RouteShape::RouteShape(QQuickItem *parent)
    : QQuickPaintedItem(parent)
{
    setAntialiasing(true);
}

void RouteShape::setPoints(const QVariantList &points)
{
    m_points = points;
    emit changed();
    update();
}

void RouteShape::setFraction(qreal fraction)
{
    if (qFuzzyCompare(fraction, m_fraction))
        return;
    m_fraction = fraction;
    emit changed();
    update();
}

void RouteShape::setColor(const QColor &color)
{
    m_color = color;
    emit changed();
    update();
}

void RouteShape::setRiddenColor(const QColor &color)
{
    m_riddenColor = color;
    emit changed();
    update();
}

void RouteShape::setMarkerColor(const QColor &color)
{
    m_markerColor = color;
    emit changed();
    update();
}

void RouteShape::setLineWidth(qreal width)
{
    m_lineWidth = width;
    emit changed();
    update();
}

void RouteShape::paint(QPainter *painter)
{
    const int n = m_points.size();
    if (n < 2 || width() <= 0 || height() <= 0)
        return;

    // Project: longitude scaled by the cosine of the middle latitude, latitude up.
    QVector<QPointF> raw;
    raw.reserve(n);
    double minLat = 90, maxLat = -90;
    for (const QVariant &v : m_points) {
        const QVariantList p = v.toList();
        if (p.size() < 2)
            continue;
        minLat = std::min(minLat, p.at(0).toDouble());
        maxLat = std::max(maxLat, p.at(0).toDouble());
    }
    const double k = qCos(qDegreesToRadians((minLat + maxLat) / 2));
    double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
    for (const QVariant &v : m_points) {
        const QVariantList p = v.toList();
        if (p.size() < 2)
            continue;
        const QPointF q(p.at(1).toDouble() * k, -p.at(0).toDouble());
        raw.append(q);
        minX = std::min(minX, q.x());
        maxX = std::max(maxX, q.x());
        minY = std::min(minY, q.y());
        maxY = std::max(maxY, q.y());
    }
    if (raw.size() < 2)
        return;
    const double margin = m_lineWidth * 2;
    const double spanX = std::max(1e-9, maxX - minX);
    const double spanY = std::max(1e-9, maxY - minY);
    const double scale = std::min((width() - 2 * margin) / spanX, (height() - 2 * margin) / spanY);
    const double offX = (width() - spanX * scale) / 2;
    const double offY = (height() - spanY * scale) / 2;
    QVector<QPointF> pts;
    pts.reserve(raw.size());
    for (const QPointF &q : raw)
        pts.append(QPointF(offX + (q.x() - minX) * scale, offY + (q.y() - minY) * scale));

    QPen pen(m_color, m_lineWidth, Qt::SolidLine, Qt::RoundCap, Qt::RoundJoin);
    painter->setPen(pen);
    painter->drawPolyline(pts.constData(), pts.size());

    if (m_fraction < 0)
        return;
    const double at = std::min(1.0, std::max(0.0, double(m_fraction))) * (pts.size() - 1);
    const int whole = int(at);
    QVector<QPointF> ridden(pts.mid(0, whole + 1));
    QPointF marker = pts.at(whole);
    if (whole + 1 < pts.size()) {
        const double t = at - whole;
        marker = pts.at(whole) + (pts.at(whole + 1) - pts.at(whole)) * t;
        ridden.append(marker);
    }
    pen.setColor(m_riddenColor);
    painter->setPen(pen);
    painter->drawPolyline(ridden.constData(), ridden.size());

    painter->setPen(Qt::NoPen);
    painter->setBrush(m_markerColor);
    painter->drawEllipse(marker, m_lineWidth * 2.5, m_lineWidth * 2.5);
}

// --- ElevationView --------------------------------------------------------------------------

ElevationView::ElevationView(QQuickItem *parent)
    : QQuickPaintedItem(parent)
{
    setAntialiasing(true);
}

void ElevationView::setProfile(const QVariantList &profile)
{
    m_profile = profile;
    emit changed();
    update();
}

void ElevationView::setBands(const QVariantList &bands)
{
    m_bands = bands;
    emit changed();
    update();
}

void ElevationView::setBandColors(const QVariantMap &colors)
{
    m_bandColors = colors;
    emit changed();
    update();
}

void ElevationView::setFraction(qreal fraction)
{
    if (qFuzzyCompare(fraction, m_fraction))
        return;
    m_fraction = fraction;
    emit changed();
    update();
}

void ElevationView::setColor(const QColor &color)
{
    m_color = color;
    emit changed();
    update();
}

void ElevationView::setMarkerColor(const QColor &color)
{
    m_markerColor = color;
    emit changed();
    update();
}

void ElevationView::paint(QPainter *painter)
{
    const int n = m_profile.size();
    const qreal w = width();
    const qreal h = height();
    if (n < 2 || w <= 0 || h <= 0)
        return;
    // The lowest point sits a little above the floor, so a flat road still shows as a road.
    auto y = [&](int i) { return h - (0.1 * h + 0.85 * h * m_profile.at(i).toDouble()); };

    // Each stretch as its own column, in its band's colour.
    for (int i = 0; i < n - 1; ++i) {
        QColor fill = m_color;
        if (i < m_bands.size()) {
            const QVariant c = m_bandColors.value(m_bands.at(i).toString());
            if (c.isValid())
                fill = c.value<QColor>();
        }
        QPainterPath column;
        const qreal x0 = w * i / (n - 1);
        const qreal x1 = w * (i + 1) / (n - 1);
        column.moveTo(x0, h);
        column.lineTo(x0, y(i));
        column.lineTo(x1, y(i + 1));
        column.lineTo(x1, h);
        column.closeSubpath();
        painter->fillPath(column, fill);
    }

    if (m_fraction < 0)
        return;
    const qreal x = w * std::min(1.0, std::max(0.0, double(m_fraction)));
    const double at = std::min(1.0, std::max(0.0, double(m_fraction))) * (n - 1);
    const int i = std::min(n - 2, int(at));
    const qreal markerY = y(i) + (y(i + 1) - y(i)) * (at - i);
    painter->setPen(QPen(m_markerColor, 2));
    painter->drawLine(QPointF(x, 0), QPointF(x, h));
    painter->setPen(Qt::NoPen);
    painter->setBrush(m_markerColor);
    painter->drawEllipse(QPointF(x, markerY), 6, 6);
}

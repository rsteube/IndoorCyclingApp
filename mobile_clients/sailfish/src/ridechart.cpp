#include "ridechart.h"

#include <QPainter>
#include <QPainterPath>

#include <algorithm>

RideChart::RideChart(QQuickItem *parent)
    : QQuickPaintedItem(parent)
{
    setAntialiasing(true);
}

void RideChart::touch()
{
    emit changed();
    update();
}

void RideChart::setPower(const QVariantList &values)
{
    m_power = values;
    touch();
}

void RideChart::setHeartRate(const QVariantList &values)
{
    m_heartRate = values;
    touch();
}

void RideChart::setPlan(const QVariantList &segments)
{
    m_plan = segments;
    touch();
}

void RideChart::setFtp(int ftp)
{
    m_ftp = ftp;
    touch();
}

void RideChart::setPowerColor(const QColor &color)
{
    m_powerColor = color;
    touch();
}

void RideChart::setHeartRateColor(const QColor &color)
{
    m_heartRateColor = color;
    touch();
}

void RideChart::setPlanColor(const QColor &color)
{
    m_planColor = color;
    touch();
}

void RideChart::paint(QPainter *painter)
{
    draw(painter, QRectF(0, 0, width(), height()), m_power, m_heartRate, m_plan, m_ftp, m_powerColor,
         m_heartRateColor, m_planColor);
}

void RideChart::draw(QPainter *painter, const QRectF &rect, const QVariantList &power,
                     const QVariantList &heartRate, const QVariantList &plan, int ftp,
                     const QColor &powerColor, const QColor &heartRateColor, const QColor &planColor,
                     qreal lineScale)
{
    const qreal w = rect.width();
    const qreal h = rect.height();
    if (w <= 0 || h <= 0)
        return;
    painter->save();
    painter->translate(rect.topLeft());
    painter->setRenderHint(QPainter::Antialiasing);

    // One scale for power and the plan, with headroom above the higher of the two.
    double top = 100;
    for (const QVariant &v : power)
        top = std::max(top, v.toDouble());
    int planSeconds = 0;
    for (const QVariant &s : plan) {
        const QVariantMap segment = s.toMap();
        planSeconds += segment.value(QStringLiteral("seconds")).toInt();
        top = std::max(top, segment.value(QStringLiteral("pct")).toDouble() / 100.0 * ftp);
    }
    top *= 1.1;

    if (power.size() > 1) {
        QPainterPath area;
        area.moveTo(0, h);
        const int n = power.size();
        for (int i = 0; i < n; ++i)
            area.lineTo(w * i / (n - 1), h - h * power.at(i).toDouble() / top);
        area.lineTo(w, h);
        area.closeSubpath();
        QColor fill = powerColor;
        fill.setAlphaF(0.35);
        painter->fillPath(area, fill);
        QPen pen(powerColor, 2 * lineScale);
        painter->setPen(pen);
        painter->setBrush(Qt::NoBrush);
        QPainterPath line;
        for (int i = 0; i < n; ++i) {
            const QPointF p(w * i / (n - 1), h - h * power.at(i).toDouble() / top);
            if (i == 0)
                line.moveTo(p);
            else
                line.lineTo(p);
        }
        painter->drawPath(line);
    }

    // The plan, stepped, over the same time as the ride.
    if (planSeconds > 0) {
        QPen pen(planColor, 2 * lineScale, Qt::DashLine);
        painter->setPen(pen);
        QPainterPath steps;
        int at = 0;
        bool first = true;
        for (const QVariant &s : plan) {
            const QVariantMap segment = s.toMap();
            const double y = h - h * segment.value(QStringLiteral("pct")).toDouble() / 100.0 * ftp / top;
            const double x0 = w * at / planSeconds;
            at += segment.value(QStringLiteral("seconds")).toInt();
            const double x1 = w * at / planSeconds;
            if (first) {
                steps.moveTo(x0, y);
                first = false;
            } else {
                steps.lineTo(x0, y);
            }
            steps.lineTo(x1, y);
        }
        painter->drawPath(steps);
    }

    // Heart rate on a scale of its own, so it reads as a shape rather than squashed under power.
    double hrLow = 1000;
    double hrHigh = 0;
    for (const QVariant &v : heartRate) {
        const double bpm = v.toDouble();
        if (bpm <= 0)
            continue;
        hrLow = std::min(hrLow, bpm);
        hrHigh = std::max(hrHigh, bpm);
    }
    if (heartRate.size() > 1 && hrHigh > 0) {
        const double span = std::max(10.0, hrHigh - hrLow);
        QPen pen(heartRateColor, 1.5 * lineScale);
        painter->setPen(pen);
        QPainterPath line;
        bool drawing = false;
        const int n = heartRate.size();
        for (int i = 0; i < n; ++i) {
            const double bpm = heartRate.at(i).toDouble();
            if (bpm <= 0) {
                drawing = false;
                continue;
            }
            const QPointF p(w * i / (n - 1), h * 0.9 - h * 0.8 * (bpm - hrLow) / span);
            if (drawing)
                line.lineTo(p);
            else
                line.moveTo(p);
            drawing = true;
        }
        painter->drawPath(line);
    }
    painter->restore();
}

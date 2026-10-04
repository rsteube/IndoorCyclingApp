#include "sharecard.h"

#include <QColor>
#include <QDebug>
#include <QFileInfo>
#include <QFont>
#include <QFontMetricsF>
#include <QGuiApplication>
#include <QImage>
#include <QPainter>

#include "ridechart.h"

#include <algorithm>

namespace {

const int Size = 1080;
const int Margin = 64;
const QColor Background(0x14, 0x16, 0x1A);
const QColor Primary(Qt::white);
const QColor Secondary(0x9A, 0xA0, 0xA6);

QFont font(int pixelSize, bool bold)
{
    QFont f = QGuiApplication::font();
    f.setPixelSize(pixelSize);
    f.setWeight(bold ? QFont::Bold : QFont::DemiBold);
    return f;
}

// A figure: its value large and its name small under it, from the top left of [at].
void figure(QPainter &p, const QPointF &at, const QString &value, const QString &label)
{
    p.setFont(font(56, true));
    p.setPen(Primary);
    const QFontMetricsF big(p.font());
    p.drawText(QPointF(at.x(), at.y() + big.ascent()), value);
    p.setFont(font(24, false));
    p.setPen(Secondary);
    const QFontMetricsF small(p.font());
    p.drawText(QPointF(at.x(), at.y() + big.height() + 4 + small.ascent()), label.toUpper());
}

QString clock(int seconds)
{
    return seconds >= 3600
        ? QStringLiteral("%1:%2:%3").arg(seconds / 3600).arg(seconds / 60 % 60, 2, 10, QLatin1Char('0'))
              .arg(seconds % 60, 2, 10, QLatin1Char('0'))
        : QStringLiteral("%1:%2").arg(seconds / 60).arg(seconds % 60, 2, 10, QLatin1Char('0'));
}

} // namespace

ShareCard::ShareCard(QObject *parent)
    : QObject(parent)
{
}

bool ShareCard::render(const QVariantMap &card, const QString &path) const
{
    QImage image(Size, Size, QImage::Format_ARGB32_Premultiplied);
    image.fill(Background);
    QPainter p(&image);
    p.setRenderHint(QPainter::Antialiasing);
    p.setRenderHint(QPainter::TextAntialiasing);

    const qreal width = Size - 2 * Margin;
    qreal y = Margin;

    // The date, then the ride's name over at most two lines.
    p.setFont(font(30, false));
    p.setPen(Secondary);
    p.drawText(QRectF(Margin, y, width, 40), Qt::AlignLeft | Qt::AlignVCenter,
               card.value(QStringLiteral("date")).toString().toUpper());
    y += 48;
    p.setFont(font(64, true));
    p.setPen(Primary);
    const QFontMetricsF nameMetrics(p.font());
    const QRectF nameBox(Margin, y, width, nameMetrics.lineSpacing() * 2);
    const QRectF nameUsed = p.boundingRect(nameBox, Qt::TextWordWrap, card.value(QStringLiteral("name")).toString());
    p.drawText(nameBox, Qt::AlignLeft | Qt::AlignTop | Qt::TextWordWrap, card.value(QStringLiteral("name")).toString());
    y += std::min(nameUsed.height(), nameBox.height()) + 36;

    // Three figures across.
    const qreal third = width / 3;
    figure(p, QPointF(Margin, y), card.value(QStringLiteral("duration")).toString(), QStringLiteral("Duration"));
    figure(p, QPointF(Margin + third, y), QStringLiteral("%1 W").arg(card.value(QStringLiteral("avgPower")).toInt()),
           QStringLiteral("Avg power"));
    figure(p, QPointF(Margin + 2 * third, y), card.value(QStringLiteral("tss")).toString(), QStringLiteral("TSS"));
    y += 120;

    // From the foot up: the app's name, then the toughest interval, and the chart takes the rest.
    qreal bottom = Size - Margin;
    p.setFont(font(30, true));
    p.setPen(Primary);
    const QString wordmark = QStringLiteral("Indoor Bike");
    p.drawText(QRectF(Margin, bottom - 40, width, 40), Qt::AlignLeft | Qt::AlignVCenter, wordmark);
    // A ride a companion drove credits the game, as Android's card does: "with", its icon, its name.
    const QString brand = card.value(QStringLiteral("brandName")).toString();
    if (!brand.isEmpty()) {
        qreal x = Margin + QFontMetricsF(p.font()).width(wordmark) + 20;
        p.setFont(font(26, false));
        p.setPen(Secondary);
        const QString with = QStringLiteral("with");
        p.drawText(QRectF(x, bottom - 40, width, 40), Qt::AlignLeft | Qt::AlignVCenter, with);
        x += QFontMetricsF(p.font()).width(with) + 14;
        const QImage icon(card.value(QStringLiteral("brandIcon")).toString());
        if (!icon.isNull()) {
            p.drawImage(QRectF(x, bottom - 40, 40, 40), icon);
            x += 40 + 12;
        }
        p.setFont(font(30, true));
        p.setPen(Primary);
        p.drawText(QRectF(x, bottom - 40, Size - Margin - x - 260, 40), Qt::AlignLeft | Qt::AlignVCenter,
                   QFontMetricsF(p.font()).elidedText(brand, Qt::ElideRight, Size - Margin - x - 260));
    }
    p.setFont(font(26, false));
    p.setPen(Secondary);
    p.drawText(QRectF(Margin, bottom - 40, width, 40), Qt::AlignRight | Qt::AlignVCenter, QStringLiteral("indoorbike.app"));
    bottom -= 40 + 36;

    const QVariantMap highlight = card.value(QStringLiteral("highlight")).toMap();
    if (!highlight.isEmpty()) {
        const qreal top = bottom - 96;
        p.setPen(Qt::NoPen);
        p.setBrush(QColor(highlight.value(QStringLiteral("color")).toString()));
        p.drawRoundedRect(QRectF(Margin, top + 8, 12, 80), 6, 6);
        p.setFont(font(22, false));
        p.setPen(Secondary);
        p.drawText(QRectF(Margin + 32, top + 4, width / 2, 30), Qt::AlignLeft | Qt::AlignVCenter,
                   QStringLiteral("TOUGHEST INTERVAL"));
        p.setFont(font(40, true));
        p.setPen(Primary);
        p.drawText(QRectF(Margin + 32, top + 36, width / 2, 56), Qt::AlignLeft | Qt::AlignVCenter,
                   QStringLiteral("Z%1 · %2").arg(highlight.value(QStringLiteral("zone")).toInt())
                       .arg(clock(highlight.value(QStringLiteral("seconds")).toInt())));
        figure(p, QPointF(Margin + width * 0.55, top + 4), QStringLiteral("%1%").arg(highlight.value(QStringLiteral("pct")).toInt()),
               QStringLiteral("% FTP"));
        figure(p, QPointF(Margin + width * 0.78, top + 4), highlight.value(QStringLiteral("label")).toString(),
               QStringLiteral("Zone"));
        bottom = top - 36;
    }

    // The chart: its heading and peak, then power against the plan.
    p.setFont(font(30, false));
    p.setPen(Primary);
    p.drawText(QRectF(Margin, y, width, 40), Qt::AlignLeft | Qt::AlignVCenter, QStringLiteral("Power"));
    p.setFont(font(26, false));
    p.setPen(Secondary);
    p.drawText(QRectF(Margin, y, width, 40), Qt::AlignRight | Qt::AlignVCenter,
               QStringLiteral("PEAK %1 W").arg(card.value(QStringLiteral("peak")).toInt()));
    y += 52;
    RideChart::draw(&p, QRectF(Margin, y, width, std::max<qreal>(80, bottom - y)),
                    card.value(QStringLiteral("power")).toList(), QVariantList(),
                    card.value(QStringLiteral("plan")).toList(), card.value(QStringLiteral("ftp")).toInt(),
                    QColor(card.value(QStringLiteral("powerColor")).toString()), QColor(),
                    QColor(card.value(QStringLiteral("planColor")).toString()), 2.5);
    p.end();

    if (!image.save(path, "PNG")) {
        qWarning() << "Share card: could not write" << path;
        return false;
    }
    return QFileInfo(path).size() > 0;
}

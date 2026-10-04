#include "maptiles.h"

#include <QColor>
#include <QImage>

#include <algorithm>

namespace {

// Android's nightTileFilter, as one matrix: invert, then saturation 0.30 (its luminance weights),
// then warm and dim. Rows are red, green and blue; the last column is the offset in 0..255.
struct Matrix {
    double m[3][4];
};

Matrix nightFilter()
{
    const double sat = 0.30;
    const double inv = 1.0 - sat;
    const double r = 0.213 * inv, g = 0.715 * inv, b = 0.072 * inv;
    const double desaturate[3][3] = { { r + sat, g, b }, { r, g + sat, b }, { r, g, b + sat } };
    const double warm[3][3] = { { 0.86, 0, 0 }, { 0, 0.80, 0 }, { 0, 0, 0.68 } };
    const double warmOffset[3] = { 4, 2, 0 };

    // With x inverted to 255 - x first: warm(desaturate(255 - x)) + offset
    //   = -(warm . desaturate) x + (warm . desaturate) 255 + offset.
    Matrix out = {};
    for (int row = 0; row < 3; ++row) {
        double constant = 0;
        for (int j = 0; j < 3; ++j) {
            double product = 0;
            for (int k = 0; k < 3; ++k)
                product += warm[row][k] * desaturate[k][j];
            out.m[row][j] = -product;
            constant += product * 255.0;
        }
        out.m[row][3] = constant + warmOffset[row];
    }
    return out;
}

int clamp(double v)
{
    return std::max(0, std::min(255, int(v + 0.5)));
}

} // namespace

MapTileProvider::MapTileProvider()
    : QQuickImageProvider(QQuickImageProvider::Image, QQmlImageProviderBase::ForceAsynchronousImageLoading)
{
}

QImage MapTileProvider::requestImage(const QString &id, QSize *size, const QSize &requestedSize)
{
    static const Matrix filter = nightFilter();

    QImage image(id.startsWith(QLatin1Char('/')) ? id : QLatin1Char('/') + id);
    if (image.isNull())
        return image;
    image = image.convertToFormat(QImage::Format_RGB32);
    for (int y = 0; y < image.height(); ++y) {
        QRgb *line = reinterpret_cast<QRgb *>(image.scanLine(y));
        for (int x = 0; x < image.width(); ++x) {
            const double in[3] = { double(qRed(line[x])), double(qGreen(line[x])), double(qBlue(line[x])) };
            int out[3];
            for (int row = 0; row < 3; ++row)
                out[row] = clamp(filter.m[row][0] * in[0] + filter.m[row][1] * in[1] + filter.m[row][2] * in[2]
                                 + filter.m[row][3]);
            line[x] = qRgb(out[0], out[1], out[2]);
        }
    }
    if (requestedSize.isValid())
        image = image.scaled(requestedSize, Qt::IgnoreAspectRatio, Qt::SmoothTransformation);
    if (size)
        *size = image.size();
    return image;
}

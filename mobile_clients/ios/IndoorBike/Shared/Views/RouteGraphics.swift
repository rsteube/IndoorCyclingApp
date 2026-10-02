import SwiftUI
import shared

/// The colour of a gradient band, as the Android app draws it: blues going
/// down, grey on the flat, then green, yellow and on to red as it steepens.
extension GradientBand {
    var color: Color {
        switch self {
        case .steepdescent: Color(hex: 0x6FD1E8)
        case .descent: Color(hex: 0x4FA3D1)
        case .flat: .secondary
        case .gentle: Zone.z3.color
        case .moderate: Zone.z4.color
        case .hard: .primary
        case .veryhard: .primary.opacity(0.8)
        default: Color(hex: 0xEB3B3B)
        }
    }
}

func gradientColor(_ gradient: Double) -> Color { RouteFormatKt.gradientBand(gradient: gradient).color }

/// Web Mercator, so a road looks on the card as it does on a map.
private func mercator(_ latitude: Double, _ longitude: Double) -> CGPoint {
    let lat = min(max(latitude, -85), 85) * .pi / 180
    return CGPoint(x: longitude * .pi / 180, y: log(tan(.pi / 4 + lat / 2)))
}

/// Fit projected points into a rectangle with one scale for both axes, centred.
private func fitted(_ points: [CGPoint], in size: CGSize, inset: CGFloat) -> [CGPoint] {
    guard let first = points.first else { return [] }
    var minX = first.x
    var maxX = first.x
    var minY = first.y
    var maxY = first.y
    for p in points {
        minX = min(minX, p.x)
        maxX = max(maxX, p.x)
        minY = min(minY, p.y)
        maxY = max(maxY, p.y)
    }
    let w = max(maxX - minX, 1e-9)
    let h = max(maxY - minY, 1e-9)
    let scale = min((size.width - inset * 2) / w, (size.height - inset * 2) / h)
    let offsetX = (size.width - w * scale) / 2
    let offsetY = (size.height - h * scale) / 2
    return points.map {
        CGPoint(x: offsetX + ($0.x - minX) * scale, y: size.height - (offsetY + ($0.y - minY) * scale))
    }
}

/// A route's outline without a map: the whole road faint, what was ridden on
/// an earlier sitting fainter still, and what was ridden now bright.
struct RouteShapeView: View {
    let latLons: [(Double, Double)]
    var riddenFraction: Double = 0
    var startedFraction: Double = 0
    var accent: Color = .primary
    var lineWidth: CGFloat = 3

    var body: some View {
        Canvas { context, size in
            let points = fitted(latLons.map { mercator($0.0, $0.1) }, in: size, inset: lineWidth * 2)
            guard points.count > 1 else { return }
            func path(_ slice: ArraySlice<CGPoint>) -> Path {
                var p = Path()
                p.addLines(Array(slice))
                return p
            }
            let style = StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round)
            context.stroke(path(points[...]), with: .color(.secondary.opacity(0.4)), style: style)
            let started = Int(Double(points.count - 1) * startedFraction)
            let ridden = Int(Double(points.count - 1) * riddenFraction)
            if started > 0 {
                context.stroke(
                    path(points[0 ... min(started, points.count - 1)]),
                    with: .color(accent.opacity(0.35)),
                    style: style
                )
            }
            if ridden > started {
                context.stroke(
                    path(points[started ... min(ridden, points.count - 1)]),
                    with: .color(accent),
                    style: style
                )
            }
            // Where it starts, as a ring.
            let start = points[0]
            context.stroke(
                Path(ellipseIn: CGRect(x: start.x - 4, y: start.y - 4, width: 8, height: 8)),
                with: .color(accent),
                lineWidth: 2
            )
        }
        .accessibilityHidden(true)
    }
}

extension RouteTrack {
    /// Up to [count] of the route's points, evenly picked, for drawing its shape.
    func latLons(count: Int = 400) -> [(Double, Double)] {
        let all = points
        guard all.count > count else { return all.map { ($0.latitude, $0.longitude) } }
        return (0 ..< count).map { i in
            let p = all[i * (all.count - 1) / (count - 1)]
            return (p.latitude, p.longitude)
        }
    }
}

extension RouteEntry {
    /// The listing's encoded outline, decoded.
    var shapeLatLons: [(Double, Double)] {
        guard let shape else { return [] }
        return RoutePolyline.shared.decode(encoded: shape).map { pair in
            ((pair.first as? NSNumber)?.doubleValue ?? 0, (pair.second as? NSNumber)?.doubleValue ?? 0)
        }
    }
}

/// A route's height along its length: one column per step, coloured by the
/// gradient there, brighter where the rider has been, with a marker where
/// they are.
struct ElevationProfileView: View {
    let route: RouteTrack
    var distanceMeters: Double = 0
    var startedAtMeters: Double = 0
    var showRider = true

    var body: some View {
        Canvas { context, size in
            let columns = max(Int(size.width / 3), 16)
            let elevation = route.elevationSamples(count: Int32(columns)).doubles
            let gradient = route.gradientSamples(count: Int32(columns)).doubles
            guard let low = elevation.min(), let high = elevation.max() else { return }
            let span = max(high - low, 20)
            let floor = low - span * 0.02
            let top = size.height * 0.18
            let total = max(route.totalDistanceMeters, 1)
            let columnWidth = size.width / CGFloat(columns)
            func y(_ e: Double) -> CGFloat { size.height - (size.height - top) * CGFloat((e - floor) / (span * 1.02)) }
            for i in 0 ..< columns {
                let along = Double(i) / Double(max(columns - 1, 1)) * total
                let ridden = along <= distanceMeters && showRider
                let rect = CGRect(
                    x: CGFloat(i) * columnWidth,
                    y: y(elevation[i]),
                    width: columnWidth + 0.5,
                    height: size.height - y(elevation[i])
                )
                context.fill(Path(rect), with: .color(gradientColor(gradient[i]).opacity(ridden ? 0.6 : 0.25)))
            }
            var outline = Path()
            for i in 0 ..< columns {
                let point = CGPoint(x: (CGFloat(i) + 0.5) * columnWidth, y: y(elevation[i]))
                if i == 0 { outline.move(to: point) } else { outline.addLine(to: point) }
            }
            context.stroke(outline, with: .color(.secondary), lineWidth: 1.5)
            guard showRider else { return }
            if startedAtMeters > 1 {
                let sx = size.width * CGFloat(startedAtMeters / total)
                context.stroke(
                    Path {
                        $0.move(to: CGPoint(x: sx, y: top))
                        $0.addLine(to: CGPoint(x: sx, y: size.height))
                    },
                    with: .color(.secondary),
                    style: StrokeStyle(lineWidth: 1, dash: [3, 3])
                )
            }
            let rx = size.width * CGFloat(min(distanceMeters / total, 1))
            let ry = y(route.elevationAt(distanceMeters: distanceMeters))
            context.stroke(
                Path {
                    $0.move(to: CGPoint(x: rx, y: 0))
                    $0.addLine(to: CGPoint(x: rx, y: size.height))
                },
                with: .color(.primary),
                lineWidth: 2
            )
            context.fill(Path(ellipseIn: CGRect(x: rx - 5, y: ry - 5, width: 10, height: 10)), with: .color(.primary))
        }
        .accessibilityHidden(true)
    }
}

/// A listing's 64-sample profile as a small filled line, for a route card.
struct ProfileSpark: View {
    let profile: [Double]

    var body: some View {
        Canvas { context, size in
            guard profile.count > 1, let low = profile.min(), let high = profile.max() else { return }
            // At least 30 m of span, so a flat route reads as flat rather than
            // as its 3 m of noise blown up to the full height.
            let span = max(high - low, 30)
            var area = Path()
            area.move(to: CGPoint(x: 0, y: size.height))
            for (i, e) in profile.enumerated() {
                area.addLine(
                    to: CGPoint(
                        x: size.width * CGFloat(i) / CGFloat(profile.count - 1),
                        y: size.height - size.height * 0.9 * CGFloat((e - low) / span)
                    )
                )
            }
            area.addLine(to: CGPoint(x: size.width, y: size.height))
            context.fill(area, with: .color(.secondary.opacity(0.35)))
        }
        .accessibilityHidden(true)
    }
}

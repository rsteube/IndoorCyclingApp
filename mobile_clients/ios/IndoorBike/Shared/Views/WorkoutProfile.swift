import SwiftUI
import shared

/// A workout's shape: one bar per interval, as long as it lasts and as tall as
/// it is hard, in its zone's colour. With a [progress], what has been ridden is
/// full colour, what is to come is dimmed, and a line marks where the rider is.
struct WorkoutProfile: View {
    let segments: [PowerZone]
    var progress: Double? = nil

    var body: some View {
        Canvas { context, size in
            let total = segments.reduce(0) { $0 + Double($1.lengthInSeconds) }
            guard total > 0 else { return }
            // Scale so the hardest interval nearly fills the height, but never
            // let an easy workout look like a hard one.
            let peak = max(Double(segments.map(\.powerPercentFTP).max() ?? 100), 120)
            var x = 0.0
            for segment in segments {
                let width = size.width * Double(segment.lengthInSeconds) / total
                let height = size.height * Double(segment.powerPercentFTP) / peak
                let rect = CGRect(x: x, y: size.height - height, width: max(width - 1, 0.5), height: height)
                let zone = Zone.forPercent(Int(segment.powerPercentFTP))
                let ridden = progress.map { (x + width / 2) / size.width <= $0 } ?? true
                context.fill(
                    Path(roundedRect: rect, cornerRadius: min(3, width / 2)),
                    with: .color(zone.color.opacity(ridden ? 1 : 0.35))
                )
                x += width
            }
            if let progress {
                let px = size.width * progress
                context.fill(
                    Path(CGRect(x: px - 1, y: 0, width: 2, height: size.height)),
                    with: .color(.primary)
                )
            }
        }
        .accessibilityHidden(true)
    }
}

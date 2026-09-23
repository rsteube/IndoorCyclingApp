import SwiftUI
import shared

/// The 1080 × 1080 picture a ride is shared as, laid out like the Android
/// card: date and name, three figures, the power chart, a highlight, and the
/// app's name at the foot.
struct ShareCard: View {
    let detail: IosAppContainer.RideDetail
    /// The FTP the chart is scaled by: the one the ride's stats were worked out at.
    let ftp: Int

    var body: some View {
        let session = detail.session
        VStack(alignment: .leading, spacing: 36) {
            VStack(alignment: .leading, spacing: 8) {
                Text(session.startDate.formatted(date: .abbreviated, time: .omitted).uppercased())
                    .font(.system(size: 30, weight: .semibold))
                    .foregroundStyle(.secondary)
                Text(session.workoutName)
                    .font(.system(size: 64, weight: .bold))
                    .lineLimit(2)
            }
            HStack(spacing: 24) {
                figure(WorkoutSummaryKt.formatClock(totalSeconds: Int32(session.durationSeconds)), "Duration")
                figure("\(detail.stats.avgPowerWatts) W", "Avg power")
                figure("\(detail.stats.tss)", "TSS")
            }
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("Power").font(.system(size: 30, weight: .semibold))
                    Spacer()
                    Text("PEAK \(detail.powerWatts.map { Int(truncating: $0) }.max() ?? 0) W")
                        .font(.system(size: 26, weight: .semibold).monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                if let workout = detail.workout {
                    AdherenceChart(workout: workout, power: detail.powerWatts.map { Int(truncating: $0) }, ftp: ftp)
                } else {
                    PowerArea(power: detail.powerWatts.map { Int(truncating: $0) })
                }
            }
            .frame(maxHeight: .infinity)
            highlight
            HStack {
                Label("Indoor Bike", systemImage: "bicycle")
                    .font(.system(size: 30, weight: .bold))
                Spacer()
                Text("indoorbike.app")
                    .font(.system(size: 26))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(64)
        .frame(width: 1080, height: 1080)
        .background(Color(hex: 0x14161A))
        .foregroundStyle(.white)
    }

    private func figure(_ value: String, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(value).font(.system(size: 56, weight: .bold).monospacedDigit())
            Text(label.uppercased()).font(.system(size: 24, weight: .semibold)).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// The toughest interval of a planned ride - the longest at Z4 or above,
    /// else the hardest - or the peak power of an unplanned one.
    @ViewBuilder
    private var highlight: some View {
        if let workout = detail.workout, !workout.segments.isEmpty {
            let segments = workout.segments
            let pick = segments
                .filter { Zone.forPercent(Int($0.powerPercentFTP)).rawValue >= 4 }
                .max { Int($0.lengthInSeconds) * Int($0.powerPercentFTP) < Int($1.lengthInSeconds) * Int($1.powerPercentFTP) }
                ?? segments.max { $0.powerPercentFTP < $1.powerPercentFTP }!
            let zone = Zone.forPercent(Int(pick.powerPercentFTP))
            HStack(spacing: 20) {
                RoundedRectangle(cornerRadius: 6).fill(zone.color).frame(width: 12, height: 80)
                VStack(alignment: .leading, spacing: 4) {
                    Text("TOUGHEST INTERVAL").font(.system(size: 22, weight: .semibold)).foregroundStyle(.secondary)
                    Text("Z\(zone.rawValue) · \(clock(Int(pick.lengthInSeconds)))").font(.system(size: 40, weight: .bold))
                }
                Spacer()
                figure("\(pick.powerPercentFTP)%", "% FTP")
                figure(zone.label, "Zone")
            }
        }
    }
}

/// A ride's power as a soft area, for a ride that had no plan to draw.
private struct PowerArea: View {
    let power: [Int]

    var body: some View {
        Canvas { context, size in
            guard power.count > 1, let peak = power.max(), peak > 0 else { return }
            var area = Path()
            area.move(to: CGPoint(x: 0, y: size.height))
            for (i, watts) in power.enumerated() {
                area.addLine(to: CGPoint(
                    x: size.width * Double(i) / Double(power.count - 1),
                    y: size.height * (1 - Double(watts) / Double(peak) * 0.9)
                ))
            }
            area.addLine(to: CGPoint(x: size.width, y: size.height))
            context.fill(area, with: .color(Zone.z2.color.opacity(0.5)))
        }
    }
}

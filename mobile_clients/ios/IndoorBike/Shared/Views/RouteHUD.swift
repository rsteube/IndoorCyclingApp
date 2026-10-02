import SwiftUI
import shared

/// A route ride: the road on a map and in profile, the gradient under the
/// wheel and the one coming, the gear, and how far there is to go. The power,
/// cadence and heart rate sit beside it, as on a workout.
struct RouteHUD: View {
    let simulation: SimulationModel
    let trainer: TrainerModel
    let zoneColor: Color
    let heartColor: Color
    let scale: CGFloat
    let wide: Bool
    let shiftingEnabled: Bool

    var body: some View {
        if let route = simulation.route {
            if wide {
                HStack(spacing: 16) {
                    VStack(spacing: 8) {
                        RouteMap(simulation: simulation)
                            .clipShape(RoundedRectangle(cornerRadius: 16))
                        ElevationProfileView(
                            route: route,
                            distanceMeters: simulation.distanceMeters,
                            startedAtMeters: simulation.startedAtMeters
                        )
                        .frame(height: 70 * scale)
                        progressStrip(route)
                    }
                    VStack(spacing: 20 * scale) {
                        power
                        HStack(spacing: 16) {
                            GradientReadout(simulation: simulation, scale: scale)
                            GearShifter(simulation: simulation, scale: scale, enabled: shiftingEnabled)
                        }
                        riderRow
                    }
                    .frame(width: 340 * min(scale, 1.4))
                }
                .padding(.horizontal)
            } else {
                VStack(spacing: 10) {
                    RouteMap(simulation: simulation)
                        .clipShape(RoundedRectangle(cornerRadius: 16))
                        .frame(minHeight: 140)
                    HStack(spacing: 12) {
                        GradientReadout(simulation: simulation, scale: scale)
                        GearShifter(simulation: simulation, scale: scale, enabled: shiftingEnabled)
                    }
                    power
                    riderRow
                    ElevationProfileView(
                        route: route,
                        distanceMeters: simulation.distanceMeters,
                        startedAtMeters: simulation.startedAtMeters
                    )
                    .frame(height: 60 * scale)
                    progressStrip(route)
                }
                .padding(.horizontal)
            }
        }
    }

    private var power: some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text("\(trainer.power)")
                .font(.system(size: 72 * scale, weight: .bold, design: .rounded).monospacedDigit())
                .foregroundStyle(zoneColor)
                .contentTransition(.numericText())
                .animation(.snappy, value: trainer.power)
            Text("W").font(.title2.weight(.semibold)).foregroundStyle(.secondary)
        }
    }

    private var riderRow: some View {
        HStack(spacing: 40 * scale) {
            small("\(trainer.cadence > 0 ? "\(trainer.cadence)" : "–")", "RPM", .primary)
            small("\(trainer.heartRate > 0 ? "\(trainer.heartRate)" : "–")", "BPM", heartColor)
        }
    }

    private func small(_ value: String, _ unit: String, _ color: Color) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.system(size: 30 * scale, weight: .semibold, design: .rounded).monospacedDigit())
                .foregroundStyle(color)
            Text(unit).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
        }
    }

    private func progressStrip(_ route: RouteTrack) -> some View {
        HStack {
            stripItem(RouteFormatKt.formatOneDecimal(value: simulation.speedMps * 3.6), "km/h", "Speed")
            stripItem(
                RouteFormatKt.formatDistance(meters: max(route.totalDistanceMeters - simulation.distanceMeters, 0)),
                "",
                "To go"
            )
            stripItem(
                "\(Int(simulation.elevationGainMeters.rounded()))",
                "/ \(Int(route.totalAscentMeters.rounded())) m",
                "Climbed"
            )
        }
        .padding(.vertical, 8)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 12))
    }

    private func stripItem(_ value: String, _ unit: String, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text(label.uppercased()).font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(value).font(.system(size: 20 * scale, weight: .semibold).monospacedDigit())
                if !unit.isEmpty { Text(unit).font(.caption).foregroundStyle(.secondary) }
            }
        }
        .frame(maxWidth: .infinity)
    }
}

/// The gradient under the wheel, in its colour, over the next 500 m of road.
struct GradientReadout: View {
    let simulation: SimulationModel
    let scale: CGFloat

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("GRADIENT").font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
            HStack(alignment: .firstTextBaseline, spacing: 2) {
                Text(RouteFormatKt.formatGradientPercent(gradient: simulation.gradient))
                    .font(.system(size: 40 * scale, weight: .bold, design: .rounded).monospacedDigit())
                    .foregroundStyle(gradientColor(simulation.gradient))
                    .contentTransition(.numericText())
                    .animation(.easeInOut(duration: 0.6), value: simulation.gradient)
                Text("%").font(.title3.weight(.semibold)).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(alignment: .bottom) {
            if let route = simulation.route {
                TerrainAhead(route: route, distance: simulation.distanceMeters)
                    .frame(height: 40 * scale)
                    .opacity(0.5)
            }
        }
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 14))
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }
}

/// The road from 50 m behind to 500 m ahead, as a sparkline. Its window and
/// sampling are the Android card's.
private struct TerrainAhead: View {
    let route: RouteTrack
    let distance: Double

    var body: some View {
        // Re-walked once every 5 m of road rather than at every tick.
        let anchor = (distance / 5).rounded(.down) * 5
        Canvas { context, size in
            let samples = 96
            let heights = (0 ..< samples).map { i in
                route.elevationAt(distanceMeters: max(anchor - 50 + 550 * Double(i) / Double(samples - 1), 0))
            }
            guard let low = heights.min(), let high = heights.max() else { return }
            let span = max(high - low, 12)
            var area = Path()
            area.move(to: CGPoint(x: 0, y: size.height))
            for (i, h) in heights.enumerated() {
                area.addLine(
                    to: CGPoint(
                        x: size.width * CGFloat(i) / CGFloat(samples - 1),
                        y: size.height * (1 - CGFloat((h - low) / span) * 0.9)
                    )
                )
            }
            area.addLine(to: CGPoint(x: size.width, y: size.height))
            context.fill(area, with: .color(gradientColor(route.gradientAt(distanceMeters: distance)).opacity(0.6)))
            let now = size.width * 50 / 550
            context.stroke(
                Path {
                    $0.move(to: CGPoint(x: now, y: 0))
                    $0.addLine(to: CGPoint(x: now, y: size.height))
                },
                with: .color(.primary.opacity(0.6)),
                lineWidth: 1
            )
        }
    }
}

/// The virtual gear, and the buttons that change it.
struct GearShifter: View {
    let simulation: SimulationModel
    let scale: CGFloat
    let enabled: Bool

    var body: some View {
        let drivetrain = simulation.drivetrain
        let index = simulation.gearIndex
        let count = Int(drivetrain?.gearCount ?? 1)
        VStack(spacing: 4) {
            Text("GEAR").font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
            HStack(spacing: 10) {
                shiftButton("minus", "Shift to an easier gear", -1, disabled: index <= 0)
                Text("\(drivetrain?.label(index: Int32(index)) ?? 0)")
                    .font(.system(size: 34 * scale, weight: .bold, design: .rounded).monospacedDigit())
                    .frame(minWidth: 40)
                shiftButton("plus", "Shift to a harder gear", 1, disabled: index >= count - 1)
            }
            if let drivetrain {
                let rpm = drivetrain.cadenceRpmFor(speedMps: simulation.speedMps, index: Int32(index))
                Text(
                    rpm > 0
                        ? "\(drivetrain.gearAt(index: Int32(index)).label) · asks \(rpm) rpm"
                        : "\(drivetrain.gearAt(index: Int32(index)).label) · roll to see rpm"
                )
                .font(.caption2.monospacedDigit())
                .foregroundStyle(.secondary)
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 14))
    }

    private func shiftButton(_ symbol: String, _ label: String, _ steps: Int, disabled: Bool) -> some View {
        Button {
            simulation.shift(steps)
        } label: {
            Image(systemName: symbol)
                .font(.headline)
                .frame(width: 40, height: 40)
                .background(.quaternary, in: Circle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled || disabled)
        .accessibilityLabel(label)
    }
}

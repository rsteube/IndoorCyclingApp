import SwiftUI
import shared

/// One workout: what it is, how hard, its shape, and the button that starts it.
struct WorkoutDetail: View {
    let workout: Workout
    let ftp: Int
    let trainerConnected: Bool
    let onConnect: () -> Void
    let onStart: () -> Void

    var body: some View {
        let summary = workout.toSummary(ftpWatts: Int32(ftp))
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                if let description = workout.description_, !description.isEmpty {
                    Text(description)
                        .font(.body)
                        .foregroundStyle(.secondary)
                }

                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 12) { stats(summary) }
                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) { stats(summary) }
                }

                WorkoutProfile(segments: workout.segments)
                    .frame(height: 140)

                VStack(alignment: .leading, spacing: 0) {
                    Text("Intervals")
                        .font(.headline)
                        .padding(.bottom, 8)
                    ForEach(Array(workout.segments.enumerated()), id: \.offset) { _, segment in
                        IntervalRow(segment: segment, ftp: ftp)
                        Divider()
                    }
                }
            }
            .padding()
            .frame(maxWidth: 720, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .navigationTitle(workout.name)
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) {
                if trainerConnected {
                    Button(action: onStart) {
                        Text("Start workout").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                } else {
                    Button(action: onConnect) {
                        Text("Connect a trainer to start").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                }
            }
            .padding()
            .frame(maxWidth: 720)
            .background(.bar)
        }
    }

    @ViewBuilder
    private func stats(_ summary: WorkoutSummary) -> some View {
        Stat(value: WorkoutSummaryKt.formatDurationPretty(totalSeconds: summary.totalSeconds), label: "Duration")
        Stat(value: "\(summary.tss)", label: "TSS")
        Stat(value: String(format: "%.2f", summary.intensityFactor), label: "IF")
        Stat(value: "\(summary.kilojoules)", label: "kJ")
    }
}

private struct Stat: View {
    let value: String
    let label: String

    var body: some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.title3.weight(.semibold).monospacedDigit())
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 10))
    }
}

private struct IntervalRow: View {
    let segment: PowerZone
    let ftp: Int

    var body: some View {
        let percent = Int(segment.powerPercentFTP)
        let zone = Zone.forPercent(percent)
        HStack {
            RoundedRectangle(cornerRadius: 2)
                .fill(zone.color)
                .frame(width: 4, height: 28)
            Text(clock(Int(segment.lengthInSeconds)))
                .monospacedDigit()
                .frame(width: 64, alignment: .leading)
            Text(zone.label)
                .foregroundStyle(zone.color)
            Spacer()
            Text("\(percent)% · \(ftp * percent / 100) W")
                .monospacedDigit()
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 6)
    }
}

import SwiftUI
import shared

/// Every saved ride, grouped by week, with the one picked open beside the
/// list on an iPad and pushed over it on an iPhone.
struct HistoryScreen: View {
    @Bindable var app: AppModel

    var body: some View {
        NavigationSplitView {
            Group {
                if app.history.sessions.isEmpty {
                    List {
                        UnfinishedRideBanner(app: app)
                            .listRowInsets(EdgeInsets())
                            .listRowBackground(Color.clear)
                        ContentUnavailableView {
                            Label("No rides yet", systemImage: "clock.arrow.circlepath")
                        } description: {
                            Text("Your past workouts will appear here once you complete them.")
                        } actions: {
                            Button("Pick a workout") { app.tab = .ride }
                                .buttonStyle(.borderedProminent)
                        }
                        .listRowBackground(Color.clear)
                    }
                } else {
                    list
                }
            }
            .navigationTitle("History")
            #if DEBUG
                .onChange(of: app.history.sessions.first?.id, initial: true) { _, newest in
                    // -demo-latest-ride opens the newest ride, for screenshots.
                    if ProcessInfo.processInfo.arguments.contains("-demo-latest-ride"), app.historySelection == nil {
                        app.historySelection = newest
                    }
                }
            #endif
        } detail: {
            if let id = app.historySelection {
                RideDetailView(app: app, sessionId: id)
                    .id(id)
            } else {
                ContentUnavailableView(
                    "Choose a ride",
                    systemImage: "chart.bar.xaxis",
                    description: Text("Pick a ride to see how it went.")
                )
            }
        }
    }

    private var list: some View {
        let sessions = app.history.sessions
        return List(selection: $app.historySelection) {
            Section {
                UnfinishedRideBanner(app: app)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                TotalsCard(sessions: sessions)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            }
            ForEach(groupByWeek(sessions), id: \.label) { group in
                Section {
                    ForEach(group.sessions, id: \.id) { session in
                        SessionRow(
                            session: session,
                            canRideAgain: app.workouts.contains { $0.id == session.workoutId },
                            rideAgain: { app.showWorkout(session.workoutId) }
                        )
                        .tag(session.id)
                    }
                } header: {
                    HStack {
                        Text(group.label)
                        Spacer()
                        Text(weekSummary(group.sessions))
                            .textCase(nil)
                    }
                }
            }
        }
    }

    private func weekSummary(_ sessions: [WorkoutSession]) -> String {
        let seconds = sessions.reduce(Int64(0)) { $0 + $1.durationSeconds }
        let kj = sessions.reduce(Int64(0)) { $0 + $1.kilojoules }
        let rides = sessions.count == 1 ? "1 ride" : "\(sessions.count) rides"
        return "\(rides) · \(ProgressionKt.durationLabelLong(totalSeconds: seconds)) · \(kj) kJ"
    }
}

struct WeekGroup {
    let label: String
    let sessions: [WorkoutSession]
}

/// "This week", "Last week", then by month, in the rider's own calendar: its
/// first weekday and its month names. Sessions arrive newest first and keep
/// that order.
func groupByWeek(_ sessions: [WorkoutSession], now: Date = .now, calendar: Calendar = .current) -> [WeekGroup] {
    let thisWeek = calendar.dateInterval(of: .weekOfYear, for: now)?.start ?? now
    let lastWeek = calendar.date(byAdding: .weekOfYear, value: -1, to: thisWeek) ?? thisWeek
    var order: [String] = []
    var buckets: [String: [WorkoutSession]] = [:]
    for session in sessions {
        let date = session.startDate
        let label: String
        if date >= thisWeek {
            label = "This week"
        } else if date >= lastWeek {
            label = "Last week"
        } else {
            label = date.formatted(.dateTime.month(.wide).year())
        }
        if buckets[label] == nil { order.append(label) }
        buckets[label, default: []].append(session)
    }
    return order.map { WeekGroup(label: $0, sessions: buckets[$0] ?? []) }
}

private struct TotalsCard: View {
    let sessions: [WorkoutSession]

    var body: some View {
        HStack {
            total("\(sessions.count)", "Rides")
            total(
                ProgressionKt.durationLabelLong(totalSeconds: ProgressionKt.totalDurationSeconds(sessions: sessions)),
                "Time"
            )
            total("\(ProgressionKt.totalKjAcross(sessions: sessions))", "kJ")
        }
        .padding(.vertical, 14)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 16))
        .padding(.vertical, 4)
    }

    private func total(_ value: String, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.title2.weight(.semibold).monospacedDigit())
                .contentTransition(.numericText())
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }
}

private struct SessionRow: View {
    let session: WorkoutSession
    let canRideAgain: Bool
    let rideAgain: () -> Void

    var body: some View {
        let date = session.startDate
        HStack(spacing: 12) {
            VStack(spacing: 0) {
                Text(date.formatted(.dateTime.weekday(.abbreviated)).uppercased())
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                Text(date.formatted(.dateTime.day()))
                    .font(.title3.weight(.semibold).monospacedDigit())
            }
            .frame(width: 44, height: 48)
            .background(.quaternary.opacity(0.6), in: RoundedRectangle(cornerRadius: 10))

            VStack(alignment: .leading, spacing: 4) {
                Text(session.workoutName)
                    .font(.headline)
                    .lineLimit(1)
                Text(
                    "\(date.formatted(date: .omitted, time: .shortened)) · \(ProgressionKt.durationLabelLong(totalSeconds: session.durationSeconds)) · \(session.avgPower) W · \(session.kilojoules) kJ"
                )
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 2)
        .contextMenu {
            if canRideAgain {
                Button("Ride again", systemImage: "arrow.counterclockwise", action: rideAgain)
            }
        }
    }
}

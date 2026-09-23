import SwiftUI
import shared

enum RideSection: Hashable {
    case workouts, routes
}

/// "What are you riding?": the kinds of ride, then the list of the chosen
/// kind, then the one picked. Three columns on an iPad, one screen after the
/// other on an iPhone.
struct RideHome: View {
    @Bindable var app: AppModel
    @State private var showingTrainer = false
    @State private var onboarding = false
    @State private var device: DeviceKind?

    var body: some View {
        NavigationSplitView {
            List(selection: $app.rideSection) {
                if !app.settings.hasCompletedOnboarding {
                    WelcomeCard { onboarding = true }
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                } else if !app.settings.hasDismissedStarterCard && app.history.sessions.isEmpty,
                          let first = app.workouts.first(where: { $0.id == "starter-15min" }) {
                    FirstRideCard(
                        onStart: { app.showWorkout(first.id) },
                        onDismiss: app.settings.dismissStarterCard
                    )
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                }
                UnfinishedRideBanner(app: app)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)

                Section("What are you riding?") {
                    ModeRow(systemImage: "figure.indoor.cycle", title: "Workout", detail: "Intervals the trainer holds for you.")
                        .tag(RideSection.workouts)
                    ModeRow(systemImage: "mountain.2", title: "Route", detail: "A real road, with its hills under the wheel.", stage: "Beta")
                        .tag(RideSection.routes)
                    Button {
                        if app.trainer.isConnected { app.startFreeRide() } else { showingTrainer = true }
                    } label: {
                        ModeRow(
                            systemImage: "speedometer", title: "Just ride",
                            detail: app.trainer.isConnected ? "No plan. Set the resistance yourself." : "Pair a trainer to ride freely at your own power."
                        )
                    }
                    .buttonStyle(.plain)
                }

                Section("Devices") {
                    ForEach([DeviceKind.trainer, .heartRateMonitor, .controller]) { kind in
                        Button { device = kind } label: { DeviceRow(kind: kind, app: app) }
                            .buttonStyle(.plain)
                    }
                }
            }
            .navigationTitle("Ride")
        } content: {
            switch app.rideSection {
            case .workouts:
                WorkoutListView(app: app)
            case .routes:
                RouteListView(app: app)
            case nil:
                ContentUnavailableView("What are you riding?", systemImage: "bicycle", description: Text("Pick a workout or a route."))
            }
        } detail: {
            if app.rideSection == .routes, let route = app.routes.routes.first(where: { $0.id == app.routeSelection }) {
                RouteDetailView(app: app, route: route)
            } else if app.rideSection == .workouts, let workout = app.workouts.first(where: { $0.id == app.workoutSelection }) {
                WorkoutDetail(
                    workout: workout,
                    ftp: app.settings.ftp,
                    trainerConnected: app.trainer.isConnected,
                    onConnect: { showingTrainer = true },
                    onStart: { app.startRide(workout) }
                )
            } else {
                ContentUnavailableView("Nothing picked yet", systemImage: "bicycle", description: Text("Choose from the list to see it here."))
            }
        }
        .sheet(isPresented: $showingTrainer) { DeviceSheet(kind: .trainer, app: app) }
        .sheet(item: $device) { kind in DeviceSheet(kind: kind, app: app) }
        .sheet(isPresented: $onboarding) { OnboardingSheet(app: app) }
    }
}

private struct ModeRow: View {
    let systemImage: String
    let title: String
    let detail: String
    var stage: String? = nil

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.title3)
                .frame(width: 40, height: 40)
                .background(.tint.opacity(0.15), in: RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(title).font(.headline)
                    if let stage {
                        Text(stage.uppercased())
                            .font(.caption2.weight(.bold))
                            .padding(.horizontal, 6).padding(.vertical, 2)
                            .background(.orange.opacity(0.2), in: Capsule())
                    }
                }
                Text(detail).font(.subheadline).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }
}

/// The workout library.
struct WorkoutListView: View {
    @Bindable var app: AppModel

    var body: some View {
        List(app.workouts, id: \.id, selection: $app.workoutSelection) { workout in
            WorkoutRow(workout: workout, ftp: app.settings.ftp)
                .tag(workout.id)
        }
        .navigationTitle("Workouts")
    }
}

private struct WorkoutRow: View {
    let workout: Workout
    let ftp: Int

    var body: some View {
        let summary = workout.toSummary(ftpWatts: Int32(ftp))
        VStack(alignment: .leading, spacing: 6) {
            Text(workout.name)
                .font(.headline)
            HStack(spacing: 8) {
                Text(WorkoutSummaryKt.formatDurationPretty(totalSeconds: summary.totalSeconds))
                Text("·")
                Text(summary.tag)
                Text("·")
                Text("TSS \(summary.tss)")
            }
            .font(.subheadline)
            .foregroundStyle(.secondary)
            WorkoutProfile(segments: workout.segments)
                .frame(height: 28)
        }
        .padding(.vertical, 4)
    }
}


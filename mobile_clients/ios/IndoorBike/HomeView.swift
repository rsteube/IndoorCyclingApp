import SwiftUI
import shared

/// The workout library and the trainer, side by side on an iPad and one after
/// the other on an iPhone.
struct HomeView: View {
    @Bindable var app: AppModel
    @State private var showingTrainer = false
    @State private var onboarding = false

    var body: some View {
        NavigationSplitView {
            List(selection: $app.workoutSelection) {
                if !app.settings.hasCompletedOnboarding {
                    WelcomeCard { onboarding = true }
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                } else if !app.settings.hasDismissedStarterCard && app.history.sessions.isEmpty,
                          let first = app.workouts.first(where: { $0.id == "starter-15min" }) {
                    FirstRideCard(
                        onStart: { app.workoutSelection = first.id },
                        onDismiss: app.settings.dismissStarterCard
                    )
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                }
                UnfinishedRideBanner(app: app)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                ForEach(app.workouts, id: \.id) { workout in
                    WorkoutRow(workout: workout, ftp: app.settings.ftp)
                        .tag(workout.id)
                }
            }
            .navigationTitle("Workouts")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    TrainerButton(trainer: app.trainer) { showingTrainer = true }
                }
            }
        } detail: {
            if let workout = app.workouts.first(where: { $0.id == app.workoutSelection }) {
                WorkoutDetail(
                    workout: workout,
                    ftp: app.settings.ftp,
                    trainerConnected: app.trainer.isConnected,
                    onConnect: { showingTrainer = true },
                    onStart: { app.startRide(workout) }
                )
            } else {
                ContentUnavailableView(
                    "Pick a workout",
                    systemImage: "bicycle",
                    description: Text("Choose one from the list to see its intervals and start it.")
                )
            }
        }
        .sheet(isPresented: $showingTrainer) {
            DeviceSheet(kind: .trainer, app: app)
        }
        .sheet(isPresented: $onboarding) {
            OnboardingSheet(app: app)
        }
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

/// The trainer's state in the toolbar, and the way to connect one.
private struct TrainerButton: View {
    let trainer: TrainerModel
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            if trainer.isConnected {
                Label(trainer.trainerName ?? "Trainer", systemImage: "bolt.horizontal.circle.fill")
                    .labelStyle(.titleAndIcon)
                    .foregroundStyle(.green)
            } else {
                Label("Connect trainer", systemImage: "bolt.horizontal.circle")
                    .labelStyle(.titleAndIcon)
            }
        }
    }
}

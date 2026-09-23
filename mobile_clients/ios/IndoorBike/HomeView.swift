import SwiftUI
import shared

/// The workout library and the trainer, side by side on an iPad and one after
/// the other on an iPhone.
struct HomeView: View {
    let app: AppModel
    @State private var selection: String?
    @State private var showingTrainer = false
    @State private var showingRider = false
    @State private var riding = false

    var body: some View {
        NavigationSplitView {
            List(app.workouts, id: \.id, selection: $selection) { workout in
                WorkoutRow(workout: workout, ftp: app.settings.ftp)
                    .tag(workout.id)
            }
            .navigationTitle("Workouts")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { showingRider = true } label: {
                        Label("Rider", systemImage: "person.crop.circle")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    TrainerButton(trainer: app.trainer) { showingTrainer = true }
                }
            }
        } detail: {
            if let workout = app.workouts.first(where: { $0.id == selection }) {
                WorkoutDetail(
                    workout: workout,
                    ftp: app.settings.ftp,
                    trainerConnected: app.trainer.isConnected,
                    onConnect: { showingTrainer = true },
                    onStart: {
                        app.ride.begin(workout)
                        riding = true
                    }
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
            TrainerScreen(trainer: app.trainer)
        }
        .sheet(isPresented: $showingRider) {
            RiderSettings(settings: app.settings)
        }
        .onChange(of: app.demoRideRequested) { _, requested in
            if requested { riding = true }
        }
        .fullScreenCover(isPresented: $riding) {
            RideView(ride: app.ride, trainer: app.trainer, ftp: app.settings.ftp) {
                app.ride.close()
                riding = false
            }
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

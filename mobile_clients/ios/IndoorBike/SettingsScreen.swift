import SwiftUI
import shared

/// The rider, their devices, and the app.
struct SettingsScreen: View {
    let app: AppModel
    @State private var showingTrainer = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Trainer") {
                    Button { showingTrainer = true } label: {
                        LabeledContent("Smart trainer", value: app.trainer.isConnected ? (app.trainer.trainerName ?? "Connected") : "Not connected")
                    }
                }
                RiderSection(settings: app.settings)
            }
            .navigationTitle("Settings")
            .sheet(isPresented: $showingTrainer) { TrainerScreen(trainer: app.trainer) }
        }
    }
}

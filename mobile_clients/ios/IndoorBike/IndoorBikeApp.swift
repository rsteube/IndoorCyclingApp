import SwiftUI
import shared

@main
struct IndoorBikeApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @State private var app = AppModel()

    var body: some Scene {
        WindowGroup {
            HomeView(app: app)
                .task { app.startDemoIfAsked() }
        }
        .onChange(of: scenePhase) { _, phase in
            app.container.setForeground(value: phase == .active)
        }
    }
}

/// Everything the app holds for its whole life: the shared container, built
/// once, and the Swift models over it.
@MainActor
@Observable
final class AppModel {
    let container: IosAppContainer
    let trainer: TrainerModel
    let ride: RideModel
    let settings: SettingsModel
    let workouts: [Workout]

    init() {
        let container = IosAppContainer()
        self.container = container
        trainer = TrainerModel(manager: container.trainerManager, heartRateMonitor: container.heartRateMonitor)
        ride = RideModel(container: container)
        settings = SettingsModel(settings: container.userSettings)
        workouts = container.parseWorkouts(jsonFiles: Self.bundledWorkoutFiles())
        container.start()
    }

    /// The starter library, shipped as the same JSON the Android app bundles.
    private static func bundledWorkoutFiles() -> [String] {
        (Bundle.main.urls(forResourcesWithExtension: "json", subdirectory: nil) ?? [])
            .filter { $0.lastPathComponent.hasPrefix("workouts") }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
            .compactMap { try? String(contentsOf: $0, encoding: .utf8) }
    }

    /// Set by `-demo-ride`: the ride screen opens on launch.
    var demoRideRequested = false

    /// `-demo-trainer` on the launch arguments connects the demo trainer and
    /// starts it pedalling, so the Simulator (which has no Bluetooth) opens on
    /// a live trainer. `-demo-ride [workout id]` does that and starts the
    /// workout too, the first one when no id follows. Debug builds only.
    func startDemoIfAsked() {
        #if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        let ride = arguments.contains("-demo-ride")
        guard ride || arguments.contains("-demo-trainer"), !trainer.isConnected else { return }
        trainer.connectMock()
        trainer.toggleMockPedaling()
        let requestedId = arguments.firstIndex(of: "-demo-ride").flatMap { arguments[safe: $0 + 1] }
        if ride, let workout = workouts.first(where: { $0.id == requestedId }) ?? workouts.first {
            self.ride.begin(workout)
            demoRideRequested = true
        }
        #endif
    }
}

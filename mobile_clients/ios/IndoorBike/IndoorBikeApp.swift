import SwiftUI
import shared

@main
struct IndoorBikeApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @State private var app = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView(app: app)
                .task { app.startDemoIfAsked() }
        }
        .onChange(of: scenePhase) { _, phase in
            app.container.setForeground(value: phase == .active)
        }
    }
}

enum AppTab: Hashable {
    case ride, history, progress, settings
}

/// Everything the app holds for its whole life: the shared container, built
/// once, the Swift models over it, and where the rider is in the app.
@MainActor
@Observable
final class AppModel {
    let container: IosAppContainer
    let trainer: TrainerModel
    let ride: RideModel
    let settings: SettingsModel
    let history: HistoryModel
    let unfinished: UnfinishedRideModel
    let workouts: [Workout]

    var tab: AppTab = .ride
    /// The workout open in the Ride tab.
    var workoutSelection: String?
    /// The ride open in the History tab.
    var historySelection: Int64?
    /// The ride screen is showing.
    var riding = false

    init() {
        let container = IosAppContainer()
        self.container = container
        let workouts = container.parseWorkouts(jsonFiles: Self.bundledWorkoutFiles())
        self.workouts = workouts
        trainer = TrainerModel(manager: container.trainerManager, heartRateMonitor: container.heartRateMonitor)
        ride = RideModel(container: container)
        settings = SettingsModel(settings: container.userSettings)
        history = HistoryModel(repository: container.historyRepository)
        unfinished = UnfinishedRideModel(container: container, library: workouts)
        container.start()
    }

    func startRide(_ workout: Workout) {
        ride.begin(workout)
        riding = true
    }

    /// Leave the ride screen; a saved ride opens in History.
    func finishRide() {
        let saved = ride.savedSessionId
        ride.close()
        riding = false
        if let saved { showRide(saved) }
    }

    /// Back to a workout from a past ride, to ride it again.
    func showWorkout(_ workoutId: String) {
        workoutSelection = workoutId
        tab = .ride
    }

    func showRide(_ sessionId: Int64) {
        historySelection = sessionId
        tab = .history
    }

    func continueUnfinishedRide() {
        unfinished.continueRide()
        riding = true
    }

    /// The starter library, shipped as the same JSON the Android app bundles.
    private static func bundledWorkoutFiles() -> [String] {
        (Bundle.main.urls(forResourcesWithExtension: "json", subdirectory: nil) ?? [])
            .filter { $0.lastPathComponent.hasPrefix("workouts") }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
            .compactMap { try? String(contentsOf: $0, encoding: .utf8) }
    }

    /// `-demo-trainer` on the launch arguments connects the demo trainer and
    /// starts it pedalling, so the Simulator (which has no Bluetooth) opens on
    /// a live trainer. `-demo-ride [workout id]` does that and starts the
    /// workout too, the first one when no id follows. Debug builds only.
    func startDemoIfAsked() {
        #if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        let wantsRide = arguments.contains("-demo-ride")
        if let i = arguments.firstIndex(of: "-demo-tab"), let name = arguments[safe: i + 1] {
            tab = ["history": .history, "progress": .progress, "settings": .settings][name] ?? .ride
        }
        guard wantsRide || arguments.contains("-demo-trainer"), !trainer.isConnected else { return }
        trainer.connectMock()
        trainer.toggleMockPedaling()
        let requestedId = arguments.firstIndex(of: "-demo-ride").flatMap { arguments[safe: $0 + 1] }
        if wantsRide, let workout = workouts.first(where: { $0.id == requestedId }) ?? workouts.first {
            startRide(workout)
        }
        #endif
    }
}

/// The four places in the app, as on Android: a tab bar on an iPhone, a
/// sidebar on an iPad.
struct RootView: View {
    @Bindable var app: AppModel

    var body: some View {
        TabView(selection: $app.tab) {
            Tab("Ride", systemImage: "bicycle", value: AppTab.ride) {
                HomeView(app: app)
            }
            Tab("History", systemImage: "clock.arrow.circlepath", value: AppTab.history) {
                HistoryScreen(app: app)
            }
            Tab("Progress", systemImage: "chart.line.uptrend.xyaxis", value: AppTab.progress) {
                ProgressScreen(app: app)
            }
            Tab("Settings", systemImage: "gearshape", value: AppTab.settings) {
                SettingsScreen(app: app)
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        .fullScreenCover(isPresented: $app.riding) {
            RideView(ride: app.ride, trainer: app.trainer, ftp: app.settings.ftp) {
                app.finishRide()
            }
        }
    }
}

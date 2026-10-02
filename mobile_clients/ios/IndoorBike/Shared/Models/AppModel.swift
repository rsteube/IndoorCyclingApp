import Foundation
import SwiftUI
import shared

/// Everything the app holds for its whole life: the shared container, built
/// once, the Swift models over it, and where the rider is in the app.
@MainActor
@Observable
final class AppModel {
    let container: IosAppContainer
    let trainer: TrainerModel
    let heartRateMonitor: SensorModel
    let controller: SensorModel
    let ride: RideModel
    let settings: SettingsModel
    let history: HistoryModel
    let unfinished: UnfinishedRideModel
    let routes: RouteLibraryModel
    let simulation: SimulationModel
    let library: LibraryModel
    let sync: SyncModel
    let companion: CompanionModel
    /// The Companion screen is up, so its socket belongs to it.
    var keepCompanionOpen = false

    var workouts: [Workout] { library.workouts }
    /// A short line about something that just happened, shown for a moment.
    var notice: String?

    var tab: AppTab = .ride
    /// Where the Ride tab has got to: empty on its home, else the kind of ride open.
    var ridePath: [RideSection] = []
    /// Which kind of ride is open in the Ride tab.
    var rideSection: RideSection? {
        get { ridePath.last }
        set { ridePath = newValue.map { [$0] } ?? [] }
    }
    /// The workout open in the Ride tab.
    var workoutSelection: String?
    /// The route open in the Ride tab.
    var routeSelection: String?
    /// The ride open in the History tab.
    var historySelection: Int64?
    /// The ride screen is showing.
    var riding = false

    init() {
        let container = IosAppContainer(
            bundledWorkoutJson: Self.bundledWorkoutFiles(),
            secrets: KeychainStore(),
            appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0",
            isDebug: AppInfo.isDebug
        )
        self.container = container
        let library = LibraryModel(container: container)
        self.library = library
        trainer = TrainerModel(
            manager: container.trainerManager,
            heartRateMonitor: container.heartRateMonitor,
            onForget: container.forgetTrainer
        )
        heartRateMonitor = .heartRateMonitor(container.heartRateMonitor, container: container)
        controller = .controller(container.controllerManager, container: container)
        ride = RideModel(container: container)
        settings = SettingsModel(settings: container.userSettings)
        history = HistoryModel(repository: container.historyRepository)
        unfinished = UnfinishedRideModel(container: container, library: { library.workouts })
        routes = RouteLibraryModel(container: container)
        simulation = SimulationModel(engine: container.simulationEngine)
        sync = SyncModel(container: container)
        companion = CompanionModel(container: container)
        container.start()
    }

    func startRide(_ workout: Workout) {
        ride.begin(workout)
        riding = true
    }

    /// Ride [route], from the start or from where the rider got to.
    func startRoute(_ route: RouteTrack, from progress: RouteProgress?) {
        ride.prepare { container.prepareRoute(route: route, resumeFrom: progress) }
        riding = true
    }

    /// No plan: the rider sets the wattage.
    func startFreeRide() {
        ride.prepare { container.prepareFreeRide() }
        riding = true
    }

    /// Leave the ride screen; a saved ride opens in History.
    func finishRide() {
        let saved = ride.savedSessionId
        if ride.isCompanion {
            // The host saved each lap as it finished; leaving ends the session.
            if companion.phase != .ended { companion.host.endSession() }
            if !keepCompanionOpen { companion.host.stop() }
            riding = false
            UIApplication.shared.isIdleTimerDisabled = false
            return
        }
        ride.close()
        riding = false
        routes.reloadProgress()
        if let saved { showRide(saved) }
    }

    /// Back to a workout from a past ride, to ride it again.
    func showWorkout(_ workoutId: String) {
        rideSection = .workouts
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

    /// Say [text] for a few seconds.
    func show(_ text: String?) {
        guard let text else { return }
        notice = text
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(4))
            if notice == text { notice = nil }
        }
    }

    /// Import from a link or a file the system handed the app, and say how it went.
    func open(_ url: URL) {
        let done: (ImportResult) -> Void = { [weak self] result in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.show(result.notice)
                if let workout = result.singleWorkout { self.showWorkout(workout.id) }
            }
        }
        if url.scheme == "indoorbike" && url.host == "oauth" {
            // FitTrackee's sign-in coming back from the browser.
            container.completeFitTrackeeSignIn(redirect: url.absoluteString) { [weak self] error in
                MainActor.assumeIsolated { self?.show(error ?? "Connected to FitTrackee") }
            }
            return
        }
        if url.isFileURL {
            let accessed = url.startAccessingSecurityScopedResource()
            defer { if accessed { url.stopAccessingSecurityScopedResource() } }
            if let data = try? Data(contentsOf: url) {
                container.importWorkoutFile(data: data, onDone: done)
            } else {
                show("Couldn't open that file.")
            }
        } else {
            container.importWorkoutLink(link: url.absoluteString, onDone: done)
        }
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
            if let i = arguments.firstIndex(of: "-demo-open"), let link = arguments[safe: i + 1],
                let url = URL(string: link)
            {
                // As if the system had opened the link, without its confirmation.
                open(url)
            }
            if arguments.contains("-demo-sync-folder") {
                // A sync folder inside the app's own Documents, as if picked in Files.
                let folder = URL.documentsDirectory.appendingPathComponent("sync-test", isDirectory: true)
                try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                if let bookmark = try? folder.bookmarkData() {
                    container.userSettings.setSyncFolderUri(uri: bookmark.base64EncodedString())
                    container.syncNow()
                }
            }
            if let i = arguments.firstIndex(of: "-demo-section"), let name = arguments[safe: i + 1] {
                rideSection = ["routes": .routes, "companion": .companion][name] ?? .workouts
            }
            guard wantsRide || arguments.contains("-demo-trainer"), !trainer.isLive else { return }
            trainer.connectMock()
            trainer.toggleMockPedaling()
            let requestedId = arguments.firstIndex(of: "-demo-ride").flatMap { arguments[safe: $0 + 1] }
            if wantsRide, requestedId == "route" {
                // -demo-ride route: the first route in the library, once it has loaded.
                Task { @MainActor in
                    while routes.routes.isEmpty { try? await Task.sleep(for: .milliseconds(100)) }
                    startRoute(routes.routes[0], from: nil)
                }
            } else if wantsRide, requestedId == "free" {
                startFreeRide()
            } else if wantsRide, let workout = workouts.first(where: { $0.id == requestedId }) ?? workouts.first {
                startRide(workout)
            }
        #endif
    }
}

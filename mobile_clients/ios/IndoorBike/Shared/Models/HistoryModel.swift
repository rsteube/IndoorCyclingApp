import Foundation
import Observation
import shared

/// Every saved ride, newest first, from the shared history database.
@MainActor
@Observable
final class HistoryModel {
    private(set) var sessions: [WorkoutSession] = []

    @ObservationIgnored private let flows = KotlinFlows()

    init(repository: HistoryRepository) {
        flows.watch(repository.getAllSessions()) { [unowned self] (value: NSArray?) in
            sessions = (value as? [WorkoutSession]) ?? []
        }
    }
}

/// The ride a previous run of the app left behind, when there is one to offer.
@MainActor
@Observable
final class UnfinishedRideModel {
    private(set) var ride: UnfinishedRide?

    @ObservationIgnored private let container: IosAppContainer
    @ObservationIgnored private let library: () -> [Workout]
    @ObservationIgnored private let flows = KotlinFlows()
    @ObservationIgnored private var snapshot: ActiveSessionSnapshot?
    @ObservationIgnored private var state: WorkoutState = .idle

    init(container: IosAppContainer, library: @escaping () -> [Workout]) {
        self.container = container
        self.library = library
        flows.watch(container.sessionManager.persistedSnapshot) { [unowned self] (value: ActiveSessionSnapshot?) in
            snapshot = value
            update()
        }
        flows.watch(container.sessionManager.state) { [unowned self] (value: WorkoutState?) in
            state = value ?? .idle
            update()
        }
        // An unfinished ride of an imported workout can only be named once the
        // rider's own workouts have loaded.
        flows.watch(container.workoutLibrary.entries) { [unowned self] (_: NSArray?) in update() }
    }

    /// Only with nothing on the go: while this run has a ride of its own, from
    /// being prepared to being saved, the snapshot is that ride's and not one
    /// left behind.
    private func update() {
        ride = state == .idle ? container.unfinishedRide(snapshot: snapshot, library: library()) : nil
    }

    func continueRide() {
        guard let ride else { return }
        container.continueUnfinishedRide(ride: ride)
    }

    func save(onSaved: @escaping (Int64?) -> Void) {
        guard let ride else { return }
        container.saveUnfinishedRide(ride: ride) { id in
            MainActor.assumeIsolated { onSaved(id?.int64Value) }
        }
    }

    func abandon() {
        container.abandonUnfinishedRide()
        ride = nil
    }
}

extension WorkoutSession {
    var startDate: Date { Date(timeIntervalSince1970: TimeInterval(startTimeEpoch) / 1000) }
    var kilojoules: Int64 { ProgressionKt.sessionKilojoules(session: self) }
}

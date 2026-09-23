import Foundation
import Observation
import shared

/// The ride in progress, from the shared `WorkoutSessionManager`.
///
/// The session manager runs the whole ride - pedal to start, the countdown,
/// the ERG targets, auto-pause, the crash-safe snapshot. This copies what the
/// ride screen shows into observable properties and forwards the controls.
@MainActor
@Observable
final class RideModel {
    private(set) var state: WorkoutState = .idle
    private(set) var workout: Workout?
    private(set) var elapsedSeconds = 0
    private(set) var rideSeconds = 0
    private(set) var countdown = 0
    private(set) var segmentIndex = 0
    private(set) var elapsedInSegment = 0
    private(set) var biasPercent = 0
    private(set) var freeRideTargetWatts = 0

    /// The ride's row in history once it has been saved.
    private(set) var savedSessionId: Int64?
    private(set) var saveFailed = false

    @ObservationIgnored private let container: IosAppContainer
    @ObservationIgnored private let flows = KotlinFlows()
    @ObservationIgnored private var saving = false

    var session: WorkoutSessionManager { container.sessionManager }

    init(container: IosAppContainer) {
        self.container = container
        let session = container.sessionManager
        flows.watch(session.state) { [unowned self] (value: WorkoutState?) in
            state = value ?? .idle
            if state == .completed { saveIfCompleted() }
        }
        flows.watch(session.activeWorkout) { [unowned self] (value: Workout?) in workout = value }
        flows.watchInt(session.elapsedSeconds) { [unowned self] in elapsedSeconds = $0 }
        flows.watchInt(session.actualRideSeconds) { [unowned self] in rideSeconds = $0 }
        flows.watchInt(session.countdownSeconds) { [unowned self] in countdown = $0 }
        flows.watchInt(session.currentSegmentIndex) { [unowned self] in segmentIndex = $0 }
        flows.watchInt(session.elapsedInSegment) { [unowned self] in elapsedInSegment = $0 }
        flows.watchInt(session.targetBiasPercent) { [unowned self] in biasPercent = $0 }
        flows.watchInt(session.freeRideTargetWatts) { [unowned self] in freeRideTargetWatts = $0 }
    }

    // MARK: Derived

    var segments: [PowerZone] { workout?.segments ?? [] }
    var totalSeconds: Int { segments.reduce(0) { $0 + Int($1.lengthInSeconds) } }
    var currentSegment: PowerZone? { segments[safe: segmentIndex] }
    var nextSegment: PowerZone? { segments[safe: segmentIndex + 1] }
    var remainingInSegment: Int { max(0, Int(currentSegment?.lengthInSeconds ?? 0) - elapsedInSegment) }
    /// How far through the whole workout's plan the ride is, 0...1.
    var progress: Double { totalSeconds > 0 ? min(1, Double(elapsedSeconds) / Double(totalSeconds)) : 0 }
    var hasStarted: Bool { session.sessionStartTimeEpoch != 0 }
    var isRiding: Bool { state == .active || state == .untracked }

    // MARK: Controls

    func begin(_ workout: Workout) {
        prepare { container.prepareWorkout(workout: workout) }
    }

    /// Get a ride of any kind ready: [how] is the container call that does it.
    func prepare(_ how: () -> Void) {
        savedSessionId = nil
        saveFailed = false
        how()
    }

    var isSimulation: Bool { workout?.isSimulation ?? false }
    var isFreeRide: Bool { workout?.isFreeRide ?? false }
    var isCompanion: Bool { workout?.isCompanion ?? false }
    /// No plan to count down: a free ride or a route.
    var isOpenEnded: Bool { isSimulation || isFreeRide || isCompanion }

    func pause() { session.pauseWorkout() }
    func resume() { session.resumeWorkout() }
    func skipSegment() { session.skipSegment() }
    /// One step, as the ride understands it: see the shared `adjustEffort`.
    func easier() { container.stepEffort(direction: -1) }
    func harder() { container.stepEffort(direction: 1) }

    /// End the ride: saved if it ever started, simply dropped if the rider never
    /// pedalled off.
    func end() {
        if hasStarted {
            session.endWorkoutEarly()
        } else {
            session.stopWorkout()
        }
    }

    /// Leave the ride screen for good. A completed ride has already been saved.
    func close() {
        if state != .completed && state != .idle { session.stopWorkout() }
    }

    private func saveIfCompleted() {
        // A companion ride the host is hosting is written by the host, the
        // moment the game says it is over; saving it here too would save it twice.
        if isCompanion && container.companionHost.hosting.value as? Bool == true { return }
        guard !saving, savedSessionId == nil else { return }
        saving = true
        container.saveCompletedRide { [weak self] id in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.saving = false
                if let id { self.savedSessionId = id.int64Value } else { self.saveFailed = true }
            }
        }
    }
}

extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}

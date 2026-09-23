import Foundation
import Observation
import shared

/// The shared `TrainerManager`, with its flows copied into properties SwiftUI
/// can observe.
///
/// Everything the trainer does - scanning, connecting, reconnecting, parsing
/// FTMS and Cycling Power - is the same Kotlin the Android app runs. This only
/// watches the flows and forwards the taps.
@MainActor
@Observable
final class TrainerModel {
    private(set) var state: TrainerConnectionState = .disconnected
    private(set) var candidates: [TrainerCandidate] = []
    private(set) var trainerName: String?
    private(set) var isMock = false
    private(set) var power = 0
    private(set) var cadence = 0
    private(set) var heartRate = 0
    private(set) var targetPower = 0
    private(set) var pairedId: String?
    /// How the app can drive this trainer; see `trainerHelper`.
    private(set) var control: TrainerControl?

    var isPaired: Bool { (pairedId != nil && pairedId != "mock") || isMock }

    var isConnected: Bool { state == .connected }
    var isScanning: Bool { state == .scanning }

    @ObservationIgnored private let manager: TrainerManager
    @ObservationIgnored private let onForget: () -> Void
    @ObservationIgnored private let flows = KotlinFlows()

    init(manager: TrainerManager, heartRateMonitor: HeartRateMonitor, onForget: @escaping () -> Void) {
        self.manager = manager
        self.onForget = onForget
        flows.watch(manager.pairedTrainerId) { [unowned self] (value: NSString?) in pairedId = value as String? }
        flows.watch(manager.control) { [unowned self] (value: TrainerControl?) in control = value }
        flows.watch(manager.connectionState) { [unowned self] (value: TrainerConnectionState?) in
            state = value ?? .disconnected
        }
        flows.watch(manager.discoveredDevices) { [unowned self] (value: NSArray?) in
            candidates = (value as? [TrainerCandidate]) ?? []
        }
        flows.watch(manager.connectedTrainerName) { [unowned self] (value: NSString?) in
            trainerName = value as String?
        }
        flows.watch(manager.isMockTrainer) { [unowned self] (value: KotlinBoolean?) in
            isMock = value?.boolValue ?? false
        }
        flows.watchInt(manager.power) { [unowned self] in power = $0 }
        flows.watchInt(manager.cadence) { [unowned self] in cadence = $0 }
        flows.watchInt(manager.targetPower) { [unowned self] in targetPower = $0 }
        // A chest strap wins over the trainer's own reading, as in the ride
        // record; see IosAppContainer.
        var trainerHeartRate = 0
        var strapHeartRate = 0
        flows.watchInt(manager.heartRate) { [unowned self] in
            trainerHeartRate = $0
            heartRate = strapHeartRate > 0 ? strapHeartRate : trainerHeartRate
        }
        flows.watchInt(heartRateMonitor.heartRate) { [unowned self] in
            strapHeartRate = $0
            heartRate = strapHeartRate > 0 ? strapHeartRate : trainerHeartRate
        }
    }

    func startScan() { manager.startScan() }
    func stopScan() { manager.stopScan() }
    func connect(_ candidate: TrainerCandidate) { manager.connectToCandidate(candidateId: candidate.id) }
    func connectMock() { manager.connectMock() }
    func toggleMockPedaling() { manager.toggleMockPedaling() }
    func disconnect() { manager.disconnect() }
    func forget() { onForget() }
}

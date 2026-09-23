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

    var isConnected: Bool { state == .connected }
    var isScanning: Bool { state == .scanning }

    @ObservationIgnored private let manager = TrainerManager()
    @ObservationIgnored private var watching: [Watching] = []

    init() {
        watch(manager.connectionState) { [unowned self] (value: TrainerConnectionState?) in
            state = value ?? .disconnected
        }
        watch(manager.discoveredDevices) { [unowned self] (value: NSArray?) in
            candidates = (value as? [TrainerCandidate]) ?? []
        }
        watch(manager.connectedTrainerName) { [unowned self] (value: NSString?) in
            trainerName = value as String?
        }
        watch(manager.isMockTrainer) { [unowned self] (value: KotlinBoolean?) in
            isMock = value?.boolValue ?? false
        }
        watch(manager.power) { [unowned self] (value: KotlinInt?) in power = value?.intValue ?? 0 }
        watch(manager.cadence) { [unowned self] (value: KotlinInt?) in cadence = value?.intValue ?? 0 }
        watch(manager.heartRate) { [unowned self] (value: KotlinInt?) in heartRate = value?.intValue ?? 0 }
    }

    func startScan() { manager.startScan() }
    func stopScan() { manager.stopScan() }
    func connect(_ candidate: TrainerCandidate) { manager.connectToCandidate(candidateId: candidate.id) }
    func connectMock() { manager.connectMock() }
    func toggleMockPedaling() { manager.toggleMockPedaling() }
    func disconnect() { manager.disconnect() }

    /// FlowWatcher calls back on the main thread (Dispatchers.Main), which is
    /// what makes `assumeIsolated` true rather than hopeful.
    private func watch<T: AnyObject>(
        _ flow: any Kotlinx_coroutines_coreFlow,
        _ onEach: @escaping @MainActor (T?) -> Void
    ) {
        let handle = FlowWatcher<T>(flow: flow).watch { value in
            MainActor.assumeIsolated { onEach(value) }
        }
        watching.append(handle)
    }
}

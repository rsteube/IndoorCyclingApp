import Foundation
import Observation
import shared

/// A Bluetooth device the app pairs with: the trainer, a heart rate monitor, or
/// a controller. The pairing screen and the device sheet work with any of them.
@MainActor
protocol PairableDevice: AnyObject {
    var state: TrainerConnectionState { get }
    var candidates: [TrainerCandidate] { get }
    var isPaired: Bool { get }
    func startScan()
    func stopScan()
    func connect(_ candidate: TrainerCandidate)
    func disconnect()
    func forget()
}

extension TrainerModel: PairableDevice {}

/// The shared `HeartRateMonitor` or `ControllerManager`, whose flows have the
/// same shape: a connection, the devices found, the one paired, and a flag for
/// a device that is connected but saying nothing.
@MainActor
@Observable
final class SensorModel: PairableDevice {
    private(set) var state: TrainerConnectionState = .disconnected
    private(set) var candidates: [TrainerCandidate] = []
    private(set) var deviceName: String?
    private(set) var pairedId: String?
    /// Connected, but no heart rate or no button presses are arriving.
    private(set) var silent = false
    /// Heart rate, for a monitor; always 0 for a controller.
    private(set) var heartRate = 0

    var isPaired: Bool { pairedId != nil }

    @ObservationIgnored private let flows = KotlinFlows()
    @ObservationIgnored private let actions: Actions

    struct Actions {
        let startScan: () -> Void
        let stopScan: () -> Void
        let connect: (String) -> Void
        let disconnect: () -> Void
        let forget: () -> Void
    }

    static func heartRateMonitor(_ monitor: HeartRateMonitor, container: IosAppContainer) -> SensorModel {
        let model = SensorModel(
            state: monitor.connectionState,
            discovered: monitor.discoveredDevices,
            paired: monitor.pairedDeviceId,
            name: monitor.connectedDeviceName,
            silent: monitor.sensorSilent,
            actions: Actions(
                startScan: monitor.startScan,
                stopScan: monitor.stopScan,
                connect: { monitor.connectToCandidate(candidateId: $0) },
                disconnect: monitor.disconnect,
                forget: container.forgetHeartRateMonitor
            )
        )
        model.flows.watchInt(monitor.heartRate) { [unowned model] in model.heartRate = $0 }
        return model
    }

    static func controller(_ controller: ControllerManager, container: IosAppContainer) -> SensorModel {
        SensorModel(
            state: controller.connectionState,
            discovered: controller.discoveredDevices,
            paired: controller.pairedDeviceId,
            name: controller.connectedDeviceName,
            silent: controller.keypadSilent,
            actions: Actions(
                startScan: controller.startScan,
                stopScan: controller.stopScan,
                connect: { controller.connectToCandidate(candidateId: $0) },
                disconnect: controller.disconnect,
                forget: container.forgetController
            )
        )
    }

    private init(
        state: any Kotlinx_coroutines_coreFlow,
        discovered: any Kotlinx_coroutines_coreFlow,
        paired: any Kotlinx_coroutines_coreFlow,
        name: any Kotlinx_coroutines_coreFlow,
        silent: any Kotlinx_coroutines_coreFlow,
        actions: Actions
    ) {
        self.actions = actions
        flows.watch(state) { [unowned self] (value: TrainerConnectionState?) in self.state = value ?? .disconnected }
        flows.watch(discovered) { [unowned self] (value: NSArray?) in candidates = (value as? [TrainerCandidate]) ?? [] }
        flows.watch(paired) { [unowned self] (value: NSString?) in pairedId = value as String? }
        flows.watch(name) { [unowned self] (value: NSString?) in deviceName = value as String? }
        flows.watch(silent) { [unowned self] (value: KotlinBoolean?) in self.silent = value?.boolValue ?? false }
    }

    func startScan() { actions.startScan() }
    func stopScan() { actions.stopScan() }
    func connect(_ candidate: TrainerCandidate) { actions.connect(candidate.id) }
    func disconnect() { actions.disconnect() }
    func forget() { actions.forget() }
}

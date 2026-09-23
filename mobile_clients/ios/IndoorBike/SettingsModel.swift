import Foundation
import Observation
import shared

/// The rider's numbers the ride depends on: FTP sets every ERG target, and
/// weight feeds the distance estimate.
@MainActor
@Observable
final class SettingsModel {
    private(set) var ftp = 0
    private(set) var weightKg: Float = 0
    private(set) var maxHr = 0
    private(set) var bikeWeightKg: Float = 0
    private(set) var drivetrainId = ""
    private(set) var hasCompletedOnboarding = true
    private(set) var hasDismissedStarterCard = true

    @ObservationIgnored private let settings: IosUserSettings
    @ObservationIgnored private let flows = KotlinFlows()

    init(settings: IosUserSettings) {
        self.settings = settings
        flows.watchInt(settings.ftp) { [unowned self] in ftp = $0 }
        flows.watch(settings.weightKg) { [unowned self] (value: KotlinFloat?) in weightKg = value?.floatValue ?? 0 }
        flows.watchInt(settings.maxHr) { [unowned self] in maxHr = $0 }
        flows.watch(settings.bikeWeightKg) { [unowned self] (value: KotlinFloat?) in bikeWeightKg = value?.floatValue ?? 0 }
        flows.watch(settings.drivetrainId) { [unowned self] (value: NSString?) in drivetrainId = (value as String?) ?? "" }
        flows.watch(settings.hasCompletedOnboarding) { [unowned self] (value: KotlinBoolean?) in
            hasCompletedOnboarding = value?.boolValue ?? true
        }
        flows.watch(settings.hasDismissedStarterCard) { [unowned self] (value: KotlinBoolean?) in
            hasDismissedStarterCard = value?.boolValue ?? true
        }
    }

    func setMaxHr(_ value: Int) { settings.setMaxHr(newMaxHr: Int32(value)) }
    func setBikeWeightKg(_ value: Float) { settings.setBikeWeightKg(value: value) }
    func setDrivetrain(_ id: String) { settings.setDrivetrainId(id: id) }
    func finishOnboarding() { settings.setHasCompletedOnboarding(value: true) }
    func dismissStarterCard() { settings.setHasDismissedStarterCard(value: true) }

    func setFtp(_ value: Int) { settings.setFtp(newFtp: Int32(value)) }
    func setWeightKg(_ value: Float) { settings.setWeightKg(newWeightKg: value) }
}

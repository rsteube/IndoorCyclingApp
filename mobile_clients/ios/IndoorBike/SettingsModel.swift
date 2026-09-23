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

    @ObservationIgnored private let settings: IosUserSettings
    @ObservationIgnored private let flows = KotlinFlows()

    init(settings: IosUserSettings) {
        self.settings = settings
        flows.watchInt(settings.ftp) { [unowned self] in ftp = $0 }
        flows.watch(settings.weightKg) { [unowned self] (value: KotlinFloat?) in weightKg = value?.floatValue ?? 0 }
    }

    func setFtp(_ value: Int) { settings.setFtp(newFtp: Int32(value)) }
    func setWeightKg(_ value: Float) { settings.setWeightKg(newWeightKg: value) }
}

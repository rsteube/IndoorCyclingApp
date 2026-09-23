import Foundation
import Observation
import shared

/// The backup folder and the upload services, as Settings and the ride
/// detail show them.
@MainActor
@Observable
final class SyncModel {
    private(set) var status: SyncStatus = SyncStatus.Idle.shared
    private(set) var folder: String?
    private(set) var paused = false
    private(set) var deviceLabel = ""
    private(set) var inFlight: Set<String> = []
    /// Bumped when an upload is recorded on a ride, so its detail reloads.
    private(set) var recordedCount = 0

    // Intervals.icu
    private(set) var intervalsAthleteId = ""
    private(set) var intervalsApiKey = ""
    private(set) var intervalsToken = ""
    private(set) var intervalsAthleteName = ""
    private(set) var intervalsAutoUpload = false
    // FitTrackee
    private(set) var fitTrackeeUrl = ""
    private(set) var fitTrackeeClientId = ""
    private(set) var fitTrackeeClientSecret = ""
    private(set) var fitTrackeeToken = ""
    private(set) var fitTrackeeSportId = 13
    private(set) var fitTrackeeAutoUpload = false

    var intervalsConfigured: Bool { !intervalsToken.isEmpty || (!intervalsAthleteId.isEmpty && !intervalsApiKey.isEmpty) }
    var fitTrackeeConnected: Bool { !fitTrackeeToken.isEmpty }

    @ObservationIgnored let settings: IosUserSettings
    @ObservationIgnored private let flows = KotlinFlows()

    init(container: IosAppContainer) {
        settings = container.userSettings
        let s = settings
        flows.watch(container.syncEngine.status) { [unowned self] (value: SyncStatus?) in status = value ?? SyncStatus.Idle.shared }
        flows.watch(s.syncFolderUri) { [unowned self] (value: NSString?) in folder = value as String? }
        flows.watch(s.syncPaused) { [unowned self] (value: KotlinBoolean?) in paused = value?.boolValue ?? false }
        flows.watch(s.deviceLabel) { [unowned self] (value: NSString?) in deviceLabel = (value as String?) ?? "" }
        flows.watch(container.rideUploads.inFlight) { [unowned self] (value: NSSet?) in
            inFlight = Set((value?.allObjects ?? []).compactMap { item in
                guard let pair = item as? KotlinPair<KotlinLong, RideUploads.Service> else { return nil }
                return "\(pair.first?.int64Value ?? 0):\(pair.second?.name ?? "")"
            })
        }
        flows.watch(container.rideUploads.recorded) { [unowned self] (_: KotlinLong?) in recordedCount += 1 }
        func string(_ flow: any Kotlinx_coroutines_coreFlow, _ set: @escaping @MainActor (String) -> Void) {
            flows.watch(flow) { (value: NSString?) in set((value as String?) ?? "") }
        }
        string(s.intervalsAthleteId) { [unowned self] in intervalsAthleteId = $0 }
        string(s.intervalsApiKey) { [unowned self] in intervalsApiKey = $0 }
        string(s.intervalsAccessToken) { [unowned self] in intervalsToken = $0 }
        string(s.intervalsAthleteName) { [unowned self] in intervalsAthleteName = $0 }
        string(s.fitTrackeeUrl) { [unowned self] in fitTrackeeUrl = $0 }
        string(s.fitTrackeeClientId) { [unowned self] in fitTrackeeClientId = $0 }
        string(s.fitTrackeeClientSecret) { [unowned self] in fitTrackeeClientSecret = $0 }
        string(s.fitTrackeeAccessToken) { [unowned self] in fitTrackeeToken = $0 }
        flows.watchInt(s.fitTrackeeSportId) { [unowned self] in fitTrackeeSportId = $0 }
        flows.watch(s.intervalsAutoUpload) { [unowned self] (value: KotlinBoolean?) in intervalsAutoUpload = value?.boolValue ?? false }
        flows.watch(s.fitTrackeeAutoUpload) { [unowned self] (value: KotlinBoolean?) in fitTrackeeAutoUpload = value?.boolValue ?? false }
    }

    func uploading(_ sessionId: Int64, _ service: RideUploads.Service) -> Bool {
        inFlight.contains("\(sessionId):\(service.name)")
    }

    /// "Synced 2 min ago" and friends, as the Android card says it.
    static func relative(_ epochMs: Int64) -> String {
        let seconds = max(0, Int(Date.now.timeIntervalSince1970) - Int(epochMs / 1000))
        switch seconds {
        case ..<5: return "just now"
        case ..<60: return "\(seconds)s ago"
        case ..<3600: return "\(seconds / 60) min ago"
        case ..<86400: return "\(seconds / 3600)h ago"
        default: return "\(seconds / 86400)d ago"
        }
    }
}

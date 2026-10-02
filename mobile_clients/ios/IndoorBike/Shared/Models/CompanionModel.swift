import Foundation
import Network
import Observation
import SwiftUI
import UIKit
import shared

/// Companion mode, from the shared `CompanionHost` and `CompanionEngine`: the
/// socket, who is connected, and the road the game is describing.
@MainActor
@Observable
final class CompanionModel {
    private(set) var status = CompanionStatus(
        running: false, port: 0, token: "", sessionId: "", addresses: [], advertising: false,
        controllerName: nil, controllerAddress: nil, observers: 0, receivingRoad: false, roadStale: false,
        label: nil, world: nil, error: nil, epoch: 0
    )
    private(set) var controls: RemoteControls?
    private(set) var pendingPairing: CompanionHost.PendingPairing?
    private(set) var paired: [PairedCompanion] = []
    private(set) var sessionRides: [SavedCompanionRide] = []
    private(set) var phase: CompanionHost.SessionPhase = .live
    private(set) var companionName: String?
    private(set) var lookName: String?
    private(set) var icon: UIImage?
    private(set) var accent: Color?
    private(set) var screenshotMessage: String?
    private(set) var screenshotWaiting = false
    private(set) var screenshotsOffered = false
    private(set) var hosting = false
    private(set) var showAllData = false

    // The road
    private(set) var gradient = 0.0
    private(set) var speedMps = 0.0
    private(set) var distanceMeters = 0.0
    private(set) var climbedMeters = 0.0
    private(set) var gearIndex = 0
    private(set) var drivetrain: Drivetrain?
    private(set) var roadStale = false
    private(set) var powerTarget: Int?

    @ObservationIgnored let host: CompanionHost
    @ObservationIgnored private let engine: CompanionEngine
    @ObservationIgnored private let settings: IosUserSettings
    @ObservationIgnored private let flows = KotlinFlows()
    /// Wi-Fi joined, lost or swapped: the addresses shown and put in the QR follow.
    @ObservationIgnored private let pathMonitor = NWPathMonitor()

    init(container: IosAppContainer) {
        host = container.companionHost
        engine = container.companionEngine
        settings = container.userSettings
        flows.watch(host.status) { [unowned self] (value: CompanionStatus?) in if let value { status = value } }
        flows.watch(host.remoteControls) { [unowned self] (value: RemoteControls?) in controls = value }
        flows.watch(host.pendingPairing) { [unowned self] (value: CompanionHost.PendingPairing?) in pendingPairing = value }
        flows.watch(host.pairedCompanions) { [unowned self] (value: NSArray?) in paired = (value as? [PairedCompanion]) ?? [] }
        flows.watch(host.sessionRides) { [unowned self] (value: NSArray?) in sessionRides = (value as? [SavedCompanionRide]) ?? [] }
        flows.watch(host.sessionPhase) { [unowned self] (value: CompanionHost.SessionPhase?) in phase = value ?? .live }
        flows.watch(host.sessionCompanionName) { [unowned self] (value: NSString?) in companionName = value as String? }
        flows.watch(host.look) { [unowned self] (value: CompanionHost.Look?) in
            lookName = value?.name
            icon = value?.icon as? UIImage
            accent = value?.palette.map { Color(argb: $0.accent) }
        }
        flows.watch(host.screenshotStatus) { [unowned self] (value: CompanionHost.ScreenshotStatus?) in
            screenshotMessage = value?.message
            screenshotWaiting = value?.kind == .waiting
        }
        flows.watch(host.screenshotsOffered) { [unowned self] (value: KotlinBoolean?) in screenshotsOffered = value?.boolValue ?? false }
        flows.watch(host.hosting) { [unowned self] (value: KotlinBoolean?) in hosting = value?.boolValue ?? false }
        flows.watch(settings.companionShowAllData) { [unowned self] (value: KotlinBoolean?) in showAllData = value?.boolValue ?? false }
        flows.watch(engine.gradient) { [unowned self] (value: KotlinDouble?) in gradient = value?.doubleValue ?? 0 }
        flows.watch(engine.speedMps) { [unowned self] (value: KotlinDouble?) in speedMps = value?.doubleValue ?? 0 }
        flows.watch(engine.distanceMeters) { [unowned self] (value: KotlinDouble?) in distanceMeters = value?.doubleValue ?? 0 }
        flows.watch(engine.elevationGainMeters) { [unowned self] (value: KotlinDouble?) in climbedMeters = value?.doubleValue ?? 0 }
        flows.watchInt(engine.gearIndex) { [unowned self] in gearIndex = $0 }
        flows.watch(engine.drivetrain) { [unowned self] (value: Drivetrain?) in drivetrain = value }
        flows.watch(engine.roadStale) { [unowned self] (value: KotlinBoolean?) in roadStale = value?.boolValue ?? false }
        flows.watch(engine.powerTarget) { [unowned self] (value: KotlinInt?) in powerTarget = value.map { Int(truncating: $0) } }
        let host = host
        pathMonitor.pathUpdateHandler = { _ in host.refreshAddresses() }
        pathMonitor.start(queue: DispatchQueue(label: "app.indoorbike.companion.path"))
    }

    /// The name the rider knows the game by: its brand's, else what it said in hello.
    var displayName: String? { lookName ?? status.controllerName ?? companionName }

    func setShowAllData(_ on: Bool) { settings.setCompanionShowAllData(value: on) }
    func shift(_ steps: Int) { engine.shiftBy(steps: Int32(steps), source: "screen") }
}

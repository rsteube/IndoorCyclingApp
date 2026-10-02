import CoreLocation
import Foundation
import Observation
import shared

/// The routes the rider can ride here, and where they got to on each.
@MainActor
@Observable
final class RouteLibraryModel {
    private(set) var routes: [RouteTrack] = []
    private(set) var sources: [String: RouteSource] = [:]
    private(set) var loaded = false
    private(set) var progress: [String: RouteProgress] = [:]

    @ObservationIgnored private let container: IosAppContainer
    @ObservationIgnored private let flows = KotlinFlows()
    /// Card numbers per route, worked out once: a route's shape and profile
    /// come from thousands of points.
    @ObservationIgnored private var entries: [String: RouteEntry] = [:]

    init(container: IosAppContainer) {
        self.container = container
        let library = container.routeLibrary
        flows.watch(library.routes) { [unowned self] (value: NSArray?) in
            routes = (value as? [RouteTrack]) ?? []
            reloadProgress()
        }
        flows.watch(library.sources) { [unowned self] (value: NSDictionary?) in
            sources = (value as? [String: RouteSource]) ?? [:]
        }
        flows.watch(library.initialised) { [unowned self] (value: KotlinBoolean?) in loaded = value?.boolValue ?? false
        }
    }

    func entry(_ route: RouteTrack) -> RouteEntry {
        if let cached = entries[route.id] { return cached }
        let made = container.routeEntry(route: route)
        entries[route.id] = made
        return made
    }

    func source(_ route: RouteTrack) -> RouteSource {
        sources[route.id] ?? RouteSource.Bundled.shared
    }

    func estimateSeconds(_ entry: RouteEntry) -> Int? {
        container.routeEstimateSeconds(entry: entry).map { Int(truncating: $0) }
    }

    /// Where the rider got to: read again whenever the ride state may have moved it.
    func reloadProgress() {
        container.loadRouteProgress { [weak self] map in
            MainActor.assumeIsolated { self?.progress = map as [String: RouteProgress] }
        }
    }

    func delete(_ route: RouteTrack, onDone: @escaping (Bool) -> Void) {
        container.deleteRoute(routeId: route.id) { removed in
            MainActor.assumeIsolated { onDone(removed.boolValue) }
        }
    }

    func importFile(_ url: URL, onDone: @escaping (String?) -> Void) {
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else {
            onDone("Couldn't open that file.")
            return
        }
        container.importRouteFile(fileName: url.lastPathComponent, data: data) { result in
            MainActor.assumeIsolated { onDone((result as? RouteImportResult.Failure)?.message) }
        }
    }
}

/// The road being ridden, from the shared `SimulationEngine`, for the route HUD.
@MainActor
@Observable
final class SimulationModel {
    private(set) var route: RouteTrack?
    private(set) var position: RoutePosition?
    private(set) var distanceMeters = 0.0
    private(set) var startedAtMeters = 0.0
    private(set) var gradient = 0.0
    private(set) var speedMps = 0.0
    private(set) var gearIndex = 0
    private(set) var drivetrain: Drivetrain?
    private(set) var elevationGainMeters = 0.0
    private(set) var startedAtElevationGainMeters = 0.0

    /// The route's points as map coordinates, made once per route.
    private(set) var coordinates: [CLLocationCoordinate2D] = []
    @ObservationIgnored private(set) var pointDistances: [Double] = []

    @ObservationIgnored private let engine: SimulationEngine
    @ObservationIgnored private let flows = KotlinFlows()

    init(engine: SimulationEngine) {
        self.engine = engine
        flows.watch(engine.route) { [unowned self] (value: RouteTrack?) in
            guard value?.id != route?.id || (value == nil) != (route == nil) else { return }
            route = value
            let points = value?.points ?? []
            coordinates = points.map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) }
            pointDistances = points.map(\.distanceMeters)
        }
        flows.watch(engine.position) { [unowned self] (value: RoutePosition?) in position = value }
        flows.watch(engine.distanceMeters) { [unowned self] (value: KotlinDouble?) in
            distanceMeters = value?.doubleValue ?? 0
        }
        flows.watch(engine.startedAtMeters) { [unowned self] (value: KotlinDouble?) in
            startedAtMeters = value?.doubleValue ?? 0
        }
        flows.watch(engine.gradient) { [unowned self] (value: KotlinDouble?) in gradient = value?.doubleValue ?? 0 }
        flows.watch(engine.speedMps) { [unowned self] (value: KotlinDouble?) in speedMps = value?.doubleValue ?? 0 }
        flows.watchInt(engine.gearIndex) { [unowned self] in gearIndex = $0 }
        flows.watch(engine.drivetrain) { [unowned self] (value: Drivetrain?) in drivetrain = value }
        flows.watch(engine.elevationGainMeters) { [unowned self] (value: KotlinDouble?) in
            elevationGainMeters = value?.doubleValue ?? 0
        }
        flows.watch(engine.startedAtElevationGainMeters) { [unowned self] (value: KotlinDouble?) in
            startedAtElevationGainMeters = value?.doubleValue ?? 0
        }
    }

    /// How many of the route's points are behind the rider.
    func riddenCount(at distance: Double) -> Int {
        var low = 0
        var high = pointDistances.count
        while low < high {
            let mid = (low + high) / 2
            if pointDistances[mid] <= distance { low = mid + 1 } else { high = mid }
        }
        return low
    }

    func shift(_ steps: Int) { engine.shiftBy(steps: Int32(steps)) }
}

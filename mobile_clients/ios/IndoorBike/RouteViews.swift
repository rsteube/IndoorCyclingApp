import SwiftUI
import UniformTypeIdentifiers
import shared

/// Every route the rider can ride, with how far they got on each.
struct RouteListView: View {
    @Bindable var app: AppModel
    @State private var importing = false
    @State private var message: String?
    @State private var deleting: RouteTrack?

    var body: some View {
        let library = app.routes
        List(selection: $app.routeSelection) {
            if library.routes.isEmpty {
                ContentUnavailableView {
                    Label(library.loaded ? "No routes yet." : "Loading routes…", systemImage: "mountain.2")
                } description: {
                    if library.loaded {
                        Text("Add a road from a community repository, or a GPX file, and the trainer will ride its hills.")
                    }
                }
            }
            ForEach(library.routes, id: \.id) { route in
                RouteRow(route: route, library: library, trainerLive: app.trainer.isConnected)
                    .tag(route.id)
                    .swipeActions {
                        if library.source(route) is RouteSource.Imported {
                            Button("Remove", role: .destructive) { deleting = route }
                        }
                    }
            }
            Section {
                NavigationLink("Find more routes") { CommunityRoutesView(app: app) }
                Button("Add a GPX file…") { importing = true }
            }
        }
        .navigationTitle("Routes")
        .fileImporter(isPresented: $importing, allowedContentTypes: [UTType(filenameExtension: "gpx") ?? .xml, .xml]) { result in
            if case .success(let url) = result {
                library.importFile(url) { error in message = error }
            }
        }
        .alert("Couldn't add that route", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(message ?? "")
        }
        .confirmationDialog("Remove this route?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
            Button("Remove", role: .destructive) {
                if let route = deleting {
                    library.delete(route) { _ in
                        if app.routeSelection == route.id { app.routeSelection = nil }
                    }
                }
            }
            Button("Keep it", role: .cancel) {}
        } message: {
            if let route = deleting {
                Text("\"\(route.name)\" will be deleted from this device\(library.progress[route.id] != nil ? ", and where you got to on it with it" : ""). You can add it again from where it came from. Rides you have already done on it stay in your history.")
            }
        }
        .onAppear { library.reloadProgress() }
    }
}

private struct RouteRow: View {
    let route: RouteTrack
    let library: RouteLibraryModel
    let trainerLive: Bool

    var body: some View {
        let entry = library.entry(route)
        let source = library.source(route)
        let saved = library.progress[route.id]
        HStack(alignment: .top, spacing: 12) {
            RouteShapeView(latLons: entry.shapeLatLons, lineWidth: 2)
                .frame(width: 56, height: 56)
                .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 4) {
                Text(source.label.uppercased()).font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
                Text(route.name).font(.headline).lineLimit(2)
                ProfileSpark(profile: entry.profile.map { $0.doubleValue }).frame(height: 28)
                RouteStatsLine(entry: entry, estimate: library.estimateSeconds(entry))
                if let saved {
                    ProgressView(value: min(saved.distanceMeters / max(route.totalDistanceMeters, 1), 1)).tint(.orange)
                    Text("\(RouteFormatKt.formatKm(meters: saved.distanceMeters)) ridden - tap to carry on or start again")
                        .font(.caption).foregroundStyle(.secondary)
                }
                if !trainerLive {
                    Text("Pair a trainer to feel this route's hills.").font(.caption).foregroundStyle(.secondary)
                }
                if let credit = source.creditLine {
                    Text(credit).font(.caption2).foregroundStyle(.tertiary)
                }
            }
        }
        .padding(.vertical, 4)
    }
}

/// Distance, climbing and how long it should take this rider.
struct RouteStatsLine: View {
    let entry: RouteEntry
    let estimate: Int?

    var body: some View {
        let time = estimate.map { WorkoutSummaryKt.formatDurationPretty(totalSeconds: Int32($0)) } ?? "--"
        Text("\(RouteFormatKt.formatKm(meters: entry.distanceMeters)) · \(Int(entry.ascentMeters.rounded())) m up · \(time)")
        .font(.subheadline.monospacedDigit())
        .foregroundStyle(.secondary)
    }
}

/// One route before it is ridden: where it goes, how it climbs, and the way in.
struct RouteDetailView: View {
    let app: AppModel
    let route: RouteTrack
    @State private var showingTrainer = false

    var body: some View {
        let library = app.routes
        let entry = library.entry(route)
        let saved = library.progress[route.id]
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                RoutePreviewMap(route: route)
                    .frame(height: 260)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                RouteStatsLine(entry: entry, estimate: library.estimateSeconds(entry))
                ElevationProfileView(route: route, showRider: false)
                    .frame(height: 110)
                HStack {
                    LabeledContent("Highest", value: "\(Int(route.maxElevationMeters.rounded())) m")
                    Divider()
                    LabeledContent("Steepest", value: String(format: "%.0f %%", entry.maxGradientPercent))
                }
                .font(.subheadline)
                if let credit = library.source(route).creditLine {
                    Text(credit).font(.caption).foregroundStyle(.secondary)
                }
                if let saved {
                    Card {
                        Text("You rode \(RouteFormatKt.formatDistance(meters: saved.distanceMeters)) of it last time, and \(RouteFormatKt.formatDistance(meters: max(route.totalDistanceMeters - saved.distanceMeters, 0))) is left.")
                        ProgressView(value: min(saved.distanceMeters / max(route.totalDistanceMeters, 1), 1)).tint(.orange)
                        Text("Continuing starts you at \(Int(saved.distanceMeters / max(route.totalDistanceMeters, 1) * 100)) % along the road. Either way, the ride you already did stays in your history.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .padding()
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
        }
        .navigationTitle(route.name)
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) {
                if !app.trainer.isConnected {
                    Button { showingTrainer = true } label: { Text("Connect a trainer to ride").frame(maxWidth: .infinity) }
                        .buttonStyle(.bordered)
                } else if let saved {
                    Button { app.startRoute(route, from: saved) } label: { Text("Continue").frame(maxWidth: .infinity) }
                        .buttonStyle(.borderedProminent)
                    Button { app.startRoute(route, from: nil) } label: { Text("From the start").frame(maxWidth: .infinity) }
                        .buttonStyle(.bordered)
                } else {
                    Button { app.startRoute(route, from: nil) } label: { Text("Ride this route").frame(maxWidth: .infinity) }
                        .buttonStyle(.borderedProminent)
                }
            }
            .controlSize(.large)
            .padding()
            .frame(maxWidth: 720)
            .background(.bar)
        }
        .sheet(isPresented: $showingTrainer) { DeviceSheet(kind: .trainer, app: app) }
    }
}

/// The community registry's repositories that publish routes.
struct CommunityRoutesView: View {
    let app: AppModel
    @State private var repositories: [RegistryEntry]?
    @State private var error: String?

    var body: some View {
        List {
            if let error, repositories?.isEmpty ?? true {
                Text("Couldn't reach the community list: \(error)").foregroundStyle(.secondary)
            }
            if let repositories {
                let withRoutes = repositories.filter(\.hasRoutes)
                if withRoutes.isEmpty && error == nil {
                    Text("No community repositories publish routes yet.").foregroundStyle(.secondary)
                }
                ForEach(withRoutes, id: \.id) { repo in
                    NavigationLink {
                        RepoRoutesView(app: app, repo: repo)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(repo.name).font(.headline)
                            if let description = repo.description_ { Text(description).font(.subheadline).foregroundStyle(.secondary) }
                        }
                    }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Community routes")
        .task {
            app.container.loadRegistry { index, failure in
                MainActor.assumeIsolated {
                    repositories = index?.repositories ?? []
                    error = failure
                }
            }
        }
    }
}

/// One repository's routes, each one tap from being in the rider's library.
struct RepoRoutesView: View {
    let app: AppModel
    let repo: RegistryEntry
    @State private var manifest: RepositoryManifest?
    @State private var routes: [RouteEntry]?
    @State private var failure: String?
    @State private var adding: Set<String> = []
    @State private var added: Set<String> = []
    @State private var message: String?

    var body: some View {
        List {
            if let failure { Text(failure).foregroundStyle(.secondary) }
            if let routes {
                if routes.isEmpty { Text("This repository does not publish any routes yet.").foregroundStyle(.secondary) }
                ForEach(routes, id: \.id) { entry in
                    HStack(alignment: .top, spacing: 12) {
                        RouteShapeView(latLons: entry.shapeLatLons, lineWidth: 2)
                            .frame(width: 56, height: 56)
                            .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 10))
                        VStack(alignment: .leading, spacing: 4) {
                            Text(entry.name).font(.headline)
                            if let place = entry.place { Text(place).font(.subheadline).foregroundStyle(.secondary) }
                            ProfileSpark(profile: entry.profile.map { $0.doubleValue }).frame(height: 28)
                            RouteStatsLine(entry: entry, estimate: app.routes.estimateSeconds(entry))
                            if let description = entry.description_ { Text(description).font(.caption).foregroundStyle(.secondary) }
                            if let credit = entry.attribution?.creditLine { Text(credit).font(.caption2).foregroundStyle(.tertiary) }
                        }
                        Spacer(minLength: 0)
                        addButton(entry)
                    }
                    .padding(.vertical, 4)
                }
            } else if failure == nil {
                ProgressView()
            }
        }
        .navigationTitle(manifest?.name ?? repo.name)
        .alert("Couldn't add that route", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(message ?? "") }
        .task {
            app.container.fetchRepoRoutes(manifestUrl: repo.manifestUrl) { result in
                MainActor.assumeIsolated {
                    if let ok = result as? RouteFetchResult.Ok {
                        manifest = ok.manifest
                        routes = ok.routes
                    } else if let fail = result as? RouteFetchResult.Failure {
                        failure = fail.message
                    }
                }
            }
        }
    }

    private func installed(_ entry: RouteEntry) -> Bool {
        if added.contains(entry.id) { return true }
        let repoId = manifest?.id ?? repo.id
        return app.routes.sources.values.contains { source in
            guard let imported = source as? RouteSource.Imported else { return false }
            return imported.sourceRepoId == repoId && imported.sourceEntryId == entry.id
        }
    }

    @ViewBuilder
    private func addButton(_ entry: RouteEntry) -> some View {
        if adding.contains(entry.id) {
            ProgressView()
        } else if installed(entry) {
            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green).accessibilityLabel("Added")
        } else {
            Button {
                adding.insert(entry.id)
                app.container.addRouteFromRepo(entry: entry, repoId: manifest?.id ?? repo.id, repoName: manifest?.name ?? repo.name) { result in
                    MainActor.assumeIsolated {
                        adding.remove(entry.id)
                        if result is RouteImportResult.Added { added.insert(entry.id) }
                        if let failure = result as? RouteImportResult.Failure { message = failure.message }
                    }
                }
            } label: {
                Image(systemName: "icloud.and.arrow.down").font(.title3)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Add \(entry.name)")
        }
    }
}

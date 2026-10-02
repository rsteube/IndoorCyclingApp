import SwiftUI
import UniformTypeIdentifiers
import VisionKit
import shared

/// The workout library: search, filters, favourites, and the way to more.
struct WorkoutListView: View {
    @Bindable var app: AppModel
    @State private var search = ""
    @State private var filter = "All"

    var body: some View {
        let library = app.library
        let ftp = app.settings.ftp
        let summaries = app.workouts.map { ($0, $0.toSummary(ftpWatts: Int32(ftp))) }
        let tags = Array(Set(summaries.map { $0.1.tag })).sorted()
        let filters = ["All", "≤ 30 min", "30-60", "60+"] + (library.favourites.isEmpty ? [] : ["★"]) + tags
        let shown = summaries.filter { workout, summary in
            let matchesSearch =
                search.isEmpty
                || workout.name.localizedCaseInsensitiveContains(search)
                || summary.tag.localizedCaseInsensitiveContains(search)
            let seconds = Int(summary.totalSeconds)
            let matchesFilter: Bool =
                switch filter {
                case "≤ 30 min": seconds <= 1800
                case "30-60": seconds > 1800 && seconds <= 3600
                case "60+": seconds > 3600
                case "★": library.favourites.contains(workout.id)
                case "All": true
                default: summary.tag == filter
                }
            return matchesSearch && matchesFilter
        }.map(\.0)

        List(selection: $app.workoutSelection) {
            Section {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(filters, id: \.self) { name in
                            Button(name) { filter = name }
                                .buttonStyle(.bordered)
                                .tint(filter == name ? .accentColor : .secondary)
                        }
                    }
                }
                .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
            }
            ForEach(shown, id: \.id) { workout in
                WorkoutRow(
                    workout: workout,
                    ftp: ftp,
                    favourite: library.favourites.contains(workout.id),
                    toggleFavourite: { library.toggleFavourite(workout.id) }
                )
                .tag(workout.id)
            }
            if shown.isEmpty {
                Text(search.isEmpty ? "No workouts match this filter." : "No workouts match \"\(search)\".")
                    .foregroundStyle(.secondary)
            }
            Section {
                NavigationLink {
                    GetWorkoutsView(app: app)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Want more workouts?")
                        Text("Community library, QR code, or a file.").font(.subheadline).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .searchable(text: $search, prompt: "Search workouts")
        .navigationTitle("Workouts")
        .onChange(of: filters) { _, now in if !now.contains(filter) { filter = "All" } }
    }
}

struct WorkoutRow: View {
    let workout: Workout
    let ftp: Int
    var favourite = false
    var toggleFavourite: (() -> Void)? = nil

    var body: some View {
        let summary = workout.toSummary(ftpWatts: Int32(ftp))
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 6) {
                Text(workout.name).font(.headline)
                Text(
                    "\(WorkoutSummaryKt.formatDurationPretty(totalSeconds: summary.totalSeconds)) · \(summary.tag) · TSS \(summary.tss)"
                )
                .font(.subheadline)
                .foregroundStyle(.secondary)
                WorkoutProfile(segments: workout.segments).frame(height: 28)
            }
            if let toggleFavourite {
                Button(action: toggleFavourite) {
                    Image(systemName: favourite ? "star.fill" : "star")
                        .foregroundStyle(favourite ? .yellow : .secondary)
                }
                .buttonStyle(.borderless)
                .sensoryFeedback(.selection, trigger: favourite)
                .accessibilityLabel(favourite ? "Remove from favourites" : "Add to favourites")
            }
        }
        .padding(.vertical, 4)
    }
}

/// "Get workouts": from the web editor's QR, a file, a link, or the community.
struct GetWorkoutsView: View {
    let app: AppModel
    @State private var scanning = false
    @State private var importing = false
    @State private var pasting = false
    @State private var link = ""
    @State private var registry: [RegistryEntry]?
    @State private var registryError: String?
    @State private var confirmDelete: Workout?

    var body: some View {
        List {
            Section {
                Button {
                    scanning = true
                } label: {
                    Label {
                        VStack(alignment: .leading) {
                            Text("Scan from web editor")
                            Text("Point at the QR on indoorbike.app/editor").font(.subheadline).foregroundStyle(
                                .secondary
                            )
                        }
                    } icon: {
                        Image(systemName: "qrcode.viewfinder")
                    }
                }
                Button {
                    importing = true
                } label: {
                    Label("Import file", systemImage: "doc.badge.plus")
                }
                Button {
                    pasting = true
                } label: {
                    Label("Paste URL", systemImage: "link")
                }
            }

            Section {
                if let registry {
                    ForEach(registry, id: \.id) { repo in
                        NavigationLink {
                            RepoWorkoutsView(app: app, manifestUrl: repo.manifestUrl, fallbackName: repo.name)
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                if let author = repo.author {
                                    Text(author.uppercased()).font(.caption2.weight(.semibold)).foregroundStyle(
                                        .secondary
                                    )
                                }
                                Text(repo.name).font(.headline)
                                if let description = repo.description_ {
                                    Text(description).font(.subheadline).foregroundStyle(.secondary).lineLimit(3)
                                }
                            }
                        }
                    }
                    if registry.isEmpty {
                        Text(
                            registryError.map { "Couldn't reach the registry. \($0)" }
                                ?? "No community repositories yet."
                        )
                        .foregroundStyle(.secondary)
                    }
                } else {
                    ProgressView("Fetching registry…")
                }
            } header: {
                Text("Browse community")
            } footer: {
                if registryError != nil, !(registry?.isEmpty ?? true) { Text("Showing a saved copy.") }
            }

            let imported = app.library.entries.filter { $0.source is WorkoutSource.Imported }.map(\.workout)
            if !imported.isEmpty {
                Section("Your imported workouts") {
                    ForEach(imported, id: \.id) { workout in
                        Text(workout.name)
                            .swipeActions { Button("Delete", role: .destructive) { confirmDelete = workout } }
                    }
                }
            }
        }
        .navigationTitle("Get workouts")
        .task { loadRegistry() }
        .refreshable { loadRegistry() }
        .sheet(isPresented: $scanning) {
            QRScanner { text in
                scanning = false
                app.open(URL(string: text) ?? URL(string: "about:blank")!)
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .plainText, .data]) { result in
            if case .success(let url) = result { app.open(url) }
        }
        .alert("Paste a repository or workout URL", isPresented: $pasting) {
            TextField("https://example.com/indoorbike.json", text: $link)
                .textInputAutocapitalization(.never)
                .keyboardType(.URL)
            Button("Add") { add(link) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Must be HTTPS. Workouts import directly. Repository URLs open so you can pick which workouts to add.")
        }
        .confirmationDialog(
            "Delete \"\(confirmDelete?.name ?? "")\"?",
            isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } }),
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive) {
                if let workout = confirmDelete {
                    app.container.deleteWorkout(workoutId: workout.id) { _ in }
                }
            }
        } message: {
            Text("The workout file will be removed from your device. Past rides of this workout stay in your history.")
        }
    }

    private func loadRegistry() {
        app.container.loadRegistry { index, error in
            MainActor.assumeIsolated {
                registry = index?.repositories ?? []
                registryError = error
            }
        }
    }

    private func add(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        link = ""
        app.container.importWorkoutLink(link: trimmed) { result in
            MainActor.assumeIsolated {
                if result is ImportResult.Manifest {
                    app.show("That's a repository: browse it from Repositories on the Ride tab, or follow it there.")
                } else {
                    app.show(result.notice)
                    if let workout = result.singleWorkout { app.showWorkout(workout.id) }
                }
            }
        }
    }
}

/// One repository's workouts, to pick from; nothing enters the library until
/// the rider taps Add.
struct RepoWorkoutsView: View {
    let app: AppModel
    let manifestUrl: String
    let fallbackName: String
    @State private var manifest: RepositoryManifest?
    @State private var workouts: [Workout]?
    @State private var failure: String?
    @State private var duration = "Any"
    @State private var tag = "All"

    var body: some View {
        let ftp = app.settings.ftp
        List {
            if let failure {
                Text("Couldn't load this repository.").font(.headline)
                Text(failure).foregroundStyle(.secondary)
                Button("Retry") { load() }
            } else if let workouts {
                if let description = manifest?.description_ {
                    Text(description).foregroundStyle(.secondary)
                }
                let summaries = workouts.map { ($0, $0.toSummary(ftpWatts: Int32(ftp))) }
                let tags = ["All"] + Array(Set(summaries.map { $0.1.tag })).sorted()
                Section {
                    Picker("Duration", selection: $duration) {
                        ForEach(["Any", "Quick", "30–60", "60–90", "Long"], id: \.self) { Text($0) }
                    }
                    Picker("Kind", selection: $tag) {
                        ForEach(tags, id: \.self) { Text($0) }
                    }
                }
                let shown = summaries.filter { _, summary in
                    let minutes = Int(summary.totalSeconds) / 60
                    let okDuration: Bool =
                        switch duration {
                        case "Quick": minutes < 30
                        case "30–60": minutes >= 30 && minutes < 60
                        case "60–90": minutes >= 60 && minutes < 90
                        case "Long": minutes >= 90
                        default: true
                        }
                    return okDuration && (tag == "All" || summary.tag == tag)
                }
                if workouts.isEmpty {
                    Text("This repository has no readable workouts yet.").foregroundStyle(.secondary)
                } else if shown.isEmpty {
                    Text("No workouts match these filters. Try widening the duration or tag.").foregroundStyle(
                        .secondary
                    )
                }
                ForEach(shown, id: \.0.id) { workout, summary in
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(summary.tag.uppercased()).font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
                            Text(workout.name).font(.headline)
                            if let description = workout.description_ {
                                Text(description).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                            }
                            WorkoutProfile(segments: workout.segments).frame(height: 28)
                            Text(
                                "\(WorkoutSummaryKt.formatDurationPretty(totalSeconds: summary.totalSeconds)) · TSS \(summary.tss) · IF \(String(format: "%.2f", summary.intensityFactor))"
                            )
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                        }
                        Spacer()
                        if app.library.isImported(workout.id) {
                            Text("Added").font(.caption.weight(.semibold)).foregroundStyle(.green)
                        } else {
                            Button("Add") { add(workout) }.buttonStyle(.bordered)
                        }
                    }
                    .padding(.vertical, 4)
                }
            } else {
                ProgressView("Fetching workouts…")
            }
        }
        .navigationTitle(manifest?.name ?? fallbackName)
        .task { load() }
    }

    private func load() {
        failure = nil
        app.container.fetchRepoWorkouts(manifestUrl: manifestUrl) { result in
            MainActor.assumeIsolated {
                if let ok = result as? RepoFetchResult.Ok {
                    manifest = ok.manifest
                    workouts = ok.workouts
                    app.container.followedRepositories.markSeen(repoId: ok.manifest.id)
                } else if let fail = result as? RepoFetchResult.Failure {
                    failure = fail.message
                }
            }
        }
    }

    private func add(_ workout: Workout) {
        guard let manifest else { return }
        app.container.addWorkoutFromRepo(workout: workout, repoId: manifest.id, repoName: manifest.name) {
            MainActor.assumeIsolated { app.show("Added \"\(workout.name)\" to your library") }
        }
    }
}

/// The repositories the rider follows, and the community ones they could.
struct RepositoriesView: View {
    let app: AppModel
    @State private var registry: [RegistryEntry] = []
    @State private var url = ""
    @State private var urlError: String?

    var body: some View {
        let library = app.library
        List {
            Section {
                if library.followed.isEmpty {
                    Text(
                        "Follow a repository and the app checks it for new workouts and roads. Nothing is added to your library until you pick it."
                    )
                    .foregroundStyle(.secondary)
                }
                ForEach(library.followed, id: \.id) { repo in
                    NavigationLink {
                        RepoWorkoutsView(app: app, manifestUrl: repo.manifestUrl, fallbackName: repo.name)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(repo.name).font(.headline)
                            if let author = repo.author {
                                Text("by \(author)").font(.subheadline).foregroundStyle(.secondary)
                            }
                            Text(statusLine(library.status[repo.id])).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .swipeActions {
                        Button("Unfollow", role: .destructive) {
                            app.container.followedRepositories.unfollow(repoId: repo.id)
                        }
                    }
                }
            } header: {
                Text(library.followed.isEmpty ? "Not following anything yet" : "Following")
            }

            Section {
                HStack {
                    TextField("https://…/manifest.json", text: $url)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                        .onSubmit(followByUrl)
                    Button("Follow", action: followByUrl).disabled(url.isEmpty)
                }
                if let urlError { Text(urlError).font(.caption).foregroundStyle(.red) }
            } header: {
                Text("Add by URL")
            }

            let followedIds = Set(library.followed.map(\.id))
            let others = registry.filter { !followedIds.contains($0.id) }
            if !others.isEmpty {
                Section("From the community") {
                    ForEach(others, id: \.id) { repo in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(repo.name).font(.headline)
                                if let description = repo.description_ {
                                    Text(description).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                                }
                                if repo.hasRoutes {
                                    Text("workouts and routes").font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            Button {
                                app.container.follow(entry: repo)
                            } label: {
                                Image(systemName: "plus.circle")
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel("Follow \(repo.name)")
                        }
                    }
                }
            }
        }
        .navigationTitle("Repositories")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                if library.refreshing {
                    ProgressView()
                } else {
                    Button {
                        app.container.refreshFollowed(force: true)
                    } label: {
                        Label("Check for new items", systemImage: "arrow.clockwise")
                    }
                }
            }
        }
        .task {
            app.container.loadRegistry { index, _ in
                MainActor.assumeIsolated { registry = index?.repositories ?? [] }
            }
        }
    }

    private func followByUrl() {
        urlError = nil
        app.container.followByUrl(url: url) { error in
            MainActor.assumeIsolated {
                if let error { urlError = error } else { url = "" }
            }
        }
    }
}

/// What a followed repository has for the rider, in a line.
func statusLine(_ status: RepoStatus?) -> String {
    guard let status else { return "Not checked yet" }
    func new() -> String {
        var parts: [String] = []
        if status.newWorkouts > 0 { parts.append("\(status.newWorkouts) workout\(status.newWorkouts == 1 ? "" : "s")") }
        if status.newRoutes > 0 { parts.append("\(status.newRoutes) route\(status.newRoutes == 1 ? "" : "s")") }
        return parts.joined(separator: " and ") + " you do not have"
    }
    switch status {
    case is RepoStatus.Checking:
        return "Checking…"
    case let failed as RepoStatus.Failed:
        return status.total > 0 ? "\(new()) · last check failed" : "Could not reach it · \(failed.message)"
    case let idle as RepoStatus.Idle:
        if status.total > 0 { return new() }
        guard let checked = idle.checkedEpoch?.int64Value else { return "Not checked yet" }
        let ago = Date(timeIntervalSince1970: TimeInterval(checked) / 1000).formatted(.relative(presentation: .named))
        return "Up to date · checked \(ago)"
    default:
        return ""
    }
}

/// Reads the QR code the web editor shows, and hands over what it says.
struct QRScanner: View {
    let onScan: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if DataScannerViewController.isSupported && DataScannerViewController.isAvailable {
                    ScannerView(onScan: onScan)
                        .ignoresSafeArea()
                        .overlay(alignment: .bottom) {
                            Text(
                                "Point at a QR code from the workout editor.\nWe'll fetch the workout (or repository) it points to."
                            )
                            .multilineTextAlignment(.center)
                            .font(.subheadline)
                            .padding()
                            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                            .padding()
                        }
                } else {
                    ContentUnavailableView(
                        "Camera not available",
                        systemImage: "camera",
                        description: Text(
                            "Scanning needs a camera, and permission to use it. Frames are read on the device and never kept."
                        )
                    )
                }
            }
            .navigationTitle("Scan a QR code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
    }
}

private struct ScannerView: UIViewControllerRepresentable {
    let onScan: (String) -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            isHighlightingEnabled: true
        )
        scanner.delegate = context.coordinator
        try? scanner.startScanning()
        return scanner
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onScan: onScan) }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onScan: (String) -> Void
        private var done = false
        init(onScan: @escaping (String) -> Void) { self.onScan = onScan }

        func dataScanner(
            _ scanner: DataScannerViewController,
            didAdd items: [RecognizedItem],
            allItems: [RecognizedItem]
        ) {
            guard !done else { return }
            for item in items {
                if case .barcode(let code) = item, let text = code.payloadStringValue {
                    done = true
                    scanner.stopScanning()
                    onScan(text)
                    return
                }
            }
        }
    }
}

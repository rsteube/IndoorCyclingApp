import SwiftUI
import shared

enum RideSection: Hashable {
    case workouts, routes, companion
}

/// "What are you riding?": the kinds of ride, each opening its own page, as
/// on Android. Workouts and routes show their list beside the one picked on
/// an iPad, and one after the other on an iPhone; Just ride starts at once;
/// Companion is a single page.
struct RideHome: View {
    @Bindable var app: AppModel
    @State private var showingTrainer = false
    @State private var onboarding = false
    @State private var device: DeviceKind?

    var body: some View {
        NavigationStack(path: $app.ridePath) {
            home
            .readableWidth()
            .navigationTitle("Ride")
            .navigationDestination(for: RideSection.self) { section in
                switch section {
                case .workouts: WorkoutBrowser(app: app, showingTrainer: $showingTrainer)
                case .routes: RouteBrowser(app: app)
                case .companion: CompanionScreen(app: app)
                }
            }
        }
        .sheet(isPresented: $showingTrainer) { DeviceSheet(kind: .trainer, app: app) }
        .sheet(item: $device) { kind in DeviceSheet(kind: kind, app: app) }
        .sheet(isPresented: $onboarding) { OnboardingSheet(app: app) }
        .task { app.container.refreshFollowed(force: false) }
    }

    private var home: some View {
        List {
            if !app.settings.hasCompletedOnboarding {
                WelcomeCard { onboarding = true }
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            } else if !app.settings.hasDismissedStarterCard && app.history.sessions.isEmpty,
                      let first = app.workouts.first(where: { $0.id == "starter-15min" }) {
                FirstRideCard(
                    onStart: { app.showWorkout(first.id) },
                    onDismiss: app.settings.dismissStarterCard
                )
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            }
            UnfinishedRideBanner(app: app)
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)

            Section("What are you riding?") {
                NavigationLink(value: RideSection.workouts) {
                    ModeRow(systemImage: "figure.indoor.cycle", title: "Workout", detail: "Intervals the trainer holds for you.")
                }
                NavigationLink(value: RideSection.routes) {
                    ModeRow(systemImage: "mountain.2", title: "Route", detail: "A real road, with its hills under the wheel.", stage: "Beta")
                }
                // Starts the ride there and then: there is nothing to choose.
                Button {
                    if app.trainer.isLive { app.startFreeRide() } else { showingTrainer = true }
                } label: {
                    HStack {
                        ModeRow(
                            systemImage: "speedometer", title: "Just ride",
                            detail: app.trainer.isLive ? "No plan. Set the resistance yourself." : "Pair a trainer to ride freely at your own power."
                        )
                        Spacer()
                        Image(systemName: app.trainer.isLive ? "play.circle.fill" : "link")
                            .font(.title2)
                            .foregroundStyle(.tint)
                            .accessibilityHidden(true)
                    }
                    // A plain button answers taps only on what it draws; the
                    // gap between the text and the icon is part of the row.
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint(app.trainer.isLive ? "Starts a free ride" : "Pair a trainer first")
                NavigationLink(value: RideSection.companion) {
                    ModeRow(
                        systemImage: "dot.radiowaves.left.and.right", title: "Companion",
                        detail: app.trainer.isLive ? "Hand the trainer to a game or app on another device. Needs one running."
                            : "Pair a trainer, and a companion app on another device.",
                        stage: "Alpha"
                    )
                }
            }

            Section {
                ForEach(app.library.followed, id: \.id) { repo in
                    NavigationLink {
                        RepoWorkoutsView(app: app, manifestUrl: repo.manifestUrl, fallbackName: repo.name)
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(repo.name)
                                Text(statusLine(app.library.status[repo.id])).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            let total = Int(app.library.status[repo.id]?.total ?? 0)
                            if total > 0 {
                                Text("\(total) new").font(.caption.weight(.semibold))
                                    .padding(.horizontal, 6).padding(.vertical, 2)
                                    .background(.orange.opacity(0.2), in: Capsule())
                            }
                        }
                    }
                }
                NavigationLink(app.library.followed.isEmpty ? "Find repositories" : "Manage repositories") {
                    RepositoriesView(app: app)
                }
            } header: {
                Text(app.library.refreshing ? "Repositories · checking…" : "Repositories")
            }

            Section("Devices") {
                ForEach([DeviceKind.trainer, .heartRateMonitor, .controller]) { kind in
                    Button { device = kind } label: { DeviceRow(kind: kind, app: app) }
                        .buttonStyle(.plain)
                }
            }
        }
    }
}

/// The workout library and the workout picked from it: side by side on an
/// iPad, the list then the workout on an iPhone.
private struct WorkoutBrowser: View {
    @Bindable var app: AppModel
    @Binding var showingTrainer: Bool
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        if sizeClass == .regular {
            HStack(spacing: 0) {
                WorkoutListView(app: app).frame(width: 380)
                Divider().ignoresSafeArea()
                detail.frame(maxWidth: .infinity)
            }
            .navigationTitle("Workouts")
            .navigationBarTitleDisplayMode(.inline)
        } else {
            WorkoutListView(app: app)
                .navigationDestination(item: $app.workoutSelection) { _ in detail }
        }
    }

    @ViewBuilder private var detail: some View {
        if let workout = app.workouts.first(where: { $0.id == app.workoutSelection }) {
            WorkoutDetail(
                workout: workout,
                ftp: app.settings.ftp,
                trainerConnected: app.trainer.isLive,
                onConnect: { showingTrainer = true },
                onStart: { app.startRide(workout) }
            )
            .toolbar { WorkoutActions(app: app, workout: workout) }
        } else {
            ContentUnavailableView("Pick a workout", systemImage: "figure.indoor.cycle", description: Text("Its intervals, and how hard it is at your FTP, show here."))
        }
    }
}

/// The routes and the route picked from them, laid out as [WorkoutBrowser] is.
private struct RouteBrowser: View {
    @Bindable var app: AppModel
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        if sizeClass == .regular {
            HStack(spacing: 0) {
                RouteListView(app: app).frame(width: 380)
                Divider().ignoresSafeArea()
                detail.frame(maxWidth: .infinity)
            }
            .navigationTitle("Routes")
            .navigationBarTitleDisplayMode(.inline)
        } else {
            RouteListView(app: app)
                .navigationDestination(item: $app.routeSelection) { _ in detail }
        }
    }

    @ViewBuilder private var detail: some View {
        if let route = app.routes.routes.first(where: { $0.id == app.routeSelection }) {
            RouteDetailView(app: app, route: route)
        } else {
            ContentUnavailableView("Pick a route", systemImage: "mountain.2", description: Text("Its profile, its climbs and how long it takes show here."))
        }
    }
}

extension View {
    /// Keeps a list to a readable width on an iPad rather than stretching its
    /// rows across the screen.
    func readableWidth(_ width: CGFloat = 720) -> some View {
        GeometryReader { geometry in
            contentMargins(.horizontal, max(0, (geometry.size.width - width) / 2), for: .scrollContent)
        }
    }
}

private struct ModeRow: View {
    let systemImage: String
    let title: String
    let detail: String
    var stage: String? = nil

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.title3)
                .frame(width: 40, height: 40)
                .background(.tint.opacity(0.15), in: RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(title).font(.headline)
                    if let stage {
                        Text(stage.uppercased())
                            .font(.caption2.weight(.bold))
                            .padding(.horizontal, 6).padding(.vertical, 2)
                            .background(.orange.opacity(0.2), in: Capsule())
                    }
                }
                Text(detail).font(.subheadline).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }
}


/// A workout's own actions: favourite it, and rename or delete one the rider imported.
private struct WorkoutActions: ToolbarContent {
    let app: AppModel
    let workout: Workout
    @State private var renaming = false
    @State private var newName = ""
    @State private var deleting = false

    var body: some ToolbarContent {
        ToolbarItemGroup(placement: .primaryAction) {
            let favourite = app.library.favourites.contains(workout.id)
            Button { app.library.toggleFavourite(workout.id) } label: {
                Label(favourite ? "Remove from favourites" : "Add to favourites", systemImage: favourite ? "star.fill" : "star")
            }
            .sensoryFeedback(.selection, trigger: favourite)
            if app.library.isImported(workout.id) {
                Menu {
                    Button("Rename", systemImage: "pencil") { newName = workout.name; renaming = true }
                    Button("Delete", systemImage: "trash", role: .destructive) { deleting = true }
                } label: {
                    Label("Workout actions", systemImage: "ellipsis.circle")
                }
                .alert("Rename workout", isPresented: $renaming) {
                    TextField("Name", text: $newName)
                    Button("Save") { app.container.renameWorkout(workoutId: workout.id, name: newName) { _ in } }
                        .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty || newName == workout.name)
                    Button("Cancel", role: .cancel) {}
                }
                .confirmationDialog("Delete \"\(workout.name)\"?", isPresented: $deleting, titleVisibility: .visible) {
                    Button("Delete", role: .destructive) {
                        app.container.deleteWorkout(workoutId: workout.id) { _ in
                            MainActor.assumeIsolated { app.workoutSelection = nil }
                        }
                    }
                } message: {
                    Text("The workout file will be removed from your device. Past rides of this workout stay in your history.")
                }
            }
        }
    }
}

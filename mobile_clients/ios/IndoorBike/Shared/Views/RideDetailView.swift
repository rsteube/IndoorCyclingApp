import SwiftUI
import shared

/// One saved ride: how long and how hard, the power against the plan, time in
/// each zone, and the ways out of the app - a FIT or TCX file, or a picture.
struct RideDetailView: View {
    let app: AppModel
    let sessionId: Int64

    @State private var detail: IosAppContainer.RideDetail?
    @State private var missing = false
    @State private var confirmingDelete = false
    @State private var sharing: ShareItem?
    @State private var exporting = false

    var body: some View {
        Group {
            if let detail {
                content(detail)
            } else if missing {
                ContentUnavailableView("Ride not found", systemImage: "questionmark.circle")
            } else {
                ProgressView()
            }
        }
        .navigationTitle(detail?.session.workoutName ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let detail {
                ToolbarItemGroup(placement: .primaryAction) {
                    Menu {
                        Button("Share picture", systemImage: "photo") { sharePicture(detail) }
                        if detail.canExport {
                            Button("Export FIT file", systemImage: "doc") { export(fit: true) }
                            Button("Export TCX file", systemImage: "doc.text") { export(fit: false) }
                        }
                    } label: {
                        Label("Share", systemImage: "square.and.arrow.up")
                    }
                    .disabled(exporting)
                    Button(role: .destructive) {
                        confirmingDelete = true
                    } label: {
                        Label("Delete", systemImage: "trash")
                    }
                }
            }
        }
        .confirmationDialog("Delete this ride?", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                app.container.deleteRide(sessionId: sessionId) {
                    MainActor.assumeIsolated { app.historySelection = nil }
                }
            }
        } message: {
            Text("Telemetry and stats for this session will be removed. This can't be undone.")
        }
        .sheet(item: $sharing) { item in
            ActivityView(items: item.items)
        }
        .task { load() }
        .onChange(of: app.sync.recordedCount) { _, _ in load() }
    }

    private func load() {
        app.container.loadRide(sessionId: sessionId, library: app.workouts) { loaded in
            MainActor.assumeIsolated {
                detail = loaded
                missing = loaded == nil
            }
        }
    }

    private func export(fit: Bool) {
        exporting = true
        app.container.exportRide(sessionId: sessionId, fit: fit, library: app.workouts) { path in
            MainActor.assumeIsolated {
                exporting = false
                if let path { sharing = ShareItem(items: [URL(fileURLWithPath: path)]) }
            }
        }
    }

    private func sharePicture(_ detail: IosAppContainer.RideDetail) {
        let renderer = ImageRenderer(
            content: ShareCard(detail: detail, ftp: app.settings.ftp).environment(\.colorScheme, .dark)
        )
        renderer.scale = 1
        if let image = renderer.uiImage { sharing = ShareItem(items: [image]) }
    }

    @ViewBuilder
    private func content(_ detail: IosAppContainer.RideDetail) -> some View {
        let session = detail.session
        let stats = detail.stats
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text(session.startDate.formatted(date: .long, time: .shortened))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                HStack {
                    hero(WorkoutSummaryKt.formatClock(totalSeconds: Int32(session.durationSeconds)), "Duration")
                    hero("\(session.avgPower) W", "Avg power")
                }
                .padding(.vertical, 16)
                .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 16))

                LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3), spacing: 12) {
                    StatTile(value: "\(session.avgCadence)", unit: "rpm", label: "Cadence")
                    StatTile(value: "\(session.avgHeartRate)", unit: "bpm", label: "Heart rate")
                    if detail.canExport {
                        StatTile(value: "\(stats.normalizedPowerWatts)", unit: "W", label: "NP")
                        StatTile(value: String(format: "%.2f", stats.intensityFactor), unit: "", label: "IF")
                        StatTile(value: "\(stats.tss)", unit: "", label: "TSS")
                        StatTile(value: "\(stats.kilojoules)", unit: "kJ", label: "Energy")
                    }
                }

                if let route = detail.route {
                    RouteSummaryPanel(route: route, session: session)
                }

                if stats.distanceMeters > 0 && detail.route == nil {
                    Card {
                        HStack {
                            hero(String(format: "%.2f km", Double(stats.distanceMeters) / 1000), "Distance")
                            hero(String(format: "%.1f km/h", stats.avgSpeedKmh), "Avg speed")
                        }
                        Text("Estimated from power on a flat virtual road.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                if let workout = detail.workout {
                    Card(title: "Power adherence", trailing: "target · actual") {
                        AdherenceChart(
                            workout: workout,
                            power: detail.powerWatts.map { Int(truncating: $0) },
                            ftp: app.settings.ftp
                        )
                        .frame(height: 140)
                    }
                }

                if app.sync.intervalsConfigured {
                    UploadCard(
                        title: "Intervals.icu",
                        uploadedEpoch: session.intervalsUploadedEpoch,
                        autoUpload: app.sync.intervalsAutoUpload,
                        uploading: app.sync.uploading(session.id, .intervals)
                    ) {
                        app.container.uploadToIntervals(sessionId: session.id, library: app.workouts) { message in
                            MainActor.assumeIsolated { app.show(message) }
                        }
                    }
                }
                if app.sync.fitTrackeeConnected {
                    UploadCard(
                        title: "FitTrackee",
                        uploadedEpoch: session.fitTrackeeUploadedEpoch,
                        autoUpload: app.sync.fitTrackeeAutoUpload,
                        uploading: app.sync.uploading(session.id, .fittrackee)
                    ) {
                        app.container.uploadToFitTrackee(sessionId: session.id, library: app.workouts) { message in
                            MainActor.assumeIsolated { app.show(message) }
                        }
                    }
                }

                let zones = detail.timeInZoneSeconds.map { Int(truncating: $0) }
                if zones.contains(where: { $0 > 0 }) {
                    Card(title: "Time in zone") {
                        TimeInZoneChart(seconds: zones)
                            .frame(height: 140)
                    }
                }
            }
            .padding()
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
        }
    }

    private func hero(_ value: String, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.title.weight(.semibold).monospacedDigit())
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }
}

struct ShareItem: Identifiable {
    let id = UUID()
    let items: [Any]
}

/// The system share sheet.
struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

struct Card<Content: View>: View {
    var title: String? = nil
    var trailing: String? = nil
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let title {
                HStack {
                    Text(title).font(.headline)
                    Spacer()
                    if let trailing {
                        Text(trailing).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            content
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 16))
    }
}

struct StatTile: View {
    let value: String
    let unit: String
    let label: String

    var body: some View {
        VStack(spacing: 2) {
            HStack(alignment: .firstTextBaseline, spacing: 2) {
                Text(value).font(.title3.weight(.semibold).monospacedDigit())
                if !unit.isEmpty {
                    Text(unit).font(.caption).foregroundStyle(.secondary)
                }
            }
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 12))
    }
}

/// The plan's intervals as zone-coloured bars, with the power actually ridden
/// drawn over them. Scaled so neither the plan nor a sprint runs off the top.
struct AdherenceChart: View {
    let workout: Workout
    let power: [Int]
    let ftp: Int

    var body: some View {
        Canvas { context, size in
            let segments = workout.segments
            let total = segments.reduce(0.0) { $0 + Double($1.lengthInSeconds) }
            guard total > 0, ftp > 0 else { return }
            let maxPlan = Double(segments.map(\.powerPercentFTP).max() ?? 0)
            let maxRidden = Double(power.max() ?? 0) / Double(ftp) * 100
            let top = max(maxPlan, maxRidden, 60)
            var x = 0.0
            for segment in segments {
                let width = size.width * Double(segment.lengthInSeconds) / total
                let height = size.height * Double(segment.powerPercentFTP) / top
                let zone = Zone.forPercent(Int(segment.powerPercentFTP))
                context.fill(
                    Path(
                        roundedRect: CGRect(x: x, y: size.height - height, width: max(width - 1, 0.5), height: height),
                        cornerRadius: 2
                    ),
                    with: .color(zone.color.opacity(0.35))
                )
                x += width
            }
            guard power.count > 1 else { return }
            // One sample a second: placed along the plan's own time axis.
            var line = Path()
            for (i, watts) in power.enumerated() {
                let point = CGPoint(
                    x: size.width * min(Double(i) / total, 1),
                    y: size.height * (1 - Double(watts) / Double(ftp) * 100 / top)
                )
                if i == 0 { line.move(to: point) } else { line.addLine(to: point) }
            }
            context.stroke(line, with: .color(.primary), lineWidth: 1.5)
        }
    }
}

/// Seconds in each zone, as bars against the busiest zone.
struct TimeInZoneChart: View {
    let seconds: [Int]

    var body: some View {
        let peak = max(seconds.max() ?? 1, 1)
        HStack(alignment: .bottom, spacing: 8) {
            ForEach(Array(Zone.allCases.enumerated()), id: \.offset) { index, zone in
                let value = seconds[safe: index] ?? 0
                VStack(spacing: 4) {
                    Spacer(minLength: 0)
                    if value > 0 {
                        Text(clock(value))
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.6)
                    }
                    GeometryReader { geometry in
                        VStack {
                            Spacer(minLength: 0)
                            RoundedRectangle(cornerRadius: 4)
                                .fill(zone.color)
                                .frame(
                                    height: value > 0
                                        ? max(
                                            geometry.size.height * CGFloat(value) / CGFloat(peak),
                                            geometry.size.height * 0.08
                                        ) : 4
                                )
                        }
                    }
                    Text("Z\(zone.rawValue)")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.secondary)
                }
            }
        }
    }
}

/// The road a route ride was on: its shape with what was ridden, the distance
/// and climbing done, and the profile.
struct RouteSummaryPanel: View {
    let route: RouteTrack
    let session: WorkoutSession

    var body: some View {
        let total = max(route.totalDistanceMeters, 1)
        let ridden = session.routeDistanceMeters
        let startedAt = session.routeStartMeters
        let continued = startedAt > 1
        let completed = ridden >= total - 5
        Card {
            HStack {
                Text("VIRTUAL ROUTE").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                if completed {
                    Text("Completed").font(.caption.weight(.semibold))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(.green.opacity(0.2), in: Capsule())
                }
            }
            Text(route.name).font(.headline)
            HStack(spacing: 16) {
                RouteShapeView(
                    latLons: route.latLons(),
                    riddenFraction: ridden / total,
                    startedFraction: startedAt / total,
                    accent: .orange
                )
                .frame(width: 150, height: 150)
                VStack(alignment: .leading, spacing: 12) {
                    stat(
                        continued ? "Ridden today" : "Ridden",
                        RouteFormatKt.formatDistance(meters: ridden - startedAt),
                        continued
                            ? "\(RouteFormatKt.formatDistance(meters: ridden)) of \(RouteFormatKt.formatDistance(meters: total))"
                            : "of \(RouteFormatKt.formatDistance(meters: total))"
                    )
                    stat(
                        continued ? "Climbed today" : "Climbed",
                        "\(Int((session.routeElevationGainMeters - session.routeStartElevationGainMeters).rounded())) m",
                        continued
                            ? "\(Int(session.routeElevationGainMeters.rounded())) of \(Int(route.totalAscentMeters.rounded())) m"
                            : "of \(Int(route.totalAscentMeters.rounded())) m"
                    )
                }
            }
            ElevationProfileView(route: route, distanceMeters: ridden, startedAtMeters: startedAt)
                .frame(height: 72)
            Text(
                "Simulated indoors. Gradient and distance from the route, speed from the rider's own power."
                    + (continued ? " Picked up from an earlier ride; the faint part of the road was ridden then." : "")
            )
            .font(.caption)
            .foregroundStyle(.secondary)
        }
    }

    private func stat(_ label: String, _ value: String, _ detail: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label.uppercased()).font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
            Text(value).font(.title3.weight(.semibold).monospacedDigit())
            Text(detail).font(.caption).foregroundStyle(.secondary)
        }
    }
}

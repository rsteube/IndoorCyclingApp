import AuthenticationServices
import SwiftUI
import UniformTypeIdentifiers
import shared

/// Where finished rides can be sent. Each is off until set up, and nothing is
/// contacted before then.
struct ConnectionsView: View {
    let app: AppModel

    var body: some View {
        Form {
            Section {
                Text(
                    "Finished rides can be uploaded to a training platform. Each one is off until you set it up, and nothing is contacted before then."
                )
                .foregroundStyle(.secondary)
            }
            IntervalsSection(app: app)
            FitTrackeeSection(app: app)
        }
        .navigationTitle("Connections")
    }
}

/// What the Settings row says about the connections.
@MainActor
func connectionsSummary(_ sync: SyncModel) -> String {
    var names: [String] = []
    if sync.intervalsConfigured { names.append("Intervals.icu") }
    if sync.fitTrackeeConnected { names.append("FitTrackee") }
    return names.isEmpty ? "Nothing connected" : names.joined(separator: " · ")
}

private struct IntervalsSection: View {
    let app: AppModel
    @State private var showKey = false

    var body: some View {
        let sync = app.sync
        let settings = sync.settings
        Section {
            Text(
                "Send your finished rides to Intervals.icu. Nothing is contacted until you connect, and after that only when a ride is uploaded."
            )
            .foregroundStyle(.secondary)
            if !sync.intervalsToken.isEmpty {
                Label(
                    sync.intervalsAthleteName.isEmpty ? "Connected" : "Connected as \(sync.intervalsAthleteName)",
                    systemImage: "checkmark.circle.fill"
                )
                .foregroundStyle(.green)
            } else {
                TextField(
                    "Athlete ID, e.g. i12345",
                    text: Binding(get: { sync.intervalsAthleteId }, set: { settings.setIntervalsAthleteId(id: $0) })
                )
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                HStack {
                    Group {
                        if showKey {
                            TextField(
                                "API key",
                                text: Binding(
                                    get: { sync.intervalsApiKey },
                                    set: { settings.setIntervalsApiKey(key: $0) }
                                )
                            )
                        } else {
                            SecureField(
                                "API key",
                                text: Binding(
                                    get: { sync.intervalsApiKey },
                                    set: { settings.setIntervalsApiKey(key: $0) }
                                )
                            )
                        }
                    }
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    Button {
                        showKey.toggle()
                    } label: {
                        Image(systemName: showKey ? "eye.slash" : "eye")
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(showKey ? "Hide key" : "Show key")
                }
                Text("Both are on your Intervals.icu settings page. The key is kept in this phone's Keychain.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Toggle(
                isOn: Binding(get: { sync.intervalsAutoUpload }, set: { settings.setIntervalsAutoUpload(value: $0) })
            ) {
                VStack(alignment: .leading) {
                    Text("Auto-upload finished rides")
                    Text(
                        sync.intervalsConfigured
                            ? "Each completed ride is sent immediately." : "Add credentials above to enable."
                    )
                    .font(.caption).foregroundStyle(.secondary)
                }
            }
            .disabled(!sync.intervalsConfigured)
            if !sync.intervalsToken.isEmpty {
                Button("Disconnect", role: .destructive) {
                    app.container.disconnectIntervals { told in
                        MainActor.assumeIsolated {
                            app.show(
                                told.boolValue
                                    ? "Disconnected from Intervals.icu"
                                    : "Disconnected on this phone. Intervals.icu couldn't be reached, so remove Indoor Bike in your Intervals.icu settings as well."
                            )
                        }
                    }
                }
            }
        } header: {
            Text("Intervals.icu")
        } footer: {
            Text(intervalsStatus(sync))
        }
    }

    private func intervalsStatus(_ sync: SyncModel) -> String {
        let auto = sync.intervalsAutoUpload ? "auto-upload on" : "manual upload"
        if !sync.intervalsToken.isEmpty { return "Connected · \(auto)" }
        if sync.intervalsConfigured { return "API key · \(auto)" }
        return "Not connected · optional"
    }
}

private struct FitTrackeeSection: View {
    let app: AppModel
    @Environment(\.webAuthenticationSession) private var webAuthenticationSession
    @State private var sports: [FitTrackeeUploader.Sport] = []
    @State private var signingIn = false

    var body: some View {
        let sync = app.sync
        let settings = sync.settings
        Section {
            Text(
                "Your own FitTrackee server. Worth knowing before you set it up: FitTrackee is built for outdoor activities and only accepts files that contain GPS coordinates, which an indoor ride has none of. Rides therefore arrive as an entry with the date, duration and estimated distance, and the power, cadence and heart rate figures written into the notes. There is no power graph. Export a FIT file if you need one."
            )
            .font(.subheadline)
            .foregroundStyle(.secondary)
            if sync.fitTrackeeConnected {
                Label(sync.fitTrackeeUrl, systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                if sports.isEmpty {
                    Text(
                        "Rides are filed as Cycling (Virtual). Your server didn't share its sport list, which is normal and doesn't stop anything."
                    )
                    .font(.caption).foregroundStyle(.secondary)
                } else {
                    Picker(
                        "Sport",
                        selection: Binding(
                            get: { sync.fitTrackeeSportId },
                            set: { settings.setFitTrackeeSportId(id: Int32($0)) }
                        )
                    ) {
                        ForEach(sports, id: \.id) { Text($0.label).tag(Int($0.id)) }
                    }
                }
                Toggle(
                    isOn: Binding(
                        get: { sync.fitTrackeeAutoUpload },
                        set: { settings.setFitTrackeeAutoUpload(value: $0) }
                    )
                ) {
                    VStack(alignment: .leading) {
                        Text("Auto-upload finished rides")
                        Text("Sent as soon as a ride is saved.").font(.caption).foregroundStyle(.secondary)
                    }
                }
                Button("Disconnect", role: .destructive) {
                    app.container.fitTrackeeUploader.signOut()
                    settings.setFitTrackeeAutoUpload(value: false)
                    sports = []
                }
            } else {
                Text(
                    "You approve this in your browser, on your own server, so the app never sees your password. In FitTrackee, go to Profile → Apps → Create an app, allow it to write workouts, and use this as the redirect URI:"
                )
                .font(.subheadline)
                HStack {
                    Text(FitTrackeeUploader.companion.REDIRECT_URI).font(.body.monospaced())
                    Spacer()
                    Button("Copy") {
                        UIPasteboard.general.string = FitTrackeeUploader.companion.REDIRECT_URI
                        app.show("Redirect URI copied")
                    }
                    .buttonStyle(.borderless)
                }
                TextField(
                    "Server address, e.g. https://fittrackee.example.org",
                    text: Binding(get: { sync.fitTrackeeUrl }, set: { settings.setFitTrackeeUrl(url: $0) })
                )
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                TextField(
                    "Client ID",
                    text: Binding(get: { sync.fitTrackeeClientId }, set: { settings.setFitTrackeeClientId(id: $0) })
                )
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                SecureField(
                    "Client secret",
                    text: Binding(
                        get: { sync.fitTrackeeClientSecret },
                        set: { settings.setFitTrackeeClientSecret(secret: $0) }
                    )
                )
                Button(signingIn ? "Waiting for the browser…" : "Connect in browser") { Task { await signIn() } }
                    .disabled(signingIn)
            }
        } header: {
            Text("FitTrackee")
        } footer: {
            Text(
                sync.fitTrackeeConnected
                    ? "Connected · \(sync.fitTrackeeAutoUpload ? "auto-upload on" : "manual upload")"
                    : "Not connected · optional"
            )
        }
        .task(id: sync.fitTrackeeConnected) {
            guard sync.fitTrackeeConnected else { return }
            app.container.fitTrackeeSports { list in MainActor.assumeIsolated { sports = list } }
        }
    }

    /// Open the rider's server's consent page, and finish with what comes back.
    private func signIn() async {
        guard let urlString = app.container.fitTrackeeUploader.authorizeUrl(), let url = URL(string: urlString) else {
            app.show("Enter your server address and client id first")
            return
        }
        signingIn = true
        defer { signingIn = false }
        do {
            let callback = try await webAuthenticationSession.authenticate(using: url, callbackURLScheme: "indoorbike")
            app.container.completeFitTrackeeSignIn(redirect: callback.absoluteString) { error in
                MainActor.assumeIsolated { app.show(error ?? "Connected to FitTrackee") }
            }
        } catch {
            // Closing the browser sheet is the rider changing their mind, not a failure to report.
        }
    }
}

/// Backup & sync: one small file per device in a folder the rider already trusts.
struct BackupSection: View {
    let app: AppModel
    @State private var picking = false

    var body: some View {
        let sync = app.sync
        Section {
            Text(
                "Pick any folder in Files - iCloud Drive, Dropbox, Nextcloud, anything that shows up there. Each device writes one small JSON file into it; other devices pointed at the same folder share your rides, library, and settings. No accounts, no servers - just a file in a folder you already trust."
            )
            .font(.subheadline)
            .foregroundStyle(.secondary)
            if sync.folder == nil {
                Button("Choose sync folder") { picking = true }
            } else {
                Button("Change folder") { picking = true }
                TextField(
                    "Device name",
                    text: Binding(get: { sync.deviceLabel }, set: { sync.settings.setDeviceLabel(label: $0) })
                )
                Text(statusLine(sync)).font(.subheadline).foregroundStyle(.secondary)
                if let review = sync.status as? SyncStatus.NeedsReview, true {
                    reviewBlock(counts: review)
                } else if sync.paused {
                    reviewBlock(counts: nil)
                } else {
                    Button(sync.status is SyncStatus.Syncing ? "Syncing…" : "Sync now") { app.container.syncNow() }
                        .disabled(sync.status is SyncStatus.Syncing)
                    Button("Restore from backup") { app.container.restoreFromBackup() }
                }
                Button("Stop syncing", role: .destructive) { sync.settings.setSyncFolderUri(uri: nil) }
            }
        } header: {
            Text("Backup & sync")
        } footer: {
            if sync.folder != nil {
                Text(
                    "Stop syncing disconnects this device from the folder. Local rides, settings, and library stay put - files in the folder aren't deleted."
                )
            }
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.folder]) { result in
            guard case .success(let url) = result else { return }
            let accessed = url.startAccessingSecurityScopedResource()
            defer { if accessed { url.stopAccessingSecurityScopedResource() } }
            if let bookmark = try? url.bookmarkData() {
                sync.settings.setSyncFolderUri(uri: bookmark.base64EncodedString())
                app.container.syncNow()
            } else {
                app.show("Couldn't keep access to that folder.")
            }
        }
    }

    @ViewBuilder
    private func reviewBlock(counts: SyncStatus.NeedsReview?) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("⚠ Backup paused").font(.headline)
            if let c = counts {
                Text(
                    "This device has \(c.localSessions) ride(s) and \(c.localImports) workout(s); the backup has \(c.backupSessions) ride(s) and \(c.backupImports) workout(s). They disagree about too many at once, which usually means one device's data was reset. Backup is paused so nothing is deleted by mistake. Choose what to do:"
                )
            } else {
                Text(
                    "This device and the backup disagree about too many rides at once. Backup is paused so nothing is deleted by mistake. Choose what to do:"
                )
            }
        }
        .font(.subheadline)
        Button("Restore from backup") { app.container.restoreFromBackup() }
        Text(
            "Makes this device match the backup: rides, workouts and settings missing here come back, and anything the backup has removed is removed here too. Recommended if this device's data was lost by accident."
        )
        .font(.caption).foregroundStyle(.secondary)
        Button("Keep this device's data", role: .destructive) { app.container.keepThisDevicesData() }
        Text(
            "Keeps every ride and workout on this device as it is and updates the backup to match. Rides this device doesn't have are removed from the backup, and from your other devices when they next sync. Use only if that is what you want."
        )
        .font(.caption).foregroundStyle(.secondary)
    }

    private func statusLine(_ sync: SyncModel) -> String {
        switch sync.status {
        case is SyncStatus.Syncing: return "Syncing…"
        case let ok as SyncStatus.Success:
            let devices = ok.devicesSeen == 1 ? "1 device seen" : "\(ok.devicesSeen) devices seen"
            let skipped = ok.slicesSkipped > 0 ? " · ⚠ \(ok.slicesSkipped) unreadable slice(s)" : ""
            return "Synced \(SyncModel.relative(ok.syncedAtEpoch)) · \(devices)\(skipped)"
        case let failed as SyncStatus.Failed: return "Couldn't sync: \(failed.message)"
        case is SyncStatus.NeedsReview: return "Paused - local data looks reset. Review below."
        case is SyncStatus.NotConfigured: return "Not configured"
        default: return sync.paused ? "Paused - local data looks reset. Review below." : "Not synced yet · tap Sync now"
        }
    }
}

/// On a saved ride: send it to a service, or see that it went.
struct UploadCard: View {
    let title: String
    let uploadedEpoch: Int64
    let autoUpload: Bool
    let uploading: Bool
    let upload: () -> Void

    var body: some View {
        Card(title: title) {
            HStack {
                if uploadedEpoch > 0 {
                    Label(
                        "Uploaded · \(Date(timeIntervalSince1970: TimeInterval(uploadedEpoch) / 1000).formatted(date: .abbreviated, time: .shortened))",
                        systemImage: "checkmark.circle.fill"
                    )
                    .foregroundStyle(.green)
                } else {
                    Text(autoUpload ? "Auto-upload is on. Tap to re-send." : "Tap to upload this ride.")
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button(uploading ? "Uploading…" : uploadedEpoch > 0 ? "Re-upload" : "Upload", action: upload)
                    .buttonStyle(.bordered)
                    .disabled(uploading)
            }
            .font(.subheadline)
        }
    }
}

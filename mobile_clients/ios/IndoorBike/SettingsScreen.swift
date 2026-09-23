import MessageUI
import SwiftUI
import shared

/// The rider, their devices, the bike a route is ridden on, and the app.
struct SettingsScreen: View {
    let app: AppModel
    @State private var sheet: DeviceKind?
    @State private var reporting = false

    var body: some View {
        NavigationStack {
            Form {
                UnfinishedRideBanner(app: app)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)

                Section("Profile") {
                    NumberField(
                        title: "FTP", subtitle: "Sets your power zones", unit: "W",
                        value: Double(app.settings.ftp), range: 50...600, decimals: false
                    ) { app.settings.setFtp(Int($0)) }
                    NumberField(
                        title: "Body weight", subtitle: "For W/kg and XP", unit: "kg",
                        value: Double(app.settings.weightKg), range: 30...250, decimals: true
                    ) { app.settings.setWeightKg(Float($0)) }
                    NumberField(
                        title: "Max heart rate", subtitle: "Colours the live HR by zone", unit: "bpm",
                        value: Double(app.settings.maxHr), range: 100...230, decimals: false
                    ) { app.settings.setMaxHr(Int($0)) }
                }

                Section("Devices") {
                    ForEach([DeviceKind.trainer, .heartRateMonitor, .controller]) { kind in
                        Button { sheet = kind } label: {
                            HStack {
                                DeviceRow(kind: kind, app: app)
                                Spacer()
                                Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }

                Section("More") {
                    NavigationLink {
                        ConnectionsView(app: app)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Connections")
                            Text(connectionsSummary(app.sync)).font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                    NavigationLink {
                        BikeGearingView(settings: app.settings)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Bike & gearing")
                            Text("\(Drivetrain.companion.byId(id: app.settings.drivetrainId).name) · \(String(format: "%.1f", app.settings.bikeWeightKg)) kg bike")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                BackupSection(app: app)

                Section("About") {
                    LabeledContent("App version", value: AppInfo.version)
                    LabeledContent("Build", value: AppInfo.isDebug ? "Debug" : "Release")
                    LabeledContent("License", value: "GPLv3")
                }

                Section("Feedback") {
                    Button { reporting = true } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Report a problem")
                            Text("Drafts an email with diagnostics · no personal data")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("Settings")
            .sheet(item: $sheet) { kind in DeviceSheet(kind: kind, app: app) }
            .reportProblem(isPresented: $reporting, app: app)
        }
    }
}

/// A number the rider types: saved as soon as it is valid, and put back to
/// the stored value if they leave it invalid.
struct NumberField: View {
    let title: String
    let subtitle: String
    let unit: String
    let value: Double
    let range: ClosedRange<Double>
    let decimals: Bool
    let onChange: (Double) -> Void

    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(subtitle).font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer()
            TextField("", text: $text)
                .keyboardType(decimals ? .decimalPad : .numberPad)
                .multilineTextAlignment(.trailing)
                .monospacedDigit()
                .frame(width: 72)
                .focused($focused)
                .onChange(of: text) { _, new in
                    if let parsed = parse(new), range.contains(parsed) { onChange(parsed) }
                }
            Text(unit).foregroundStyle(.secondary).fixedSize().frame(minWidth: 36, alignment: .leading)
        }
        .onAppear { text = format(value) }
        .onChange(of: value) { _, new in if !focused { text = format(new) } }
        .onChange(of: focused) { _, isFocused in
            if !isFocused, parse(text).map(range.contains) != true { text = format(value) }
        }
    }

    private func parse(_ raw: String) -> Double? {
        Double(raw.replacingOccurrences(of: ",", with: ".").trimmingCharacters(in: .whitespaces))
    }

    private func format(_ number: Double) -> String {
        if !decimals || number.rounded() == number { return String(Int(number.rounded())) }
        return String(format: "%.1f", number)
    }
}

enum AppInfo {
    static var version: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(short) (\(build))"
    }

    static var isDebug: Bool {
        #if DEBUG
        true
        #else
        false
        #endif
    }

    /// The app and the device, for a bug report. Nothing here identifies a person.
    static var deviceInfo: String {
        let device = UIDevice.current
        return """
        App: \(version)\(isDebug ? " debug" : "")
        Device: Apple \(modelIdentifier) (\(device.model))
        iOS: \(device.systemVersion)
        """
    }

    private static var modelIdentifier: String {
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { buffer in
            String(decoding: buffer.prefix { $0 != 0 }, as: UTF8.self)
        }
    }
}

/// Bike weight and gearing, which only change how a route ride feels.
struct BikeGearingView: View {
    let settings: SettingsModel

    var body: some View {
        Form {
            Section {
                Text("How a route ride is simulated. Workouts and Just Ride are unaffected.")
                    .foregroundStyle(.secondary)
            }
            Section {
                Stepper(
                    value: Binding(get: { Double(settings.bikeWeightKg) }, set: { settings.setBikeWeightKg(Float($0)) }),
                    in: 4...30, step: 0.5
                ) {
                    LabeledContent("Bike weight", value: String(format: "%.1f kg", settings.bikeWeightKg))
                }
                HStack {
                    ForEach([("Road", Float(8)), ("Gravel", 10), ("MTB", 14)], id: \.0) { name, kg in
                        Button("\(name) \(Int(kg))") { settings.setBikeWeightKg(kg) }
                            .buttonStyle(.bordered)
                            .tint(abs(settings.bikeWeightKg - kg) < 0.05 ? .accentColor : .secondary)
                            .frame(maxWidth: .infinity)
                    }
                }
            } header: {
                Text("Bike weight")
            } footer: {
                Text("Added to your body weight on every climb.")
            }
            Section {
                ForEach(Drivetrain.companion.ALL, id: \.id) { drivetrain in
                    Button {
                        settings.setDrivetrain(drivetrain.id)
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(drivetrain.name).foregroundStyle(.primary)
                                Text(drivetrain.summary).font(.subheadline).foregroundStyle(.secondary)
                                if let first = drivetrain.gears.first, let last = drivetrain.gears.last {
                                    Text("\(drivetrain.gearCount) gears · \(first.label) to \(last.label)")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            if drivetrain.id == settings.drivetrainId {
                                Image(systemName: "checkmark").foregroundStyle(.tint)
                            }
                        }
                    }
                }
            } header: {
                Text("Gearing")
            } footer: {
                Text("Gears change the resistance, not the speed - the same watts carry you the same distance whichever gear you pick, exactly as on the road. A change takes effect on your next route ride.")
            }
        }
        .navigationTitle("Bike & gearing")
    }
}

extension View {
    /// "Report a problem": explain what is sent, then open an email draft with
    /// the report as the body and as an attachment. Nothing leaves the phone
    /// unless the rider sends it.
    func reportProblem(isPresented: Binding<Bool>, app: AppModel, errorContext: String? = nil) -> some View {
        modifier(ReportProblem(isPresented: isPresented, app: app, errorContext: errorContext))
    }
}

private struct ReportProblem: ViewModifier {
    @Binding var isPresented: Bool
    let app: AppModel
    let errorContext: String?
    @State private var draft: ShareItem?
    @State private var mailing = false

    func body(content: Content) -> some View {
        content
            .alert("Report a problem", isPresented: $isPresented) {
                Button("Open email draft") { open() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This opens an email draft to the developer. Nothing is sent automatically: you review it and decide whether to send.\n\nThe draft includes your device model, iOS version, app version, and recent diagnostic logs (technical app state only). No personal information (no name, email, body metrics, or location) is collected.")
            }
            .sheet(isPresented: $mailing) {
                MailDraft(
                    to: BugReportTextKt.SUPPORT_EMAIL,
                    subject: BugReportTextKt.bugReportSubject(appVersion: AppInfo.version),
                    body: report
                )
                .ignoresSafeArea()
            }
            .sheet(item: $draft) { item in ActivityView(items: item.items) }
    }

    private var report: String {
        app.container.bugReport(deviceInfo: AppInfo.deviceInfo, errorContext: errorContext)
    }

    private func open() {
        if MFMailComposeViewController.canSendMail() {
            mailing = true
        } else {
            // No mail account on this device: hand the report to the share
            // sheet instead, so it can go by any app the rider has.
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("indoorbike-bug-report.txt")
            try? report.write(to: url, atomically: true, encoding: .utf8)
            draft = ShareItem(items: [url])
        }
    }
}

private struct MailDraft: UIViewControllerRepresentable {
    let to: String
    let subject: String
    let body: String
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> MFMailComposeViewController {
        let mail = MFMailComposeViewController()
        mail.mailComposeDelegate = context.coordinator
        mail.setToRecipients([to])
        mail.setSubject(subject)
        mail.setMessageBody(body, isHTML: false)
        // The same text as an attachment, so it survives a client that clips long bodies.
        if let data = body.data(using: .utf8) {
            mail.addAttachmentData(data, mimeType: "text/plain", fileName: "indoorbike-bug-report.txt")
        }
        return mail
    }

    func updateUIViewController(_ controller: MFMailComposeViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(dismiss: dismiss) }

    final class Coordinator: NSObject, MFMailComposeViewControllerDelegate {
        let dismiss: DismissAction
        init(dismiss: DismissAction) { self.dismiss = dismiss }

        func mailComposeController(_ controller: MFMailComposeViewController, didFinishWith result: MFMailComposeResult, error: Error?) {
            dismiss()
        }
    }
}

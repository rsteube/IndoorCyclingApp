import SwiftUI
import shared

/// Which of the three devices a sheet or pairing screen is about.
enum DeviceKind: String, Identifiable {
    case trainer, heartRateMonitor, controller
    var id: String { rawValue }

    var systemImage: String {
        switch self {
        case .trainer: "bicycle"
        case .heartRateMonitor: "heart.fill"
        case .controller: "gamecontroller.fill"
        }
    }

    var pairTitle: String {
        switch self {
        case .trainer: "Connect a trainer"
        case .heartRateMonitor: "Connect a monitor"
        case .controller: "Connect a controller"
        }
    }

    var findTitle: String {
        switch self {
        case .trainer: "Find your trainer"
        case .heartRateMonitor: "Find your monitor"
        case .controller: "Find your controller"
        }
    }

    var findHint: String {
        switch self {
        case .trainer: "Make sure your smart trainer is plugged in and awake."
        case .heartRateMonitor: "Wake your heart-rate strap (often by snapping it on your chest) so it broadcasts over Bluetooth."
        case .controller: "Wake your Zwift Click by pressing one of its buttons so it broadcasts over Bluetooth."
        }
    }

    var scanningHint: String {
        switch self {
        case .trainer: "Looking for nearby smart trainers…"
        case .heartRateMonitor: "Looking for nearby heart-rate monitors…"
        case .controller: "Looking for nearby controllers… press a button on your Click to wake it."
        }
    }

    var unknownName: String {
        switch self {
        case .trainer: "Unknown trainer"
        case .heartRateMonitor: "Unknown monitor"
        case .controller: "Unknown controller"
        }
    }
}

/// What a device row and sheet say, from the shared status text.
struct DeviceStatus {
    let title: String
    let line: String
    let tone: StatusTone
    let helper: String

    @MainActor
    static func of(_ kind: DeviceKind, app: AppModel) -> DeviceStatus {
        switch kind {
        case .trainer:
            let t = app.trainer
            return DeviceStatus(
                title: DeviceStatusTextKt.trainerTitle(state: t.state, name: t.trainerName, isMock: t.isMock),
                line: DeviceStatusTextKt.trainerStatusLine(state: t.state, isMock: t.isMock),
                tone: DeviceStatusTextKt.trainerStatusTone(state: t.state),
                helper: t.control.map { DeviceStatusTextKt.trainerHelper(state: t.state, isMock: t.isMock, control: $0) } ?? ""
            )
        case .heartRateMonitor:
            let h = app.heartRateMonitor
            return DeviceStatus(
                title: DeviceStatusTextKt.hrmTitle(state: h.state, name: h.deviceName),
                line: DeviceStatusTextKt.hrmStatusLine(state: h.state, heartRate: Int32(h.heartRate), sensorSilent: h.silent),
                tone: DeviceStatusTextKt.hrmStatusTone(state: h.state, sensorSilent: h.silent),
                helper: DeviceStatusTextKt.hrmHelper(sensorSilent: h.silent)
            )
        case .controller:
            let c = app.controller
            return DeviceStatus(
                title: DeviceStatusTextKt.controllerTitle(state: c.state, name: c.deviceName),
                line: DeviceStatusTextKt.controllerStatusLine(state: c.state, keypadSilent: c.silent),
                tone: DeviceStatusTextKt.controllerStatusTone(state: c.state, keypadSilent: c.silent),
                helper: DeviceStatusTextKt.CONTROLLER_HELPER
            )
        }
    }
}

extension StatusTone {
    var color: Color {
        switch self {
        case .good: .green
        case .busy: .orange
        case .bad: .red
        default: .secondary
        }
    }
}

extension AppModel {
    func device(_ kind: DeviceKind) -> PairableDevice {
        switch kind {
        case .trainer: trainer
        case .heartRateMonitor: heartRateMonitor
        case .controller: controller
        }
    }
}

/// One device in the Settings list: what it is and how it is.
struct DeviceRow: View {
    let kind: DeviceKind
    let app: AppModel

    var body: some View {
        let status = DeviceStatus.of(kind, app: app)
        HStack(spacing: 12) {
            Image(systemName: kind.systemImage)
                .frame(width: 32, height: 32)
                .background(.quaternary, in: Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(status.title).foregroundStyle(.primary)
                Text(status.line)
                    .font(.subheadline)
                    .foregroundStyle(status.tone.color)
            }
        }
    }
}

/// A device's status and what can be done with it: pair, disconnect, forget.
struct DeviceSheet: View {
    let kind: DeviceKind
    let app: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let device = app.device(kind)
        let status = DeviceStatus.of(kind, app: app)
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 16) {
                        Image(systemName: kind.systemImage)
                            .font(.title2)
                            .frame(width: 52, height: 52)
                            .background(.quaternary, in: Circle())
                        VStack(alignment: .leading, spacing: 4) {
                            Text(status.title).font(.title3.weight(.semibold))
                            Text(status.line).foregroundStyle(status.tone.color)
                        }
                    }
                    .padding(.vertical, 4)
                } footer: {
                    Text(status.helper)
                }

                if kind == .trainer, app.trainer.isLive {
                    Section("Live") {
                        LabeledContent("Power", value: "\(app.trainer.power) W")
                        LabeledContent("Cadence", value: "\(app.trainer.cadence) rpm")
                        LabeledContent("Heart rate", value: app.trainer.heartRate > 0 ? "\(app.trainer.heartRate) bpm" : "–")
                        if app.trainer.isMock {
                            Button("Start / stop pedalling", action: app.trainer.toggleMockPedaling)
                        }
                    }
                }

                Section {
                    switch device.state {
                    case .connected, .connecting, .disconnecting:
                        Button(device.state == .connecting ? "Cancel connection" : "Disconnect", role: .destructive) {
                            device.disconnect()
                        }
                    default:
                        NavigationLink(kind.pairTitle) { PairingView(kind: kind, app: app) }
                    }
                    if device.isPaired {
                        Button("Forget", role: .destructive) {
                            device.forget()
                            dismiss()
                        }
                    }
                }
            }
            .navigationTitle(kind == .trainer ? "Trainer" : kind == .heartRateMonitor ? "Heart rate" : "Controller")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
    }
}

/// Scan for a device and pick one; goes back by itself once it connects.
struct PairingView: View {
    let kind: DeviceKind
    let app: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let device = app.device(kind)
        let found = device.candidates.sorted { ($0.rssi?.intValue ?? -200) > ($1.rssi?.intValue ?? -200) }
        List {
            Section {
                switch device.state {
                case .scanning:
                    Label("Scanning · \(found.count) found", systemImage: "dot.radiowaves.left.and.right")
                    Text(kind.scanningHint).foregroundStyle(.secondary)
                case .connecting:
                    ProgressView("Connecting…")
                case .connected:
                    Label("Connected", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                default:
                    Text(kind.findTitle).font(.headline)
                    Text(kind.findHint).foregroundStyle(.secondary)
                    if device.state == .error {
                        Text("Connection failed. Please try again.").foregroundStyle(.red)
                    }
                }
            }

            if !found.isEmpty {
                Section("Nearby") {
                    ForEach(found, id: \.id) { candidate in
                        Button {
                            device.connect(candidate)
                        } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(candidate.name ?? kind.unknownName).foregroundStyle(.primary)
                                    Text(signal(candidate.rssi?.intValue)).font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Text("Connect").foregroundStyle(.tint)
                            }
                        }
                        .disabled(device.state == .connecting)
                    }
                }
            }

            Section {
                if device.state == .scanning {
                    Button("Stop scanning", action: device.stopScan)
                } else {
                    Button("Scan for devices", action: device.startScan)
                        .disabled(device.state == .connecting)
                }
                if kind == .trainer {
                    Button("Use the demo trainer") { app.trainer.connectMock() }
                }
            } footer: {
                if kind == .trainer {
                    Text("The demo trainer is simulated, for trying the app without one.")
                }
            }
        }
        .navigationTitle(kind.pairTitle)
        .onChange(of: device.state) { _, state in
            if state == .connected { dismiss() }
        }
        .onDisappear {
            if device.state == .scanning { device.stopScan() }
        }
    }

    private func signal(_ rssi: Int?) -> String {
        guard let rssi else { return "Signal unknown" }
        switch rssi {
        case (-60)...: return "Strong signal"
        case (-75)...: return "Good signal"
        default: return "Weak signal"
        }
    }
}

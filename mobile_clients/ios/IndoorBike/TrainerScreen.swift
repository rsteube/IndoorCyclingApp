import SwiftUI
import shared

/// Find a trainer, connect to it, and see that it is talking: the first thing
/// the iOS app can do, and the proof that the shared BLE stack runs here.
struct TrainerScreen: View {
    let trainer: TrainerModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if trainer.isConnected {
                    connected
                } else {
                    searching
                }
            }
            .navigationTitle("Trainer")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private var connected: some View {
        VStack(spacing: 32) {
            Text(trainer.trainerName ?? "Trainer")
                .font(.headline)
                .foregroundStyle(.secondary)

            VStack(spacing: 4) {
                Text("\(trainer.power)")
                    .font(.system(size: 96, weight: .bold, design: .monospaced))
                    .contentTransition(.numericText())
                Text("WATTS")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 48) {
                reading(trainer.cadence, unit: "RPM")
                reading(trainer.heartRate, unit: "BPM")
            }

            Spacer()

            if trainer.isMock {
                Button("Start / stop pedalling", action: trainer.toggleMockPedaling)
                    .buttonStyle(.bordered)
            }
            Button("Disconnect", role: .destructive, action: trainer.disconnect)
                .buttonStyle(.bordered)
        }
        .padding(.top, 48)
        .padding(.bottom, 24)
        .animation(.default, value: trainer.power)
    }

    private func reading(_ value: Int, unit: String) -> some View {
        VStack(spacing: 4) {
            Text(value > 0 ? "\(value)" : "–")
                .font(.system(size: 40, weight: .semibold, design: .monospaced))
                .contentTransition(.numericText())
            Text(unit)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
        }
    }

    private var searching: some View {
        List {
            Section {
                if trainer.isScanning {
                    Button("Stop scanning", action: trainer.stopScan)
                } else {
                    Button("Scan for trainers", action: trainer.startScan)
                }
            } footer: {
                Text(statusText)
            }

            if !trainer.candidates.isEmpty {
                Section("Found") {
                    ForEach(trainer.candidates, id: \.id) { candidate in
                        Button {
                            trainer.connect(candidate)
                        } label: {
                            HStack {
                                Text(candidate.name ?? "Unnamed trainer")
                                Spacer()
                                if let rssi = candidate.rssi {
                                    Text("\(rssi.intValue) dBm")
                                        .foregroundStyle(.secondary)
                                        .monospacedDigit()
                                }
                            }
                        }
                    }
                }
            }

            Section {
                Button("Use the demo trainer", action: trainer.connectMock)
            } footer: {
                Text("A simulated trainer, for trying the app without one. The iOS Simulator has no Bluetooth, so this is the only trainer it can see.")
            }
        }
    }

    private var statusText: String {
        switch trainer.state {
        case .scanning: "Looking for trainers nearby…"
        case .connecting: "Connecting…"
        case .error: "Couldn't connect. Wake the trainer by pedalling, then scan again."
        default: "Make sure the trainer is awake and not connected to another app."
        }
    }
}

import SwiftUI
import shared

/// Companion mode's pairing screen: open the socket, show the address and the
/// code, and hand over to the ride once the game starts describing a road.
struct CompanionScreen: View {
    let app: AppModel
    @State private var showingTrainer = false

    var body: some View {
        let companion = app.companion
        let status = companion.status
        List {
            // Only when something is wrong or not ready yet: the steps say the rest.
            if let error = status.error {
                Section {
                    Label("Could not start", systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                    Text(error).font(.subheadline)
                }
            } else if !status.running {
                Section { ProgressView("Opening the socket") }
            }
            // The way through, in order, each step ticking off as it happens:
            // on its own page there is otherwise nothing saying what comes next.
            Section("How to start") {
                let device = UIDevice.current.localizedModel
                StepRow(
                    number: 1,
                    title: "Get the trainer ready",
                    done: app.trainer.isLive,
                    detail: app.trainer.isLive
                        ? "Ready." : "The companion can connect without one, but nothing would move the trainer."
                ) {
                    if !app.trainer.isLive { Button("Pair a trainer") { showingTrainer = true } }
                }
                StepRow(
                    number: 2,
                    title: "Connect the companion",
                    done: status.controllerName != nil,
                    detail: status.controllerName.map { "\($0) is connected." }
                        ?? "Open it on a computer or console on the same Wi-Fi. It finds this \(device) by itself; if it doesn't, type in the details below, then the pairing code."
                ) {
                    EmptyView()
                }
                StepRow(
                    number: 3,
                    title: "Start the ride in the companion",
                    done: false,
                    detail: "This screen turns into the ride as soon as it starts describing the road."
                ) {
                    EmptyView()
                }
            }
            if status.running {
                Section {
                    LabeledContent("Pairing code") {
                        Text(status.token).font(.title2.monospaced().weight(.semibold)).foregroundStyle(.primary)
                    }
                    .textSelection(.enabled)
                    LabeledContent(
                        "Address",
                        value: status.addresses.isEmpty ? "No network" : status.addresses.joined(separator: ", ")
                    )
                    .textSelection(.enabled)
                    LabeledContent("Port", value: "\(status.port)").textSelection(.enabled)
                } header: {
                    Text("Connection details")
                } footer: {
                    Text(
                        status.addresses.isEmpty
                            ? "Join a Wi-Fi network: a companion connects over it."
                            : AppInfo.isDebug
                                ? "Debug build: the code is always the same." : "The code is new for every ride."
                    )
                }
            }
            Section("What this is") {
                Text(
                    "Companion mode hands the trainer to a game or app on another device, which describes the road. It needs one running."
                )
                .font(.subheadline)
                Link("Find or build a companion", destination: URL(string: "https://indoorbike.app")!)
            }
            if !companion.paired.isEmpty {
                Section("Paired companions") {
                    ForEach(companion.paired, id: \.deviceId) { paired in
                        VStack(alignment: .leading) {
                            Text(paired.name)
                            Text("Last connected \(SyncModel.relative(paired.lastSeenEpochMs))").font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .swipeActions {
                            Button("Forget", role: .destructive) {
                                companion.host.forgetPairing(deviceId: paired.deviceId)
                            }
                        }
                    }
                }
            }
            Section {
                Toggle(
                    "Show every reading while riding",
                    isOn: Binding(get: { companion.showAllData }, set: { companion.setShowAllData($0) })
                )
                #if DEBUG
                    Button("Debug: open the HUD without a companion") {
                        companion.host.prepareRideWithoutCompanion()
                        app.riding = true
                    }
                #endif
            }
        }
        .readableWidth()
        .navigationTitle("Companion")
        .onAppear {
            app.keepCompanionOpen = true
            companion.host.start()
            UIApplication.shared.isIdleTimerDisabled = true
        }
        .onDisappear {
            // Leaving for the ride keeps the socket; leaving anywhere else closes it.
            if !app.riding {
                companion.host.stop()
                UIApplication.shared.isIdleTimerDisabled = false
            }
            app.keepCompanionOpen = false
        }
        .onChange(of: status.receivingRoad) { _, receiving in if receiving && !app.riding { app.riding = true } }
        .sheet(isPresented: $showingTrainer) { DeviceSheet(kind: .trainer, app: app) }
        .companionPairingPrompt(companion)
    }
}

/// One step of the way into a companion ride: a number that becomes a tick.
private struct StepRow<Action: View>: View {
    let number: Int
    let title: String
    let done: Bool
    let detail: String
    @ViewBuilder let action: () -> Action

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: done ? "checkmark.circle.fill" : "\(number).circle")
                .font(.title2)
                .foregroundStyle(done ? AnyShapeStyle(.green) : AnyShapeStyle(.tint))
                .contentTransition(.symbolEffect(.replace))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.headline).foregroundStyle(done ? .secondary : .primary)
                Text(detail).font(.subheadline).foregroundStyle(.secondary)
                action()
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Step \(number), \(title)\(done ? ", done" : "")")
        .animation(.default, value: done)
    }
}

/// A companion ride: the game's name and colours, the road it describes, the
/// gear with big paddles, and the controls the game says it will take.
struct CompanionHUD: View {
    let app: AppModel
    let scale: CGFloat
    let wide: Bool
    let zoneColor: Color
    let heartColor: Color

    var body: some View {
        let companion = app.companion
        let trainer = app.trainer
        VStack(spacing: 12) {
            header(companion)
            if wide {
                HStack(spacing: 20) {
                    paddle(companion, "Easier", "chevron.down", -1)
                    readouts(companion, trainer).frame(maxWidth: .infinity)
                    paddle(companion, "Harder", "chevron.up", 1)
                }
            } else {
                readouts(companion, trainer).frame(maxHeight: .infinity)
                HStack(spacing: 16) {
                    paddle(companion, "Easier", "chevron.down", -1)
                    paddle(companion, "Harder", "chevron.up", 1)
                }
            }
            if let controls = companion.controls, controls.hasAnyEnabled {
                ControlPad(controls: controls) { companion.host.pressControl(control: $0) }
            }
        }
        .padding(.horizontal)
        .overlay {
            if companion.phase != .live { CompanionSessionPanel(app: app) }
        }
        .companionPairingPrompt(companion)
    }

    private func header(_ companion: CompanionModel) -> some View {
        HStack(spacing: 10) {
            if let icon = companion.icon {
                Image(uiImage: icon).resizable().frame(width: 32, height: 32).clipShape(
                    RoundedRectangle(cornerRadius: 8)
                )
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(companion.displayName ?? "Companion").font(.headline)
                let _where = [companion.status.label, companion.status.world].compactMap { $0 }.joined(separator: " · ")
                if !_where.isEmpty { Text(_where).font(.caption).foregroundStyle(.secondary) }
                if let notice = notice(companion) { Text(notice).font(.caption).foregroundStyle(.orange) }
            }
            Spacer()
            if companion.screenshotsOffered {
                Button {
                    companion.host.requestScreenshot()
                } label: {
                    Image(systemName: "camera")
                }
                .disabled(companion.screenshotWaiting)
                .accessibilityLabel("Screenshot")
            }
            if companion.status.controllerName != nil {
                Button("Take back control") { companion.host.revokeControl() }.font(.caption)
            }
        }
        .padding(10)
        .background((companion.accent ?? .gray).opacity(0.25), in: RoundedRectangle(cornerRadius: 12))
    }

    private func notice(_ companion: CompanionModel) -> String? {
        if let message = companion.screenshotMessage { return message }
        if companion.status.controllerName == nil { return "No companion connected - flat road" }
        if companion.roadStale {
            return "\(companion.displayName ?? "The companion") has gone quiet - flattening the road"
        }
        return nil
    }

    @ViewBuilder
    private func readouts(_ companion: CompanionModel, _ trainer: TrainerModel) -> some View {
        VStack(spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text("\(trainer.power)")
                    .font(.system(size: 110 * scale, weight: .bold, design: .rounded).monospacedDigit())
                    .foregroundStyle(zoneColor)
                    .minimumScaleFactor(0.5)
                    .contentTransition(.numericText())
                Text("W").font(.title2).foregroundStyle(.secondary)
            }
            if let target = companion.powerTarget {
                Text("TARGET \(target) W").font(.headline.monospacedDigit()).foregroundStyle(.secondary)
            }
            HStack(spacing: 28) {
                figure(
                    RouteFormatKt.formatGradientPercent(gradient: companion.gradient) + " %",
                    "Gradient",
                    gradientColor(companion.gradient)
                )
                figure("\(companion.drivetrain?.label(index: Int32(companion.gearIndex)) ?? 0)", "Gear", .primary)
                figure(trainer.cadence > 0 ? "\(trainer.cadence)" : "–", "RPM", .primary)
            }
            if companion.showAllData {
                HStack(spacing: 28) {
                    figure(RouteFormatKt.formatOneDecimal(value: companion.speedMps * 3.6), "km/h", .primary)
                    figure(String(format: "%.2f", companion.distanceMeters / 1000), "km", .primary)
                    figure("\(Int(companion.climbedMeters.rounded()))", "m climbed", .primary)
                    figure(trainer.heartRate > 0 ? "\(trainer.heartRate)" : "–", "BPM", heartColor)
                }
            }
        }
    }

    private func figure(_ value: String, _ label: String, _ color: Color) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.system(size: 28 * scale, weight: .semibold, design: .rounded).monospacedDigit())
                .foregroundStyle(color)
            Text(label.uppercased()).font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
        }
    }

    private func paddle(_ companion: CompanionModel, _ label: String, _ symbol: String, _ steps: Int) -> some View {
        Button {
            companion.shift(steps)
        } label: {
            VStack(spacing: 4) {
                Image(systemName: symbol).font(.title.weight(.bold))
                Text(label).font(.headline)
            }
            .frame(maxWidth: wide ? 140 : .infinity, maxHeight: wide ? .infinity : 90)
            .frame(minHeight: 90)
            .background((companion.accent ?? .gray).opacity(0.3), in: RoundedRectangle(cornerRadius: 20))
        }
        .buttonStyle(.plain)
        .disabled(app.ride.state == .countdown)
        .sensoryFeedback(.impact, trigger: companion.gearIndex)
    }
}

/// The game's own menu, steered from the phone: arrows, select and back.
private struct ControlPad: View {
    let controls: RemoteControls
    let press: (String) -> Void

    var body: some View {
        VStack(spacing: 6) {
            if let title = controls.title { Text(title).font(.caption.weight(.semibold)) }
            HStack(spacing: 8) {
                button("chevron.left", CompanionControl.shared.LEFT)
                VStack(spacing: 8) {
                    button("chevron.up", CompanionControl.shared.UP)
                    button("circle.fill", CompanionControl.shared.ENTER)
                    button("chevron.down", CompanionControl.shared.DOWN)
                }
                button("chevron.right", CompanionControl.shared.RIGHT)
                button("arrow.uturn.backward", CompanionControl.shared.BACK)
            }
            if let hint = controls.hint { Text(hint).font(.caption2).foregroundStyle(.secondary) }
        }
        .padding(8)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 12))
    }

    private func button(_ symbol: String, _ control: String) -> some View {
        Button {
            press(control)
        } label: {
            Image(systemName: symbol).frame(width: 40, height: 36).background(
                .quaternary,
                in: RoundedRectangle(cornerRadius: 8)
            )
        }
        .buttonStyle(.plain)
        .disabled(!controls.isEnabled(control: control))
    }
}

/// Between rides, or once the session is over: what was ridden, and the way out.
private struct CompanionSessionPanel: View {
    let app: AppModel

    var body: some View {
        let companion = app.companion
        VStack(alignment: .leading, spacing: 12) {
            Text(companion.phase == .ended ? "Session ended" : "Ride paused").font(.title2.weight(.bold))
            if companion.phase == .companionAway {
                Text("\(companion.displayName ?? "The companion") has disconnected. Waiting for it to reconnect.")
                    .foregroundStyle(.secondary)
                Text("Paused. Saved as a completed ride when you end it.").font(.caption).foregroundStyle(.secondary)
            }
            if companion.sessionRides.isEmpty {
                Text("No rides were recorded in this session.").foregroundStyle(.secondary)
            } else {
                Text("Rides this session").font(.headline)
                ForEach(companion.sessionRides, id: \.sessionId) { ride in
                    HStack {
                        Text(ride.name)
                        Spacer()
                        Text("\(clock(Int(ride.durationSeconds))) · \(ride.averagePowerWatts) W").monospacedDigit()
                            .foregroundStyle(.secondary)
                    }
                }
                Text("Every ride above is saved in History.").font(.caption).foregroundStyle(.secondary)
            }
            HStack {
                if companion.phase == .ended {
                    Button("Done") { app.finishRide() }.buttonStyle(.borderedProminent)
                } else {
                    Button("End ride") { app.ride.end() }.buttonStyle(.bordered)
                    Button("End session", role: .destructive) { companion.host.endSession() }.buttonStyle(
                        .borderedProminent
                    )
                }
            }
        }
        .padding(24)
        .background(.ultraThickMaterial, in: RoundedRectangle(cornerRadius: 20))
        .padding()
    }
}

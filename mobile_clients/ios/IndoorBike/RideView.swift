import SwiftUI
import shared

/// The ride: power against target, the interval and what comes next, and the
/// workout's shape with the rider's place in it.
///
/// Laid out for a phone on the bars and an iPad on a stand: side by side
/// wherever the screen is wider than tall, stacked where it is not.
struct RideView: View {
    let app: AppModel
    let ride: RideModel
    let trainer: TrainerModel
    let simulation: SimulationModel
    let ftp: Int
    let maxHr: Int
    let onClose: () -> Void

    @State private var confirmingEnd = false
    /// How much bigger than a phone this screen is; the numbers grow with it
    /// so they read from the saddle on an iPad on a stand, not only on a phone
    /// on the bars.
    @State private var scale: CGFloat = 1

    var body: some View {
        GeometryReader { geometry in
            let wide = geometry.size.width > geometry.size.height
            let _ = updateScale(geometry.size)
            VStack(spacing: 0) {
                header
                if ride.isCompanion {
                    CompanionHUD(app: app, scale: scale, wide: wide, zoneColor: liveZone.color, heartColor: heartColor)
                        .frame(maxHeight: .infinity)
                } else if ride.isSimulation {
                    RouteHUD(
                        simulation: simulation,
                        trainer: trainer,
                        zoneColor: liveZone.color,
                        heartColor: heartColor,
                        scale: scale,
                        wide: wide,
                        shiftingEnabled: ride.state != .countdown
                    )
                    .frame(maxHeight: .infinity)
                } else if ride.isFreeRide {
                    Group {
                        if wide {
                            HStack(spacing: 32) {
                                powerPanel.frame(maxWidth: .infinity)
                                freeRidePanel.frame(maxWidth: .infinity)
                            }
                        } else {
                            VStack(spacing: 24) {
                                powerPanel
                                freeRidePanel
                            }
                        }
                    }
                    .frame(maxHeight: .infinity)
                } else {
                    if wide {
                        HStack(alignment: .center, spacing: 32) {
                            powerPanel.frame(maxWidth: .infinity)
                            intervalPanel.frame(maxWidth: .infinity)
                        }
                        .frame(maxHeight: .infinity)
                    } else {
                        VStack(spacing: 24) {
                            powerPanel
                            intervalPanel
                        }
                        .frame(maxHeight: .infinity)
                    }
                    WorkoutProfile(segments: ride.segments, progress: ride.progress)
                        .frame(height: (wide ? 90 : 72) * scale)
                        .padding(.horizontal)
                }
                controls
            }
            .overlay { stateOverlay }
        }
        .background(Color.black.ignoresSafeArea())
        .preferredColorScheme(.dark)
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        .confirmationDialog("End this ride?", isPresented: $confirmingEnd, titleVisibility: .visible) {
            Button(ride.hasStarted ? "End and save" : "Leave", role: .destructive) {
                ride.end()
                if !ride.hasStarted { onClose() }
            }
        } message: {
            Text(ride.hasStarted ? "What you have ridden so far is saved." : "The workout hasn't started, so nothing is saved.")
        }
    }

    private func updateScale(_ size: CGSize) {
        // A phone's short side is about 400 pt; an iPad's is 750 to 1000.
        let next = min(max(min(size.width, size.height) / 400, 1), 2.2)
        if abs(next - scale) > 0.01 {
            DispatchQueue.main.async { scale = next }
        }
    }

    // MARK: Parts

    private var header: some View {
        HStack {
            Button { confirmingEnd = true } label: {
                Image(systemName: "xmark")
                    .font(.headline)
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel("End ride")
            Spacer()
            VStack(spacing: 2) {
                Text(ride.workout?.name ?? "")
                    .font(.headline)
                    .lineLimit(1)
                Text(ride.isOpenEnded ? clock(ride.elapsedSeconds) : "\(clock(ride.elapsedSeconds)) / \(clock(ride.totalSeconds))")
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Color.clear.frame(width: 44, height: 44)
        }
        .padding(.horizontal)
    }

    private var targetPercent: Int {
        let base = Int(ride.currentSegment?.powerPercentFTP ?? 0)
        return base * (100 + ride.biasPercent) / 100
    }

    /// A workout is coloured by the zone it asks for; a free ride and a route,
    /// which have no plan, by the zone the rider is actually in.
    private var zone: Zone { ride.isOpenEnded ? liveZone : Zone.forPercent(targetPercent) }
    private var liveZone: Zone { Zone.forPercent(ftp > 0 ? trainer.power * 100 / ftp : 0) }
    private var heartColor: Color { HeartZone.of(heartRate: trainer.heartRate, maxHr: maxHr)?.color ?? .primary }

    /// A free ride's target, and the buttons that move it ten watts at a time.
    private var freeRidePanel: some View {
        VStack(spacing: 8) {
            Text("TARGET").font(.system(size: 15 * scale, weight: .semibold)).foregroundStyle(.secondary)
            Text("\(ride.freeRideTargetWatts) W")
                .font(.system(size: 64 * scale, weight: .semibold, design: .rounded).monospacedDigit())
                .contentTransition(.numericText())
                .animation(.snappy, value: ride.freeRideTargetWatts)
            Text("± sets the wattage the trainer holds")
                .font(.system(size: 15 * scale))
                .foregroundStyle(.secondary)
        }
    }

    private var powerPanel: some View {
        VStack(spacing: 4) {
            Text("\(trainer.power)")
                .font(.system(size: 120 * scale, weight: .bold, design: .rounded).monospacedDigit())
                .minimumScaleFactor(0.5)
                .lineLimit(1)
                .foregroundStyle(zone.color)
                .contentTransition(.numericText())
                .animation(.snappy, value: trainer.power)
            Text(ride.isFreeRide ? "WATTS" : "TARGET \(trainer.targetPower) W")
                .font(.system(size: 20 * scale, weight: .semibold).monospacedDigit())
                .foregroundStyle(.secondary)
            HStack(spacing: 40 * scale) {
                Metric(value: trainer.cadence, unit: "RPM", scale: scale)
                Metric(
                    value: trainer.heartRate, unit: "BPM", scale: scale,
                    color: heartColor
                )
            }
            .padding(.top, 16 * scale)
        }
    }

    private var intervalPanel: some View {
        VStack(spacing: 8) {
            Text("\(zone.label.uppercased()) · \(targetPercent)%")
                .font(.system(size: 17 * scale, weight: .semibold))
                .foregroundStyle(zone.color)
            Text(clock(ride.remainingInSegment))
                .font(.system(size: 64 * scale, weight: .semibold, design: .rounded).monospacedDigit())
                .contentTransition(.numericText(countsDown: true))
                .animation(.snappy, value: ride.remainingInSegment)
            Text("left in this interval")
                .font(.system(size: 15 * scale))
                .foregroundStyle(.secondary)
            if let next = ride.nextSegment {
                let nextZone = Zone.forPercent(Int(next.powerPercentFTP))
                (Text("Up next · ").foregroundStyle(.secondary)
                    + Text("\(nextZone.label) \(clock(Int(next.lengthInSeconds)))").foregroundStyle(nextZone.color))
                    .font(.system(size: 15 * scale, weight: .medium))
                    .padding(.top, 8)
            } else if ride.currentSegment != nil {
                Text("Last interval")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.secondary)
                    .padding(.top, 8)
            }
        }
    }

    private var controls: some View {
        HStack(spacing: 12) {
            if !ride.isSimulation && !ride.isCompanion {
                // A route's and a companion's ± is the gear shifter in its HUD.
                ControlButton(systemImage: "minus", label: "Easier", action: ride.easier)
                if !ride.isFreeRide {
                    Text(ride.biasPercent == 0 ? "±0%" : String(format: "%+d%%", ride.biasPercent))
                        .font(.subheadline.monospacedDigit())
                        .frame(minWidth: 48)
                        .accessibilityLabel("Difficulty \(ride.biasPercent) percent")
                }
                ControlButton(systemImage: "plus", label: "Harder", action: ride.harder)
            }
            Spacer()
            if trainer.isMock {
                // The demo trainer sits still until told to pedal, and a ride,
                // or a companion's game, waits on pedalling to start.
                let pedalling = trainer.cadence > 0
                Button(pedalling ? "Demo: stop pedalling" : "Demo: pedal", action: trainer.toggleMockPedaling)
                    .buttonStyle(.bordered)
                    .tint(pedalling ? nil : .accentColor)
            }
            if ride.state == .paused {
                ControlButton(systemImage: "play.fill", label: "Resume", action: ride.resume)
            } else {
                ControlButton(systemImage: "pause.fill", label: "Pause", action: ride.pause)
                    .disabled(!ride.isRiding)
            }
            if !ride.isOpenEnded {
                ControlButton(systemImage: "forward.end.fill", label: "Skip interval", action: ride.skipSegment)
                    .disabled(!ride.isRiding)
            }
        }
        .padding()
    }

    // MARK: States

    @ViewBuilder
    private var stateOverlay: some View {
        switch ride.state {
        case .standby:
            if ride.isCompanion && !ride.hasStarted {
                Banner(title: "Ready", message: "Start the ride in the game.")
            } else {
                Banner(
                    title: ride.hasStarted ? "Paused" : "Start pedalling",
                    message: ride.hasStarted ? "Pedal to carry on." : "The workout starts when you do."
                )
            }
        case .countdown:
            Text("\(ride.countdown)")
                .font(.system(size: 160, weight: .bold, design: .rounded).monospacedDigit())
                .contentTransition(.numericText(countsDown: true))
                .animation(.snappy, value: ride.countdown)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(.black.opacity(0.6))
        case .paused:
            Banner(title: "Paused", message: "Resume when you're ready.")
        case .completed:
            CompletedCard(ride: ride, onDone: onClose)
        default:
            EmptyView()
        }
    }
}

private struct Metric: View {
    let value: Int
    let unit: String
    var scale: CGFloat = 1
    var color: Color = .primary

    var body: some View {
        VStack(spacing: 2) {
            Text(value > 0 ? "\(value)" : "–")
                .font(.system(size: 36 * scale, weight: .semibold, design: .rounded).monospacedDigit())
                .foregroundStyle(value > 0 ? color : .secondary)
                .contentTransition(.numericText())
                .animation(.snappy, value: value)
            Text(unit)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
        }
    }
}

private struct ControlButton: View {
    let systemImage: String
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.title3.weight(.semibold))
                .frame(width: 52, height: 52)
                .background(.quaternary, in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

private struct Banner: View {
    let title: String
    let message: String

    var body: some View {
        VStack(spacing: 6) {
            Text(title).font(.title2.weight(.bold))
            Text(message).foregroundStyle(.secondary)
        }
        .padding(.horizontal, 28)
        .padding(.vertical, 18)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18))
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .padding(.top, 72)
        .allowsHitTesting(false)
    }
}

private struct CompletedCard: View {
    let ride: RideModel
    let onDone: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 56))
                .foregroundStyle(.green)
            Text("Ride complete")
                .font(.title.weight(.bold))
            HStack(spacing: 32) {
                summary(clock(ride.rideSeconds), "Time")
                summary("\(ride.session.averagePowerWatts()) W", "Avg power")
            }
            Group {
                if ride.savedSessionId != nil {
                    Label("Saved to your history", systemImage: "tray.and.arrow.down.fill")
                } else if ride.saveFailed {
                    Label("Couldn't save this ride", systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                } else {
                    ProgressView("Saving…")
                }
            }
            .font(.subheadline)
            Button(ride.savedSessionId != nil ? "See the ride" : "Done", action: onDone)
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(ride.savedSessionId == nil && !ride.saveFailed)
        }
        .padding(32)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 24))
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.black.opacity(0.5))
    }

    private func summary(_ value: String, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.title2.weight(.semibold).monospacedDigit())
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
    }
}

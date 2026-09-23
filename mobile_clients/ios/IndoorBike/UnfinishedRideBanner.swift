import SwiftUI
import shared

/// A ride the app was stopped in the middle of: carry on with it, or, when
/// there is nothing left to ride, save it. The rules for which are shared
/// with Android (`describeUnfinishedRide`).
struct UnfinishedRideBanner: View {
    let app: AppModel
    @State private var confirmingAbandon = false
    @State private var saving = false

    var body: some View {
        if let ride = app.unfinished.ride {
            let name = ride.snapshot.companionCourse?.name ?? ride.workout.name
            VStack(alignment: .leading, spacing: 12) {
                Label(
                    ride.isFinished ? "Ride not saved" : "Unfinished ride",
                    systemImage: ride.isFinished ? "trophy.fill" : "pause.circle.fill"
                )
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(ride.isFinished ? .yellow : .orange)

                Text(name).font(.headline)

                if !ride.openEnded {
                    ProgressView(value: Double(ride.progress))
                        .tint(.orange)
                }

                Text(status(ride))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                HStack {
                    if ride.isFinished {
                        Button {
                            saving = true
                            app.unfinished.save { id in
                                saving = false
                                if let id { app.showRide(id) }
                            }
                        } label: {
                            Text(saving ? "Saving…" : "Save ride")
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(saving)
                    } else {
                        Button("Continue") { app.continueUnfinishedRide() }
                            .buttonStyle(.borderedProminent)
                            .disabled(!app.trainer.isConnected)
                    }
                    Button(ride.isFinished ? "Discard" : "Abandon", role: .destructive) {
                        confirmingAbandon = true
                    }
                    .buttonStyle(.bordered)
                }
                if !ride.isFinished && !app.trainer.isConnected {
                    Text("Connect your trainer to carry on.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 16))
            .confirmationDialog(
                ride.isFinished ? "Discard this ride?" : "Abandon this ride?",
                isPresented: $confirmingAbandon,
                titleVisibility: .visible
            ) {
                Button(ride.isFinished ? "Discard" : "Abandon", role: .destructive) { app.unfinished.abandon() }
                Button("Keep ride", role: .cancel) {}
            } message: {
                Text(abandonMessage(ride, name: name))
            }
        }
    }

    private func status(_ ride: UnfinishedRide) -> String {
        let elapsed = Int(ride.snapshot.elapsedSeconds)
        if ride.isFinished {
            return "\(clock(Int(ride.rideSeconds))) ridden · not yet in your history"
        } else if ride.openEnded {
            return "\(clock(elapsed)) ridden · no set finish"
        } else {
            let total = Int(ride.plannedSeconds?.intValue ?? 0)
            return "\(clock(elapsed)) / \(clock(total)) · \(Int(ride.progress * 100))% complete"
        }
    }

    private func abandonMessage(_ ride: UnfinishedRide, name: String) -> String {
        let elapsed = ride.isFinished ? Int(ride.rideSeconds) : Int(ride.snapshot.elapsedSeconds)
        if ride.isFinished {
            return "\(name) - \(clock(elapsed)) you already rode. Discarding means it never reaches your history. This can't be undone."
        } else if let total = ride.plannedSeconds?.intValue {
            return "\(name) - \(clock(elapsed)) of \(clock(Int(total))) won't be saved. This can't be undone."
        } else {
            return "\(name) - the \(clock(elapsed)) you rode won't be saved. This can't be undone."
        }
    }
}

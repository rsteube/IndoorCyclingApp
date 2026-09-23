import SwiftUI
import shared

/// At the top of Home until the rider has been through onboarding.
struct WelcomeCard: View {
    let onStart: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("WELCOME").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            Text("Welcome to Indoor Bike").font(.title2.weight(.bold))
            Text("Power-zone workouts for your smart trainer. Set your FTP and weight so the targets match you - takes about 30 seconds.")
                .foregroundStyle(.secondary)
            Button("Get started", action: onStart)
                .buttonStyle(.borderedProminent)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 16))
    }
}

/// After onboarding and before the first ride: where to start.
struct FirstRideCard: View {
    let onStart: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("START HERE · YOUR FIRST RIDE").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            Text("First Spin").font(.title3.weight(.bold))
            Text("Fifteen minutes, easy, with a taste of each zone.").foregroundStyle(.secondary)
            HStack {
                Button("Open workout", action: onStart).buttonStyle(.borderedProminent)
                Button("Dismiss", action: onDismiss).buttonStyle(.bordered)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.green.opacity(0.12), in: RoundedRectangle(cornerRadius: 16))
    }
}

/// Two steps: the rider's FTP and weight, then Bluetooth. Closing it at any
/// point counts as done, as on Android.
struct OnboardingSheet: View {
    let app: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var step = 1
    @State private var ftpText = ""
    @State private var weightText = ""

    private var ftp: Int? { Int(ftpText).flatMap { (50...600).contains($0) ? $0 : nil } }
    private var weight: Double? {
        Double(weightText.replacingOccurrences(of: ",", with: ".")).flatMap { (30...250).contains($0) ? $0 : nil }
    }

    var body: some View {
        NavigationStack {
            Form {
                if step == 1 { profile } else { bluetooth }
            }
            .navigationTitle(step == 1 ? "About you" : "Connect")
            .navigationBarTitleDisplayMode(.inline)
        }
        .onAppear {
            ftpText = String(app.settings.ftp)
            let kg = Double(app.settings.weightKg)
            weightText = kg.rounded() == kg ? String(Int(kg)) : String(format: "%.1f", kg)
        }
        .onDisappear { app.settings.finishOnboarding() }
    }

    @ViewBuilder
    private var profile: some View {
        Section {
            Text("STEP 1 OF 2 · ABOUT YOU").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            Text("Set your FTP and weight").font(.title2.weight(.bold))
            Text("We use these to scale every workout to your fitness. You can change them anytime in Settings.")
                .foregroundStyle(.secondary)
        }
        Section {
            HStack {
                VStack(alignment: .leading) {
                    Text("Functional Threshold Power")
                    Text("Sustainable hour power").font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer()
                TextField("", text: $ftpText).keyboardType(.numberPad).multilineTextAlignment(.trailing).frame(width: 72)
                Text("W").foregroundStyle(.secondary)
            }
            Button("Don't know your FTP? Start at \(UserSettingsKt.UNKNOWN_FTP_DEFAULT)W") {
                ftpText = String(UserSettingsKt.UNKNOWN_FTP_DEFAULT)
            }
            HStack {
                VStack(alignment: .leading) {
                    Text("Body weight")
                    Text("For W/kg and XP").font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer()
                TextField("", text: $weightText).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 72)
                Text("kg").foregroundStyle(.secondary)
            }
        }
        Section {
            Button("Continue") {
                if let ftp { app.settings.setFtp(ftp) }
                if let weight { app.settings.setWeightKg(Float(weight)) }
                step = 2
            }
            .disabled(ftp == nil || weight == nil)
        }
    }

    @ViewBuilder
    private var bluetooth: some View {
        Section {
            Text("STEP 2 OF 2 · CONNECT").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            Text("Allow trainer access").font(.title2.weight(.bold))
            Text("Indoor Bike talks to your smart trainer over Bluetooth. No internet, no account, no data leaves your phone.")
                .foregroundStyle(.secondary)
        }
        Section {
            Label {
                VStack(alignment: .leading) {
                    Text("Bluetooth")
                    Text("Find and pair your smart trainer, heart-rate strap, and Zwift Click controller.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            } icon: {
                Image(systemName: "dot.radiowaves.left.and.right")
            }
        }
        Section {
            Button("Allow access") {
                // iOS asks for Bluetooth the first time the app uses it; a short
                // scan is what makes it ask now rather than mid-pairing.
                app.trainer.startScan()
                DispatchQueue.main.asyncAfter(deadline: .now() + 2) { app.trainer.stopScan() }
                dismiss()
            }
            Button("Not now") { dismiss() }
        }
    }
}

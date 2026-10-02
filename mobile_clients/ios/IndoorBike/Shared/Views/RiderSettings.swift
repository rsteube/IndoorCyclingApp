import SwiftUI

/// FTP and weight: the two numbers a workout needs before it can set a target.
struct RiderSection: View {
    let settings: SettingsModel

    var body: some View {
        Section {
            Stepper(value: Binding(get: { settings.ftp }, set: { settings.setFtp($0) }), in: 50 ... 600, step: 5) {
                LabeledContent("FTP", value: "\(settings.ftp) W")
            }
        } footer: {
            Text("Every interval's target is a share of your FTP.")
        }
        Section {
            Stepper(
                value: Binding(get: { Double(settings.weightKg) }, set: { settings.setWeightKg(Float($0)) }),
                in: 30 ... 250,
                step: 0.5
            ) {
                LabeledContent("Weight", value: String(format: "%.1f kg", settings.weightKg))
            }
        } footer: {
            Text("Used for watts per kilo and the distance estimate.")
        }
    }
}

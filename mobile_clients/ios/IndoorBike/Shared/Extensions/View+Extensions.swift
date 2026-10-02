import SwiftUI

extension View {
    /// Keeps a list to a readable width on an iPad rather than stretching its
    /// rows across the screen.
    func readableWidth(_ width: CGFloat = 720) -> some View {
        GeometryReader { geometry in
            contentMargins(.horizontal, max(0, (geometry.size.width - width) / 2), for: .scrollContent)
        }
    }

    /// "Report a problem": explain what is sent, then open an email draft with
    /// the report as the body and as an attachment. Nothing leaves the phone
    /// unless the rider sends it.
    func reportProblem(isPresented: Binding<Bool>, app: AppModel, errorContext: String? = nil) -> some View {
        modifier(ReportProblem(isPresented: isPresented, app: app, errorContext: errorContext))
    }

    /// "Remember this companion?", whenever one asks, on whatever is showing.
    func companionPairingPrompt(_ companion: CompanionModel) -> some View {
        alert(
            "Remember \(companion.pendingPairing?.name ?? "this companion")?",
            isPresented: Binding(
                get: { companion.pendingPairing != nil },
                set: { if !$0 && companion.pendingPairing != nil { companion.host.declinePairing() } }
            )
        ) {
            Button("Remember") { companion.host.acceptPairing() }
            Button("Not now", role: .cancel) { companion.host.declinePairing() }
        } message: {
            Text("It will connect without a code from now on. You can forget it on the Companion screen.")
        }
    }
}

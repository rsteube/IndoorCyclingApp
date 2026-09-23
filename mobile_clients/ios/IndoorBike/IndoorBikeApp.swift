import SwiftUI

@main
struct IndoorBikeApp: App {
    // One trainer for the life of the app, as on Android: the connection
    // outlives any one screen.
    @State private var trainer = TrainerModel()

    var body: some Scene {
        WindowGroup {
            TrainerScreen(trainer: trainer)
                .task { startDemoIfAsked() }
        }
    }

    /// `-demo-trainer` on the launch arguments connects the demo trainer and
    /// starts it pedalling, so the Simulator (which has no Bluetooth) opens on
    /// a live screen. Debug builds only.
    private func startDemoIfAsked() {
        #if DEBUG
        guard ProcessInfo.processInfo.arguments.contains("-demo-trainer"), !trainer.isConnected else { return }
        trainer.connectMock()
        trainer.toggleMockPedaling()
        #endif
    }
}

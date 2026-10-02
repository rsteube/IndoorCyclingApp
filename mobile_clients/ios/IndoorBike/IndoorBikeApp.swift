import SwiftUI

@main
struct IndoorBikeApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @State private var app = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView(app: app)
                .task { app.startDemoIfAsked() }
        }
        .onChange(of: scenePhase) { _, phase in
            app.container.setForeground(value: phase == .active)
        }
    }
}

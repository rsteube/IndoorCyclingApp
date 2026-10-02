import Foundation
import SwiftUI

/// The four places in the app, as on Android: a tab bar on an iPhone, a
/// sidebar on an iPad.
struct RootView: View {
    @Bindable var app: AppModel

    private var importQuestionTitle: String {
        let names = app.library.question?.names ?? []
        return names.count == 1 ? "Replace \"\(names[0])\"?" : "Replace \(names.count) workouts?"
    }

    private var importQuestionMessage: String {
        let names = app.library.question?.names ?? []
        if names.count == 1 {
            return
                "You already have a workout with this one's id, and they differ. Replacing it changes it on your other devices too when they next sync."
        }
        let shown = names.prefix(3).joined(separator: ", ")
        let more = names.count > 3 ? " and \(names.count - 3) more" : ""
        return
            "You already have workouts with these ids, and they differ: \(shown)\(more). Replacing them changes them on your other devices too when they next sync."
    }

    var body: some View {
        TabView(selection: $app.tab) {
            Tab("Ride", systemImage: "bicycle", value: AppTab.ride) {
                RideHome(app: app)
            }
            Tab("History", systemImage: "clock.arrow.circlepath", value: AppTab.history) {
                HistoryScreen(app: app)
            }
            Tab("Progress", systemImage: "chart.line.uptrend.xyaxis", value: AppTab.progress) {
                ProgressScreen(app: app)
            }
            Tab("Settings", systemImage: "gearshape", value: AppTab.settings) {
                SettingsScreen(app: app)
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        .overlay(alignment: .top) {
            if let notice = app.notice {
                Text(notice)
                    .font(.subheadline)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(.regularMaterial, in: Capsule())
                    .padding(.top, 8)
                    .padding(.horizontal)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .onTapGesture { app.notice = nil }
            }
        }
        .animation(.snappy, value: app.notice)
        .alert(
            importQuestionTitle,
            isPresented: Binding(get: { app.library.question != nil }, set: { if !$0 { app.library.answer(.cancel) } })
        ) {
            Button("Keep both") { app.library.answer(.keepBoth) }
            Button((app.library.question?.names.count ?? 0) == 1 ? "Replace it" : "Replace them", role: .destructive) {
                app.library.answer(.replace)
            }
            Button("Cancel", role: .cancel) { app.library.answer(.cancel) }
        } message: {
            Text(importQuestionMessage)
        }
        .onOpenURL { app.open($0) }
        .fullScreenCover(isPresented: $app.riding) {
            RideView(
                app: app,
                ride: app.ride,
                trainer: app.trainer,
                simulation: app.simulation,
                ftp: app.settings.ftp,
                maxHr: app.settings.maxHr
            ) {
                app.finishRide()
            }
        }
    }
}

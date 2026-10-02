import SwiftUI
import shared

/// Rider level, weekly streak and badges, all worked out by the shared
/// progression code from the saved rides.
struct ProgressScreen: View {
    let app: AppModel

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    UnfinishedRideBanner(app: app)
                    if app.history.sessions.isEmpty {
                        ContentUnavailableView {
                            Label("Ready to level up?", systemImage: "chart.line.uptrend.xyaxis")
                        } description: {
                            Text("Complete workouts to track your fitness gains and XP over time.")
                        } actions: {
                            Button("Pick a workout") { app.tab = .ride }
                                .buttonStyle(.borderedProminent)
                        }
                    } else {
                        content
                    }
                }
                .padding()
                .frame(maxWidth: 760)
                .frame(maxWidth: .infinity)
            }
            .navigationTitle("Your progress")
        }
    }

    @ViewBuilder
    private var content: some View {
        let sessions = app.history.sessions
        let now = Int64(Date.now.timeIntervalSince1970 * 1000)
        let offset = Int64(TimeZone.current.secondsFromGMT()) * 1000
        let level = ProgressionKt.computeRiderLevel(sessions: sessions, weightKg: app.settings.weightKg)
        let streak = ProgressionKt.computeStreak(sessions: sessions, nowEpochMs: now, windowWeeks: 14, timeZoneOffsetMs: offset)
        let badges = ProgressionKt.computeBadges(sessions: sessions, nowEpochMs: now, timeZoneOffsetMs: offset)

        LevelCard(level: level)
        StreakCard(streak: streak)
        BadgesCard(earned: badges)
    }
}

private struct LevelCard: View {
    let level: RiderLevel

    var body: some View {
        Card {
            HStack(spacing: 16) {
                Text("\(level.level)")
                    .font(.system(size: 40, weight: .bold, design: .rounded))
                    .frame(width: 76, height: 76)
                    .background(Circle().fill(.orange.gradient))
                    .foregroundStyle(.white)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Rider level").font(.caption).foregroundStyle(.secondary)
                    Text(ProgressionKt.riderLevelTitle(level: level.level)).font(.title3.weight(.semibold))
                    Text("\(ProgressionKt.formatXp(xp: level.totalXp)) XP total")
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            ProgressView(value: Double(level.progressFraction))
                .tint(.orange)
            HStack {
                Text("\(ProgressionKt.formatXp(xp: max(level.xpToNextLevel, 0))) XP to level \(level.level + 1)")
                Spacer()
                Text("\(Int(level.progressFraction * 100))%")
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(.secondary)
            Text("XP = watt-minutes per kilogram. Harder efforts and longer rides earn more.")
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
    }
}

private struct StreakCard: View {
    let streak: StreakInfo

    var body: some View {
        Card {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Weekly streak").font(.caption).foregroundStyle(.secondary)
                    Text(streak.currentWeeks == 1 ? "1 week" : "\(streak.currentWeeks) weeks")
                        .font(.title2.weight(.semibold))
                }
                Spacer()
                Text("Best · \(streak.longestWeeks)")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            HStack(alignment: .bottom, spacing: 6) {
                ForEach(Array(streak.recentWeekActivity.enumerated()), id: \.offset) { _, week in
                    ZStack(alignment: .bottom) {
                        RoundedRectangle(cornerRadius: 4).fill(.quaternary)
                        RoundedRectangle(cornerRadius: 4)
                            .fill(.orange)
                            .frame(height: 48 * CGFloat(week.daysActive) / 7)
                    }
                    .frame(height: 48)
                }
            }
            .accessibilityLabel("Days ridden in each of the last \(streak.recentWeekActivity.count) weeks")
        }
    }
}

private struct BadgesCard: View {
    let earned: [Badge]

    var body: some View {
        let catalog = ProgressionKt.BADGE_CATALOG
        let byId = Dictionary(uniqueKeysWithValues: earned.map { ($0.id, $0) })
        Card(title: "Badges", trailing: "\(catalog.filter { byId[$0.id] != nil }.count) / \(catalog.count)") {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12)], spacing: 12) {
                ForEach(catalog, id: \.id) { spec in
                    let badge = byId[spec.id]
                    VStack(spacing: 8) {
                        Image(systemName: badge != nil ? badgeSymbol(spec.iconHint) : "lock.fill")
                            .font(.title2)
                            .frame(width: 52, height: 52)
                            .background(Circle().fill(badge != nil ? AnyShapeStyle(.orange.gradient) : AnyShapeStyle(.quaternary)))
                            .foregroundStyle(badge != nil ? .white : .secondary)
                        Text(spec.title).font(.subheadline.weight(.semibold))
                        Text(badge?.subtitle ?? spec.lockedHint)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .opacity(badge != nil ? 1 : 0.7)
                }
            }
        }
    }

    /// The shared catalogue names each badge's icon; this is the iOS drawing of it.
    private func badgeSymbol(_ hint: String) -> String {
        switch hint {
        case "flag": "flag.fill"
        case "trophy": "trophy.fill"
        case "clock": "clock.fill"
        case "bolt": "bolt.fill"
        case "fire": "flame"
        case "flame": "flame.fill"
        default: "star.fill"
        }
    }
}

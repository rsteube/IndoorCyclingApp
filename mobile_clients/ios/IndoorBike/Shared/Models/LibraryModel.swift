import Foundation
import Observation
import shared

/// The workout library, favourites, followed repositories, and the question an
/// import asks before it replaces anything.
@MainActor
@Observable
final class LibraryModel {
    private(set) var entries: [WorkoutEntry] = []
    private(set) var favourites: Set<String> = []
    private(set) var followed: [FollowedRepo] = []
    private(set) var status: [String: RepoStatus] = [:]
    private(set) var refreshing = false
    /// An import waiting to hear whether it may replace the rider's workouts.
    private(set) var question: ImportQuestions.Question?

    var workouts: [Workout] { entries.map(\.workout) }

    @ObservationIgnored private let container: IosAppContainer
    @ObservationIgnored private let flows = KotlinFlows()

    init(container: IosAppContainer) {
        self.container = container
        flows.watch(container.workoutLibrary.entries) { [unowned self] (value: NSArray?) in
            entries = (value as? [WorkoutEntry]) ?? []
        }
        flows.watch(container.userSettings.favouriteWorkoutIds) { [unowned self] (value: NSSet?) in
            favourites = (value as? Set<String>) ?? []
        }
        flows.watch(container.workoutLibrary.questions.pending) { [unowned self] (value: ImportQuestions.Question?) in
            question = value
        }
        let repos = container.followedRepositories
        flows.watch(repos.repos) { [unowned self] (value: NSArray?) in followed = (value as? [FollowedRepo]) ?? [] }
        flows.watch(repos.status) { [unowned self] (value: NSDictionary?) in status = (value as? [String: RepoStatus]) ?? [:] }
        flows.watch(repos.refreshing) { [unowned self] (value: KotlinBoolean?) in refreshing = value?.boolValue ?? false }
    }

    func entry(_ id: String) -> WorkoutEntry? { entries.first { $0.workout.id == id } }
    func isImported(_ id: String) -> Bool { entry(id)?.source is WorkoutSource.Imported }

    func toggleFavourite(_ id: String) {
        container.userSettings.setWorkoutFavourite(workoutId: id, favourite: !favourites.contains(id))
    }

    func answer(_ answer: ImportQuestions.Answer) {
        guard let question else { return }
        container.workoutLibrary.questions.answer(question: question, answer: answer)
    }

    var totalNew: Int { status.values.reduce(0) { $0 + Int($1.total) } }
}

/// What an import says when it's done, as a short line for the rider.
extension ImportResult {
    var notice: String? {
        switch self {
        case let workouts as ImportResult.Workouts:
            let list = workouts.workouts
            return list.count == 1 ? "Imported \"\(list[0].name)\"" : "Imported \(list.count) workouts"
        case let manifest as ImportResult.Manifest:
            return "\"\(manifest.manifest.name)\" is a community repository. Browse it under Get workouts to add individual workouts."
        case let failure as ImportResult.Failure:
            return failure.message
        default:
            return nil
        }
    }

    /// The one workout this import brought in, to open it straight away.
    var singleWorkout: Workout? {
        (self as? ImportResult.Workouts).flatMap { $0.workouts.count == 1 ? $0.workouts[0] : nil }
    }
}

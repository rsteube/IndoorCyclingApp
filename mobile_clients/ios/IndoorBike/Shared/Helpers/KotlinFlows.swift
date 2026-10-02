import shared

/// Watches Kotlin flows for one Swift model, and stops them all when the model
/// goes. `FlowWatcher` calls back on the main thread (Dispatchers.Main), which
/// is what makes `assumeIsolated` true rather than hopeful.
@MainActor
final class KotlinFlows {
    private var watching: [Watching] = []

    func watch<T: AnyObject>(
        _ flow: any Kotlinx_coroutines_coreFlow,
        _ onEach: @escaping @MainActor (T?) -> Void
    ) {
        let handle = FlowWatcher<T>(flow: flow).watch { value in
            MainActor.assumeIsolated { onEach(value) }
        }
        watching.append(handle)
    }

    /// An `Int` flow, the most common kind.
    func watchInt(_ flow: any Kotlinx_coroutines_coreFlow, _ onEach: @escaping @MainActor (Int) -> Void) {
        watch(flow) { (value: KotlinInt?) in onEach(value?.intValue ?? 0) }
    }

    deinit {
        for handle in watching { handle.cancel() }
    }
}

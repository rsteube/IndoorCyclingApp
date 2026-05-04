package app.indoorbike.shared.session

import kotlinx.serialization.Serializable

/**
 * Snapshot of an in-flight workout session — written periodically to disk so
 * a process death (OS kill, crash, reboot mid-ride) doesn't lose the user's
 * progress. On app start we check for one and offer to resume.
 */
@Serializable
data class ActiveSessionSnapshot(
    val workoutId: String,
    val sessionStartTimeEpochMs: Long,
    val ftp: Int,
    val targetBiasPercent: Int,
    val elapsedSeconds: Int,
    val currentSegmentIndex: Int,
    val elapsedInSegment: Int,
    val state: WorkoutState,
    /**
     * Wall-clock seconds the rider has actually spent in [WorkoutState.Active]
     * — distinct from [elapsedSeconds], which advances when segments are
     * skipped. Defaulted for backward-compatibility with snapshots written
     * before this field existed.
     */
    val actualRideSeconds: Int = 0,
)

/**
 * Per-platform persistence for the active session snapshot. Implementations
 * are expected to be lightweight (single small JSON blob).
 */
interface ActiveSessionStore {
    suspend fun read(): ActiveSessionSnapshot?
    suspend fun write(snapshot: ActiveSessionSnapshot)
    suspend fun clear()
}

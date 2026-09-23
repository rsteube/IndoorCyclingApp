/*
 * Copyright (C) 2026 Snapp Technology Ltd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package app.indoorbike.shared.sync

import app.indoorbike.shared.Workout
import app.indoorbike.shared.db.HistoryRepository
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.settings.UserSettings
import app.indoorbike.shared.util.Log
import app.indoorbike.shared.util.ReentrantLock
import app.indoorbike.shared.util.getCurrentEpochMillis
import app.indoorbike.shared.util.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sending rides to Intervals.icu and FitTrackee, as the app's work rather than
 * a screen's.
 *
 * Both used to be run by the post-ride summary screen, on the screen's own
 * coroutine scope, which had two consequences:
 *
 *  - A companion ride never passes through that screen - the host saves it and
 *    the rider carries on - so auto-upload never happened for one.
 *  - Tapping Done while an upload was in flight cancelled the scope. The
 *    request had usually reached the server by then; the cancellation came back
 *    as a failure, the ride was never marked as uploaded, and uploading it
 *    again by hand sent it a second time.
 *
 * So the upload and the mark that records it run here, on the app's scope, one
 * per ride and service at a time. A screen waiting on one only waits; leaving
 * does not stop it. Every path that saves a ride asks for [autoUpload].
 * Shared by both apps.
 */
class RideUploads(
    private val scope: CoroutineScope,
    private val history: HistoryRepository,
    private val settings: UserSettings,
    private val intervals: IntervalsIcuUploader,
    private val fitTrackee: FitTrackeeUploader,
) {
    enum class Service { INTERVALS, FITTRACKEE }

    private data class Key(val sessionId: Long, val service: Service)

    private val running = HashMap<Key, Deferred<*>>()
    private val runningLock = ReentrantLock()

    private val _inFlight = MutableStateFlow<Set<Pair<Long, Service>>>(emptySet())

    /** Uploads under way, by ride and service, for a screen to show. */
    val inFlight: StateFlow<Set<Pair<Long, Service>>> = _inFlight.asStateFlow()

    private val _recorded = MutableSharedFlow<Long>(extraBufferCapacity = 8)

    /** A ride whose upload was just recorded, so a screen showing it can reload it. */
    val recorded: SharedFlow<Long> = _recorded.asSharedFlow()

    /**
     * Send a just-saved ride wherever the rider has turned automatic upload
     * on. Returns at once; the uploads run on the app's scope.
     */
    fun autoUpload(sessionId: Long, workout: Workout?, ftp: Int) {
        scope.launch {
            val session = history.getSessionById(sessionId) ?: return@launch
            // An upload that is not set up answers NotConfigured before it
            // touches the network, so there is nothing to check first.
            if (settings.intervalsAutoUpload.value && session.intervalsUploadedEpoch == 0L) {
                logOutcome("Intervals.icu", uploadIntervals(session, workout, ftp))
            }
            if (settings.fitTrackeeAutoUpload.value && session.fitTrackeeUploadedEpoch == 0L) {
                logOutcome("FitTrackee", uploadFitTrackee(session, workout, ftp))
            }
        }
    }

    /** Upload [session] to Intervals.icu and record it on the ride. Leaving while waiting does not stop it. */
    suspend fun uploadIntervals(session: WorkoutSession, workout: Workout?, ftp: Int): IntervalsIcuUploader.Outcome =
        once(Key(session.id, Service.INTERVALS)) {
            val outcome = intervals.upload(session, workout, ftp)
            if (outcome is IntervalsIcuUploader.Outcome.Success) {
                history.markIntervalsUploaded(session.id, getCurrentEpochMillis())
                _recorded.tryEmit(session.id)
            }
            outcome
        }

    /** Upload [session] to FitTrackee and record it on the ride. Leaving while waiting does not stop it. */
    suspend fun uploadFitTrackee(session: WorkoutSession, workout: Workout?, ftp: Int): FitTrackeeUploader.Outcome =
        once(Key(session.id, Service.FITTRACKEE)) {
            val outcome = fitTrackee.upload(session, workout, ftp)
            if (outcome is FitTrackeeUploader.Outcome.Success) {
                history.markFitTrackeeUploaded(session.id, getCurrentEpochMillis())
                _recorded.tryEmit(session.id)
            }
            outcome
        }

    /**
     * Run [block] on the app's scope unless the same ride is already on its
     * way to the same service, in which case wait for that one: two taps, or
     * an auto-upload and a tap, must not send a ride twice.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> once(key: Key, block: suspend () -> T): T {
        val job = runningLock.withLock {
            running[key] ?: scope.async {
                try {
                    block()
                } finally {
                    runningLock.withLock { running.remove(key) }
                    _inFlight.update { it - (key.sessionId to key.service) }
                }
            }.also {
                running[key] = it
                _inFlight.update { set -> set + (key.sessionId to key.service) }
            }
        }
        return job.await() as T
    }

    private fun logOutcome(service: String, outcome: Any) {
        Log.i(TAG, "Automatic upload to $service: ${outcome::class.simpleName}")
    }

    private companion object {
        const val TAG = "RideUploads"
    }
}

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
package app.indoorbike.shared.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the watchdog that catches a heart rate strap which connects but never
 * sends anything - the failure behind the "stuck on waiting for first sample"
 * reports, where the phone's Bluetooth stack silently declines to deliver
 * notifications and every layer above it believes the link is healthy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HrmFirstSampleWatchdogTest {

    private val timeout = 15_000L

    @Test
    fun aSampleWithinTheWindowCountsAsDelivering() = runTest {
        val lastSample = MutableStateFlow(0L)
        var result: Boolean? = null

        val watcher = launch { result = awaitFirstSample(lastSample, timeout) }

        advanceTimeBy(1_000)
        lastSample.value = 1_234L
        watcher.join()

        assertTrue(result == true)
    }

    @Test
    fun silenceForTheWholeWindowCountsAsSilent() = runTest {
        val lastSample = MutableStateFlow(0L)
        var result: Boolean? = null

        val watcher = launch { result = awaitFirstSample(lastSample, timeout) }

        advanceTimeBy(timeout + 1)
        watcher.join()

        assertFalse(result == true)
    }

    /**
     * The regression this exists for: [awaitFirstSample] must judge the *new*
     * connection, not inherit credit from an earlier one. A reconnect after a
     * session that did deliver samples starts with a non-zero timestamp, and
     * comparing against zero would call that healthy without a single byte
     * having arrived.
     */
    @Test
    fun aStaleTimestampFromAnEarlierConnectionDoesNotCountAsASample() = runTest {
        val lastSample = MutableStateFlow(1_700_000_000_000L)
        var result: Boolean? = null

        val watcher = launch { result = awaitFirstSample(lastSample, timeout) }

        advanceTimeBy(timeout + 1)
        watcher.join()

        assertFalse(result == true)
    }

    @Test
    fun aSampleAfterTheWindowIsTooLate() = runTest {
        val lastSample = MutableStateFlow(0L)
        var result: Boolean? = null

        val watcher = launch { result = awaitFirstSample(lastSample, timeout) }

        advanceTimeBy(timeout + 1)
        lastSample.value = 99L
        watcher.join()

        assertFalse(result == true)
    }

    @Test
    fun deliveringWhateverTheAttemptCount() {
        assertEquals(FirstSampleOutcome.Delivering, firstSampleOutcome(true, 0, 3))
        assertEquals(FirstSampleOutcome.Delivering, firstSampleOutcome(true, 2, 3))
        assertEquals(FirstSampleOutcome.Delivering, firstSampleOutcome(true, 9, 3))
    }

    @Test
    fun earlySilentAttemptsCycleTheLink() {
        assertEquals(FirstSampleOutcome.Cycle, firstSampleOutcome(false, 1, 3))
        assertEquals(FirstSampleOutcome.Cycle, firstSampleOutcome(false, 2, 3))
    }

    /**
     * The boundary that decides whether a wedged stack turns into an endless
     * reconnect loop. At the cap the watchdog must stop cycling and let the UI
     * tell the rider to restart Bluetooth.
     */
    @Test
    fun theAttemptAtTheCapGivesUpRatherThanCyclingForever() {
        assertEquals(FirstSampleOutcome.GiveUp, firstSampleOutcome(false, 3, 3))
        assertEquals(FirstSampleOutcome.GiveUp, firstSampleOutcome(false, 4, 3))
    }
}

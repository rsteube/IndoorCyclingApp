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

import app.indoorbike.shared.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * One attempt at a connection to one peripheral, and everything that hangs
 * off it.
 *
 * Each manager used to create its peripheral in its own lifetime scope and
 * keep the pieces (state listener, observers, handshake) in separate job
 * fields. A peripheral made in the manager's scope lived as long as the
 * manager, and cancelling the job that connected it released nothing: a
 * trainer tapped while another was still connecting stayed connected, unheard,
 * until the process died, and every connect left one more peripheral behind.
 *
 * A link owns a scope of its own, a child of the manager's, and the peripheral
 * and every coroutine launched for it live there. Ending the link cancels that
 * scope, which stops every observer and has the transport close the GATT connection, so
 * nothing belonging to an old link outlives it. Managers also compare a
 * callback's link against their current one before touching shared state, so
 * a link being replaced cannot write over the one replacing it.
 */
internal class BleLink private constructor(
    val scope: CoroutineScope,
    val peripheral: BlePeripheral,
) {
    /**
     * The peripheral's state from its first step towards a connection on.
     *
     * Kable starts every peripheral Disconnected, and so does every other
     * transport behind [BleCentral]. A listener that read that
     * as a drop tore its own link down before the connect had begun, which
     * left the manager showing Disconnected over a live connection that
     * nothing could then disconnect.
     */
    val states: Flow<BleLinkState>
        get() = peripheral.state.dropWhile { it == BleLinkState.Disconnected }

    /**
     * Disconnect politely, then end everything the link owns. A peripheral
     * that does not answer in time is closed anyway when the scope goes.
     */
    suspend fun close() {
        try {
            withTimeoutOrNull(CLOSE_TIMEOUT_MS) { peripheral.disconnect() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Disconnect failed; closing the link anyway", e)
        }
        scope.cancel()
    }

    companion object {
        private const val TAG = "BleLink"

        /** How long a polite disconnect gets before the link is closed under it. */
        const val CLOSE_TIMEOUT_MS = 3_000L

        /**
         * A link to [advertisement] through [central]. An observation that
         * fails is logged under [logTag] with [label] ("Trainer", "HRM").
         */
        fun open(
            parent: CoroutineScope,
            central: BleCentral,
            advertisement: BleAdvertisement,
            logTag: String,
            label: String,
        ): BleLink {
            val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
            return BleLink(scope, central.peripheral(scope, advertisement, logTag, label))
        }
    }
}

/**
 * Wait out one connect attempt: true once it reaches Connected, false if it
 * ends first. The reconnect workers use it to tell a connection that came up
 * and later dropped, which earns a quick retry, from one that never came up,
 * which earns a longer wait each time.
 */
internal suspend fun connectionCameUp(state: StateFlow<TrainerConnectionState>): Boolean =
    state.first {
        it == TrainerConnectionState.Connected ||
            it == TrainerConnectionState.Disconnected ||
            it == TrainerConnectionState.Error
    } == TrainerConnectionState.Connected

/**
 * The single thread each Bluetooth manager runs on.
 *
 * Every manager's state was plain fields written from the caller's thread, the
 * state listener, each characteristic's observer and the reconnect worker, on a
 * multi-threaded dispatcher. Lists iterated on one thread while another
 * appended to them, and a disconnect could be read half-applied. One thread per
 * manager makes every one of those an ordering question with an answer: public
 * calls hop onto it, and everything the manager launches runs on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun bleManagerScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1))

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

import com.juul.kable.Bluetooth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach

/**
 * Shared tuning for the persistent auto-reconnect loops in [TrainerManager],
 * [HeartRateMonitor], and [ControllerManager].
 *
 * The loop scans for the paired device in windows rather than holding one scan
 * open forever: it's normal for a rider to open the app before waking their
 * trainer, so we keep retrying and pick the device up when it starts
 * advertising. Backoff grows between empty windows so a device that never comes
 * online settles to a low duty cycle instead of scanning flat-out. Scanning is
 * gated to app-foreground-or-active-ride by the caller (see `pauseAutoReconnect`
 * / `resumeAutoReconnect`), so a backgrounded idle app isn't draining the radio.
 */
internal object BleReconnectPolicy {
    /** Max time a single scan window waits for the paired device to appear. */
    const val SCAN_WINDOW_MS = 20_000L

    /** Backoff before the first re-scan after an empty window. */
    const val INITIAL_BACKOFF_MS = 3_000L

    /** Ceiling the empty-window backoff grows toward. */
    const val MAX_BACKOFF_MS = 60_000L

    /** Brief settle after a drop before scanning to reconnect. */
    const val POST_DROP_DELAY_MS = 2_000L
}

/**
 * Suspends until the platform is actually able to scan, returning whether it
 * had to wait.
 *
 * The reconnect loops call this before opening a scan window, which fixes two
 * problems at once that neither the backoff nor a naive retry could solve
 * alone:
 *
 *  - **Scanning with the adapter off is pure waste.** The scan throws
 *    immediately rather than running its window, so retrying on the normal
 *    cadence would spin, and every attempt would write a failure line. A short
 *    retry would flush the diagnostics buffer within a minute; that is exactly
 *    why the growing backoff was there, and why it cannot be the whole answer.
 *  - **Backoff makes recovery feel broken.** Measured on a Fairphone 4: after
 *    the adapter came back the loop waited about 45 seconds before its next
 *    scan, because the backoff had already grown while Bluetooth was off. Since
 *    the UI now tells riders with a silent strap to restart Bluetooth, a minute
 *    of nothing happening reads as "that did not work".
 *
 * Parking on the adapter's own state signal solves both. Nothing is attempted
 * and nothing is logged while Bluetooth is off, and the moment it returns the
 * caller resets its backoff and scans immediately, because time spent unable to
 * scan is not evidence that the device is missing.
 *
 * [onWait] is invoked once per park, with the reason, so a bug report drafted
 * later shows *why* nothing was happening. That matters most below API 31,
 * where this also covers location services being off: BLE scans there return
 * nothing at all rather than failing, so the old behaviour was to scan forever
 * and find nothing with no explanation anywhere.
 *
 * [availability] is injectable so the decision can be unit-tested without a
 * Bluetooth adapter; production always uses Kable's platform-backed flow.
 */
internal suspend fun awaitBluetoothAvailable(
    availability: Flow<Bluetooth.Availability> = Bluetooth.availability,
    onWait: (String) -> Unit = {},
): Boolean {
    var waited = false
    availability
        .onEach { state ->
            if (state is Bluetooth.Availability.Unavailable && !waited) {
                waited = true
                onWait(state.reason?.toString() ?: "unknown")
            }
        }
        .first { it is Bluetooth.Availability.Available }
    return waited
}

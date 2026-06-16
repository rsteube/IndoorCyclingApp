/*
 * Copyright (C) 2026 Juhani Lehtimäki
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

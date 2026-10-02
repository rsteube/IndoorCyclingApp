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

/**
 * What each device row in Settings says about the device, shared by both apps
 * so a state reads the same on either phone. The colour of a line is the
 * platform's; how worried it should look is [StatusTone].
 */

/** How a status line should look: fine, working on it, wrong, or nothing there. */
enum class StatusTone { Good, Busy, Bad, Idle }

private fun toneOf(state: TrainerConnectionState, silent: Boolean = false): StatusTone = when (state) {
    TrainerConnectionState.Connected -> if (silent) StatusTone.Bad else StatusTone.Good
    TrainerConnectionState.Scanning,
    TrainerConnectionState.Connecting,
    TrainerConnectionState.Disconnecting -> StatusTone.Busy
    TrainerConnectionState.Error -> StatusTone.Bad
    TrainerConnectionState.Disconnected -> StatusTone.Idle
}

// ─── Trainer ──────────────────────────────────────────────────────────────────

fun trainerTitle(state: TrainerConnectionState, name: String?, isMock: Boolean): String = when {
    state == TrainerConnectionState.Connected && name != null -> name
    isMock -> "Mock trainer"
    else -> "Trainer"
}

fun trainerStatusLine(state: TrainerConnectionState, isMock: Boolean): String = when (state) {
    TrainerConnectionState.Scanning -> "Scanning…"
    TrainerConnectionState.Connecting -> "Connecting…"
    TrainerConnectionState.Connected -> if (isMock) "Mock connected" else "Connected"
    TrainerConnectionState.Disconnecting -> "Disconnecting…"
    TrainerConnectionState.Disconnected -> "Not paired"
    TrainerConnectionState.Error -> "Connection error · tap to retry"
}

fun trainerStatusTone(state: TrainerConnectionState): StatusTone = toneOf(state)

/**
 * The trainer sheet's explanation: what kinds of machine pair, until one has,
 * and then how the app is driving the one that did.
 *
 * Worth saying because the machines differ in ways a rider feels. A bike that
 * only takes a resistance level is steered onto a wattage by measuring it,
 * which takes a few seconds after each change, and a rider who does not know
 * that reads it as the app being slow.
 */
fun trainerHelper(state: TrainerConnectionState, isMock: Boolean, control: TrainerControl): String {
    if (state != TrainerConnectionState.Connected || isMock) {
        return "Smart trainers and smart bikes using FTMS or Cycling Power Service. " +
            "Wahoo Kickr, Tacx Neo, Saris, Elite, etc."
    }
    return when {
        control.power == ControlPath.NATIVE && control.road == ControlPath.NATIVE ->
            "Workouts hold you at their target wattage, and routes set the resistance of the road."
        control.power == ControlPath.NATIVE && control.road == ControlPath.VIA_POWER ->
            "This trainer can't simulate a road, so routes hold you at the wattage the road " +
                "asks for at your cadence. Workouts use its own ERG."
        control.power == ControlPath.VIA_RESISTANCE ->
            "This machine only takes a resistance level, so the app sets it by measuring your " +
                "power. Allow a few seconds for it to settle after each change."
        control.power == ControlPath.MANUAL && control.road == ControlPath.NATIVE ->
            "Routes set the resistance of the road. This trainer can't hold a wattage, so in " +
                "workouts set the effort yourself."
        else ->
            "This machine's resistance can't be set from the app, so set it yourself. Your " +
                "power, speed and progress are still recorded as you ride."
    }
}

// ─── Heart rate monitor ───────────────────────────────────────────────────────

fun hrmTitle(state: HrmConnectionState, name: String?): String =
    if (state == HrmConnectionState.Connected && name != null) name else "Heart rate monitor"

fun hrmStatusLine(state: HrmConnectionState, heartRate: Int, sensorSilent: Boolean): String = when (state) {
    HrmConnectionState.Scanning -> "Scanning…"
    HrmConnectionState.Connecting -> "Connecting…"
    HrmConnectionState.Connected -> when {
        heartRate > 0 -> "$heartRate bpm"
        // "Waiting" implies patience will fix it. Once the monitor has given up
        // retrying, it won't, and the rider needs to act. See
        // HeartRateMonitor.sensorSilent.
        sensorSilent -> "Connected but sending nothing · restart Bluetooth"
        else -> "Connected · waiting for first sample"
    }
    HrmConnectionState.Disconnecting -> "Disconnecting…"
    HrmConnectionState.Disconnected -> "Not paired"
    HrmConnectionState.Error -> "Connection error · tap to retry"
}

fun hrmStatusTone(state: HrmConnectionState, sensorSilent: Boolean): StatusTone = toneOf(state, sensorSilent)

fun hrmHelper(sensorSilent: Boolean): String =
    if (sensorSilent) {
        "The strap is connected but this phone isn't receiving anything from it, and reconnecting " +
            "hasn't helped. This is usually the phone's Bluetooth rather than the strap: turn " +
            "Bluetooth off and on again, or restart the phone. Closing and reopening the app will " +
            "not clear it."
    } else {
        "Bluetooth chest strap (Polar H10, Wahoo TICKR, Garmin HRM-Pro/Dual, etc.). Wake the " +
            "strap by snapping it on or moistening the contacts before scanning."
    }

// ─── Controller ───────────────────────────────────────────────────────────────

fun controllerTitle(state: ControllerConnectionState, name: String?): String =
    if (state == ControllerConnectionState.Connected && name != null) name else "Controller"

fun controllerStatusLine(state: ControllerConnectionState, keypadSilent: Boolean): String = when (state) {
    ControllerConnectionState.Scanning -> "Scanning…"
    ControllerConnectionState.Connecting -> "Connecting…"
    // Connected is not the same as usable: a controller whose protocol the app
    // cannot speak pairs and reports itself connected like any other.
    ControllerConnectionState.Connected ->
        if (keypadSilent) "Connected but not answering · use in-app ± buttons"
        else "Connected · in-ride ± control"
    ControllerConnectionState.Disconnecting -> "Disconnecting…"
    ControllerConnectionState.Disconnected -> "Not paired · use in-app ± buttons"
    ControllerConnectionState.Error -> "Connection error · tap to retry"
}

fun controllerStatusTone(state: ControllerConnectionState, keypadSilent: Boolean): StatusTone =
    toneOf(state, keypadSilent)

const val CONTROLLER_HELPER =
    "Bluetooth ride controller for in-workout ± difficulty. Supported: Zwift Click, including " +
        "the encrypted firmware, and Zwift Ride. Zwift Play is not supported yet. With no " +
        "controller paired, the in-app ± buttons still work."

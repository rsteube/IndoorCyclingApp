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
package app.indoorbike.shared.sailfish

import app.indoorbike.shared.ble.TrainerConnectionState
import app.indoorbike.shared.ble.controllerStatusLine
import app.indoorbike.shared.ble.controllerStatusTone
import app.indoorbike.shared.ble.controllerTitle
import app.indoorbike.shared.ble.hrmHelper
import app.indoorbike.shared.ble.hrmStatusLine
import app.indoorbike.shared.ble.hrmStatusTone
import app.indoorbike.shared.ble.hrmTitle
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The heart rate monitor and the ride controller (Zwift Click and family), as
 * the `hrm` and `controller` topics and their `hrm.*` and `controller.*`
 * commands. Both have the trainer's shape: a connection, a pairing scan's
 * finds, and what they are telling the ride.
 */
internal fun SailfishCore.installSensors() {
    val hrm = heartRateMonitor
    bus.publish(
        "hrm",
        combine(hrm.connectionState, hrm.connectedDeviceName, hrm.pairedDeviceId, hrm.heartRate, hrm.sensorSilent) {
                state, name, pairedId, bpm, silent ->
            buildJsonObject {
                put("state", state.name)
                put("connected", state == TrainerConnectionState.Connected)
                put("name", name)
                put("pairedId", pairedId)
                put("heartRate", bpm)
                put("silent", silent)
                put("title", hrmTitle(state, name))
                put("status", hrmStatusLine(state, bpm, silent))
                put("helper", hrmHelper(silent))
                put("tone", hrmStatusTone(state, silent).name)
            }
        }.combine(hrm.discoveredDevices) { status, found ->
            buildJsonObject {
                status.forEach { (k, v) -> put(k, v) }
                put("candidates", buildJsonArray {
                    found.forEach { add(candidate(it.id, it.name ?: "Heart rate monitor", it.rssi)) }
                })
            }
        },
        minIntervalMs = 250,
    ) { it }
    bus.command("hrm.scan") { hrm.startScan(); JsonNull }
    bus.command("hrm.stopScan") { hrm.stopScan(); JsonNull }
    bus.command("hrm.connect") { args -> hrm.connectToCandidate(args.requireString("id")); JsonNull }
    bus.command("hrm.disconnect") { hrm.disconnect(); JsonNull }
    bus.command("hrm.forget") {
        hrm.forgetDevice()
        userSettings.setPairedHrmId(null)
        JsonNull
    }

    val controller = controllerManager
    bus.publish(
        "controller",
        combine(
            controller.connectionState, controller.connectedDeviceName, controller.pairedDeviceId,
            controller.keypadSilent, controller.discoveredDevices,
        ) { state, name, pairedId, silent, found ->
            buildJsonObject {
                put("state", state.name)
                put("connected", state == TrainerConnectionState.Connected)
                put("name", name)
                put("pairedId", pairedId)
                put("silent", silent)
                put("title", controllerTitle(state, name))
                put("status", controllerStatusLine(state, silent))
                put("tone", controllerStatusTone(state, silent).name)
                put("candidates", buildJsonArray {
                    found.forEach { add(candidate(it.id, it.name ?: "Controller", it.rssi)) }
                })
            }
        },
        minIntervalMs = 250,
    ) { it }
    bus.command("controller.scan") { controller.startScan(); JsonNull }
    bus.command("controller.stopScan") { controller.stopScan(); JsonNull }
    bus.command("controller.connect") { args -> controller.connectToCandidate(args.requireString("id")); JsonNull }
    bus.command("controller.disconnect") { controller.disconnect(); JsonNull }
    bus.command("controller.forget") {
        controller.forgetDevice()
        userSettings.setPairedControllerId(null)
        JsonNull
    }
}

private fun candidate(id: String, name: String, rssi: Int?) = buildJsonObject {
    put("id", id)
    put("name", name)
    put("rssi", rssi)
}

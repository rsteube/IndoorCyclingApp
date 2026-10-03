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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The trainer, as the `trainer` topic and the `trainer.*` commands.
 *
 * `trainer` carries the connection, the devices a pairing scan has found, the
 * live readings and what the trainer is being asked for, at most four times a
 * second: the rate real trainers notify at, and far above what a screen of
 * numbers needs.
 */
internal fun SailfishCore.installTrainer() {
    val tm = trainerManager

    /** Whether the demo trainer is pedalling; the manager keeps it to itself. */
    val mockPedaling = MutableStateFlow(false)

    val connection = combine(
        tm.connectionState, tm.connectedTrainerName, tm.pairedTrainerId, tm.isMockTrainer, mockPedaling,
    ) { state, name, pairedId, mock, pedaling ->
        buildJsonObject {
            put("state", state.name)
            put("connected", state == TrainerConnectionState.Connected)
            put("name", name)
            put("pairedId", pairedId)
            put("isMock", mock)
            put("mockPedaling", pedaling)
        }
    }
    val readings = combine(tm.power, tm.cadence, tm.heartRate, tm.targetPower, tm.lastTelemetryEpochMs) { p, c, hr, target, at ->
        buildJsonObject {
            put("power", p)
            put("cadence", c)
            put("heartRate", hr)
            put("targetPower", target)
            put("lastTelemetryMs", at)
        }
    }
    val control = combine(tm.ergError, tm.simulationParameters, tm.control) { error, road, control ->
        buildJsonObject {
            put("ergError", error)
            put("simulating", road != null)
            put("gradePercent", road?.gradePercent?.let(::JsonPrimitive) ?: JsonNull)
            put("powerPath", control.power.name)
            put("roadPath", control.road.name)
        }
    }
    val candidates = tm.discoveredDevices

    bus.publish("trainer", combine(connection, readings, control, candidates) { a, b, c, list ->
        buildJsonObject {
            a.forEach { (k, v) -> put(k, v) }
            b.forEach { (k, v) -> put(k, v) }
            c.forEach { (k, v) -> put(k, v) }
            put("candidates", buildJsonArray {
                list.forEach { candidate ->
                    add(buildJsonObject {
                        put("id", candidate.id)
                        put("name", candidate.name ?: "Unknown trainer")
                        put("rssi", candidate.rssi)
                    })
                }
            })
        }
    }, minIntervalMs = 250) { it }

    bus.command("trainer.scan") { tm.startScan(); JsonNull }
    bus.command("trainer.stopScan") { tm.stopScan(); JsonNull }
    bus.command("trainer.connect") { args -> tm.connectToCandidate(args.requireString("id")); JsonNull }
    bus.command("trainer.disconnect") { tm.disconnect(); JsonNull }
    bus.command("trainer.forget") { tm.forgetTrainer(); JsonNull }
    bus.command("trainer.clearErgError") { tm.clearErgError(); JsonNull }
    bus.command("trainer.demo") {
        tm.connectMock()
        mockPedaling.value = false
        JsonNull
    }
    bus.command("trainer.demoPedal") {
        if (!tm.isMockTrainer.value) throw UserFacing("The demo trainer is not connected.")
        tm.toggleMockPedaling()
        mockPedaling.value = !mockPedaling.value
        JsonPrimitive(mockPedaling.value)
    }
}

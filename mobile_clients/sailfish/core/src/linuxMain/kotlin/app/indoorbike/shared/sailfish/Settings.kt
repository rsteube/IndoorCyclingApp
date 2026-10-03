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

import app.indoorbike.shared.settings.MAX_BIKE_WEIGHT_KG
import app.indoorbike.shared.settings.MAX_FTP
import app.indoorbike.shared.settings.MAX_MAX_HR
import app.indoorbike.shared.settings.MAX_WEIGHT_KG
import app.indoorbike.shared.settings.MIN_BIKE_WEIGHT_KG
import app.indoorbike.shared.settings.MIN_FTP
import app.indoorbike.shared.settings.MIN_MAX_HR
import app.indoorbike.shared.settings.MIN_WEIGHT_KG
import app.indoorbike.shared.simulation.Drivetrain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The rider's settings as the `settings` topic, and `settings.set` to change
 * one. A value outside a setting's range is ignored, as on Android: the topic
 * then still carries the old one, which is how the page puts the field back.
 */
internal fun SailfishCore.installSettings() {
    val s = userSettings
    val flows: List<Flow<Any?>> = listOf(
        s.ftp, s.weightKg, s.maxHr, s.bikeWeightKg, s.drivetrainId, s.hasCompletedOnboarding,
        s.hasDismissedStarterCard, s.deviceLabel, s.pairedTrainerId, s.pairedHrmId, s.pairedControllerId,
    )
    bus.publish("settings", combine(flows) { it.toList() }) { v ->
        buildJsonObject {
            put("ftp", v[0] as Int)
            put("weightKg", v[1] as Float)
            put("maxHr", v[2] as Int)
            put("bikeWeightKg", v[3] as Float)
            put("drivetrainId", v[4] as String)
            put("onboarded", v[5] as Boolean)
            put("starterDismissed", v[6] as Boolean)
            put("deviceLabel", v[7] as String)
            put("pairedTrainerId", v[8] as String?)
            put("pairedHrmId", v[9] as String?)
            put("pairedControllerId", v[10] as String?)
            put("limits", buildJsonObject {
                put("ftp", buildJsonArray { add(JsonPrimitive(MIN_FTP)); add(JsonPrimitive(MAX_FTP)) })
                put("weightKg", buildJsonArray { add(JsonPrimitive(MIN_WEIGHT_KG)); add(JsonPrimitive(MAX_WEIGHT_KG)) })
                put("maxHr", buildJsonArray { add(JsonPrimitive(MIN_MAX_HR)); add(JsonPrimitive(MAX_MAX_HR)) })
                put("bikeWeightKg", buildJsonArray {
                    add(JsonPrimitive(MIN_BIKE_WEIGHT_KG))
                    add(JsonPrimitive(MAX_BIKE_WEIGHT_KG))
                })
            })
            put("drivetrains", buildJsonArray {
                Drivetrain.ALL.forEach { d ->
                    add(buildJsonObject {
                        put("id", d.id)
                        put("name", d.name)
                    })
                }
            })
        }
    }

    /** {"cmd": "settings.set", "key": "ftp", "value": 250}: one setting. */
    bus.command("settings.set") { args ->
        val key = args.requireString("key")
        when (key) {
            "ftp" -> s.setFtp(args.requireInt("value"))
            "weightKg" -> s.setWeightKg(args.double("value")?.toFloat() ?: throw UserFacing("Missing value"))
            "maxHr" -> s.setMaxHr(args.requireInt("value"))
            "bikeWeightKg" -> s.setBikeWeightKg(args.double("value")?.toFloat() ?: throw UserFacing("Missing value"))
            "drivetrainId" -> s.setDrivetrainId(args.requireString("value"))
            "onboarded" -> s.setHasCompletedOnboarding(args.bool("value") ?: true)
            "starterDismissed" -> s.setHasDismissedStarterCard(args.bool("value") ?: true)
            "deviceLabel" -> s.setDeviceLabel(args.requireString("value"))
            else -> throw UserFacing("No setting $key")
        }
        JsonNull
    }
}

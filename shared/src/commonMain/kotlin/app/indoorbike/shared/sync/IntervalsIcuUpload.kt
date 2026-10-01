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

import app.indoorbike.shared.fit.RideAttribution
import app.indoorbike.shared.fit.VirtualWorld

/**
 * What an upload to Intervals.icu says about a ride besides the file itself.
 *
 * Intervals.icu works the device out of the file when nobody tells it, and out
 * of Indoor Bike's files it worked out "1". `device_name` is the one documented
 * way to set it ("Device the activity was created on"), so it is always sent.
 * The file names the app as well now, which covers a ride uploaded by hand from
 * a shared file; see [RideAttribution].
 *
 * A virtual world has no field on Intervals.icu at all, so a ride a game drove
 * is named the way Zwift names its rides, "Col de Berg: <course>", and says
 * where it was ridden in its description. That is for people. Machines read the
 * developer fields in the file, which Intervals.icu keeps with the original.
 */
object IntervalsIcuUpload {

    /** The device Intervals.icu shows for every ride this app uploads. */
    const val DEVICE_NAME = RideAttribution.PRODUCT_NAME

    /** The activity's name: the ride's own, or the game's and its course's. */
    fun activityName(rideName: String, world: VirtualWorld?): String {
        if (world != null) {
            return if (world.route != null) "${world.platform}: ${world.route}" else world.platform
        }
        return rideName.trim().ifEmpty { "Indoor ride" }
    }

    /** The activity's description, or null for a ride no game drove. */
    fun description(world: VirtualWorld?): String? =
        world?.let { "Ridden in ${it.platform} on ${RideAttribution.PRODUCT_NAME}" }

    /**
     * The query parameters for `POST /api/v1/athlete/{id}/activities`, in order
     * and not yet encoded. [externalId] is the ride's own id, kept by
     * Intervals.icu so the activity can be traced back to the ride.
     */
    fun parameters(rideName: String, externalId: String, world: VirtualWorld?): List<Pair<String, String>> =
        buildList {
            add("device_name" to DEVICE_NAME)
            add("name" to activityName(rideName, world))
            description(world)?.let { add("description" to it) }
            if (externalId.isNotBlank()) add("external_id" to externalId)
        }
}

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
package app.indoorbike.shared.activity

import app.indoorbike.shared.Workout
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.db.WorkoutTelemetry

/**
 * Encodes a completed indoor ride as a Garmin TCX (Training Center Database v2)
 * file. TCX is the natural XML sibling of our FIT export: it carries time,
 * heart rate, cadence, and power (via the ActivityExtension `Watts` element)
 * with no GPS track required, so it suits indoor cycling and is accepted by
 * Strava, Garmin Connect, intervals.icu, TrainingPeaks, Golden Cheetah, etc.
 *
 * One `Lap` is emitted per segment the ride actually rode (or a single
 * whole-ride lap when [workout] is null), laid out by the same [ExportRide] as
 * [app.indoorbike.shared.fit.RideFitBuilder] so the two exports describe the
 * same structure, the same distance and the same heart rate. Output is a self-contained
 * UTF-8 XML string the caller can write to disk or hand to a share intent.
 *
 * Pure Kotlin in commonMain (no kotlinx-datetime dependency) - timestamps are
 * formatted to ISO-8601 UTC by [isoUtc].
 */
object TcxBuilder {

    private const val NS = "http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2"
    private const val NS_AX = "http://www.garmin.com/xmlschemas/ActivityExtension/v2"
    private const val NS_XSI = "http://www.w3.org/2001/XMLSchema-instance"

    private class Pt(
        /** When the sample was taken, from the start, pauses included. */
        val wallSec: Int,
        /** How far into the ride it was, pauses left out. Laps are cut on this. */
        val rideSec: Int,
        val power: Int,
        val hr: Int,
        val cad: Int,
        /** Where a companion game said the rider was, or null for nowhere. */
        val latitude: Double? = null,
        val longitude: Double? = null,
        val altitudeMeters: Double? = null,
    ) {
        /** Virtual speed for this sample's watts, and the ride distance at it. */
        var speedMps: Double = 0.0
        var metres: Double = 0.0
    }

    fun build(
        session: WorkoutSession,
        telemetry: List<WorkoutTelemetry>,
        workout: Workout?,
        /** Today's FTP, used only when history did not record the ride's own. */
        ftp: Int,
        /**
         * Rider body mass, for the virtual speed and distance a trainer has no
         * road to measure. 0 = not set, in which case the speed elements are
         * left out entirely and distance stays at zero, as it was before there
         * was an estimate to make - unless the ride was on a road, whose own
         * distance is used either way.
         */
        riderKg: Float = 0f,
    ): String {
        val ride = ExportRide(session, telemetry, workout, ftp, riderKg)
        val hasDistance = ride.hasDistance
        val startMs = session.startTimeEpoch
        val timeline = ride.timeline

        // One sample per integer second, in time order, placed on both clocks
        // by the same timeline the FIT writer uses so both exports see
        // identical data. TCX has no pause marker: a pause is the gap between
        // two trackpoints, and each lap's TotalTimeSeconds leaves it out.
        var running = 0.0
        val pts = timeline.points.mapIndexed { pi, point ->
            val s = telemetry[point.index]
            running += ride.metres[pi]
            Pt(
                wallSec = point.wallSeconds,
                rideSec = point.rideSeconds,
                power = s.power.toInt().coerceAtLeast(0),
                hr = s.heartRate.toInt().coerceIn(0, 254),
                cad = s.cadence.toInt().coerceIn(0, 254),
                latitude = s.latitude?.takeIf { s.longitude != null },
                longitude = s.longitude?.takeIf { s.latitude != null },
                altitudeMeters = s.altitudeMeters,
            ).also {
                it.speedMps = ride.metres[pi]
                it.metres = running
            }
        }

        val sb = StringBuilder(pts.size * 96 + 1024)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<TrainingCenterDatabase xmlns=\"").append(NS)
            .append("\" xmlns:ns3=\"").append(NS_AX)
            .append("\" xmlns:xsi=\"").append(NS_XSI).append("\">\n")
        sb.append("  <Activities>\n")
        sb.append("    <Activity Sport=\"Biking\">\n")
        sb.append("      <Id>").append(isoUtc(startMs)).append("</Id>\n")

        for (lap in ride.laps) {
            val lapPts = pts.subList(lap.firstPoint, lap.endPoint)
            val lapDur = lap.timerSeconds

            var sumP = 0L; var maxP = 0
            var sumHr = 0L; var nHr = 0; var maxHr = 0
            var sumCad = 0L
            var lapMetres = 0.0
            var lapMaxSpeed = 0.0
            for (p in lapPts) {
                sumP += p.power; if (p.power > maxP) maxP = p.power
                if (p.hr > 0) { sumHr += p.hr; nHr++; if (p.hr > maxHr) maxHr = p.hr }
                sumCad += p.cad
                lapMetres += p.speedMps
                if (p.speedMps > lapMaxSpeed) lapMaxSpeed = p.speedMps
            }
            val n = lapPts.size.coerceAtLeast(1)
            val avgP = (sumP / n).toInt()
            val avgCad = (sumCad / n).toInt().coerceIn(0, 254)
            val avgHr = if (nHr > 0) (sumHr / nHr).toInt() else 0
            // Sum of per-second watts = joules; /1000 = kJ ≈ kcal for cycling.
            val kj = (sumP / 1000L).toInt()

            sb.append("      <Lap StartTime=\"")
                .append(isoUtc(startMs + timeline.wallAt(lap.startRideSeconds) * 1000L)).append("\">\n")
            sb.append("        <TotalTimeSeconds>").append(lapDur).append("</TotalTimeSeconds>\n")
            // Schema order is fixed: DistanceMeters, then MaximumSpeed,
            // then Calories. A reader that meets them out of order rejects
            // the file.
            sb.append("        <DistanceMeters>").append(decimals(lapMetres, 2))
                .append("</DistanceMeters>\n")
            if (hasDistance) {
                sb.append("        <MaximumSpeed>").append(decimals(lapMaxSpeed, 3))
                    .append("</MaximumSpeed>\n")
            }
            sb.append("        <Calories>").append(kj.coerceAtLeast(0)).append("</Calories>\n")
            if (avgHr > 0) {
                sb.append("        <AverageHeartRateBpm><Value>").append(avgHr).append("</Value></AverageHeartRateBpm>\n")
                sb.append("        <MaximumHeartRateBpm><Value>").append(maxHr).append("</Value></MaximumHeartRateBpm>\n")
            }
            sb.append("        <Intensity>Active</Intensity>\n")
            sb.append("        <Cadence>").append(avgCad).append("</Cadence>\n")
            sb.append("        <TriggerMethod>Manual</TriggerMethod>\n")
            // Track_t requires at least one Trackpoint, so a lap with none (a
            // planned segment an older ride passed through without a sample)
            // leaves the Track out rather than writing an empty one.
            if (lapPts.isNotEmpty()) {
                sb.append("        <Track>\n")
                for (p in lapPts) {
                    sb.append("          <Trackpoint>\n")
                    sb.append("            <Time>").append(isoUtc(startMs + p.wallSec * 1000L)).append("</Time>\n")
                    // Trackpoint_t orders Position and AltitudeMeters between
                    // Time and DistanceMeters. Written only where a companion
                    // game reported them: a trackpoint without a Position is
                    // valid TCX and a reader leaves a gap, never a guess.
                    if (p.latitude != null && p.longitude != null) {
                        sb.append("            <Position><LatitudeDegrees>").append(signedDecimals(p.latitude, 7))
                            .append("</LatitudeDegrees><LongitudeDegrees>").append(signedDecimals(p.longitude, 7))
                            .append("</LongitudeDegrees></Position>\n")
                    }
                    p.altitudeMeters?.let {
                        sb.append("            <AltitudeMeters>").append(signedDecimals(it, 1)).append("</AltitudeMeters>\n")
                    }
                    // Trackpoint_t orders DistanceMeters ahead of HeartRateBpm.
                    sb.append("            <DistanceMeters>").append(decimals(p.metres, 2))
                        .append("</DistanceMeters>\n")
                    if (p.hr > 0) {
                        sb.append("            <HeartRateBpm><Value>").append(p.hr).append("</Value></HeartRateBpm>\n")
                    }
                    sb.append("            <Cadence>").append(p.cad).append("</Cadence>\n")
                    // TPX orders Speed ahead of Watts.
                    sb.append("            <Extensions><ns3:TPX>")
                    if (hasDistance) {
                        sb.append("<ns3:Speed>").append(decimals(p.speedMps, 3)).append("</ns3:Speed>")
                    }
                    sb.append("<ns3:Watts>").append(p.power)
                        .append("</ns3:Watts></ns3:TPX></Extensions>\n")
                    sb.append("          </Trackpoint>\n")
                }
                sb.append("        </Track>\n")
            }
            // LX orders AvgSpeed ahead of AvgWatts.
            sb.append("        <Extensions><ns3:LX>")
            if (hasDistance) {
                sb.append("<ns3:AvgSpeed>").append(decimals(lapMetres / lapDur, 3))
                    .append("</ns3:AvgSpeed>")
            }
            sb.append("<ns3:AvgWatts>").append(avgP)
                .append("</ns3:AvgWatts><ns3:MaxWatts>").append(maxP)
                .append("</ns3:MaxWatts></ns3:LX></Extensions>\n")
            sb.append("      </Lap>\n")
        }

        sb.append("      <Creator xsi:type=\"Device_t\">\n")
        sb.append("        <Name>Indoor Bike</Name>\n")
        sb.append("        <UnitId>0</UnitId>\n")
        sb.append("        <ProductID>1</ProductID>\n")
        sb.append("        <Version><VersionMajor>1</VersionMajor><VersionMinor>0</VersionMinor>")
            .append("<BuildMajor>0</BuildMajor><BuildMinor>0</BuildMinor></Version>\n")
        sb.append("      </Creator>\n")
        sb.append("    </Activity>\n")
        sb.append("  </Activities>\n")
        sb.append("  <Author xsi:type=\"Application_t\">\n")
        sb.append("    <Name>Indoor Bike</Name>\n")
        sb.append("    <Build><Version><VersionMajor>1</VersionMajor><VersionMinor>0</VersionMinor></Version></Build>\n")
        sb.append("    <LangID>en</LangID>\n")
        sb.append("    <PartNumber>000-00000-00</PartNumber>\n")
        sb.append("  </Author>\n")
        sb.append("</TrainingCenterDatabase>\n")
        return sb.toString()
    }

    /**
     * Format epoch-millis as ISO-8601 UTC ("2026-06-15T10:00:00Z"), seconds
     * precision. Pure integer arithmetic (Howard Hinnant's civil-from-days)
     * so it works in commonMain without a date library.
     */
    /**
     * [value] to [places] decimal places. commonMain has no `String.format`,
     * and TCX wants plain decimals with a dot regardless of the device locale,
     * so the digits are assembled by hand.
     */
    /** [decimals] for values that can be negative: a western longitude, a shore below sea level. */
    private fun signedDecimals(value: Double, places: Int): String =
        if (value < 0.0) "-" + decimals(-value, places) else decimals(value, places)

    private fun decimals(value: Double, places: Int): String {
        var scale = 1L
        repeat(places) { scale *= 10L }
        val scaled = (value * scale + 0.5).toLong().coerceAtLeast(0L)
        val fraction = (scaled % scale).toString()
        return buildString {
            append(scaled / scale)
            append('.')
            repeat(places - fraction.length) { append('0') }
            append(fraction)
        }
    }

    internal fun isoUtc(epochMillis: Long): String {
        val totalSec = epochMillis.floorDiv(1000L)
        val days = totalSec.floorDiv(86400L)
        val secOfDay = totalSec - days * 86400L
        val hour = (secOfDay / 3600L).toInt()
        val minute = ((secOfDay % 3600L) / 60L).toInt()
        val second = (secOfDay % 60L).toInt()

        val z = days + 719468L
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097                                   // [0, 146096]
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365  // [0, 399]
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)            // [0, 365]
        val mp = (5 * doy + 2) / 153                                 // [0, 11]
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt()            // [1, 31]
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()       // [1, 12]
        val year = if (month <= 2) y + 1 else y

        return buildString(20) {
            append(pad(year.toInt(), 4)); append('-')
            append(pad(month, 2)); append('-')
            append(pad(day, 2)); append('T')
            append(pad(hour, 2)); append(':')
            append(pad(minute, 2)); append(':')
            append(pad(second, 2)); append('Z')
        }
    }

    private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')
}

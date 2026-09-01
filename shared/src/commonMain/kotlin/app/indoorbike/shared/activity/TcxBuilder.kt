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
package app.indoorbike.shared.activity

import app.indoorbike.shared.Workout
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.db.WorkoutTelemetry
import app.indoorbike.shared.physics.VirtualSpeed

/**
 * Encodes a completed indoor ride as a Garmin TCX (Training Center Database v2)
 * file. TCX is the natural XML sibling of our FIT export: it carries time,
 * heart rate, cadence, and power (via the ActivityExtension `Watts` element)
 * with no GPS track required, so it suits indoor cycling and is accepted by
 * Strava, Garmin Connect, intervals.icu, TrainingPeaks, Golden Cheetah, etc.
 *
 * One `Lap` is emitted per workout segment (or a single whole-ride lap when
 * [workout] is null), mirroring [app.indoorbike.shared.fit.RideFitBuilder] so
 * the two exports describe the same structure. Output is a self-contained
 * UTF-8 XML string the caller can write to disk or hand to a share intent.
 *
 * Pure Kotlin in commonMain (no kotlinx-datetime dependency) - timestamps are
 * formatted to ISO-8601 UTC by [isoUtc].
 */
object TcxBuilder {

    private const val NS = "http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2"
    private const val NS_AX = "http://www.garmin.com/xmlschemas/ActivityExtension/v2"
    private const val NS_XSI = "http://www.w3.org/2001/XMLSchema-instance"

    private class Pt(val offsetSec: Int, val power: Int, val hr: Int, val cad: Int) {
        /** Virtual speed for this sample's watts, and the ride distance at it. */
        var speedMps: Double = 0.0
        var metres: Double = 0.0
    }

    fun build(
        session: WorkoutSession,
        telemetry: List<WorkoutTelemetry>,
        workout: Workout?,
        ftp: Int,
        /**
         * Rider body mass, for the virtual speed and distance a trainer has no
         * road to measure. 0 = not set, in which case the speed elements are
         * left out entirely and distance stays at zero, as it was before there
         * was an estimate to make.
         */
        riderKg: Float = 0f,
    ): String {
        val hasRiderMass = riderKg > 0f
        val startMs = session.startTimeEpoch
        val totalSeconds = session.durationSeconds.toInt().coerceAtLeast(1)

        // Normalise telemetry to one sample per integer-second offset, clamped
        // into the ride window - same posture as the FIT writer so both exports
        // see identical data.
        val seen = HashSet<Int>()
        val pts = ArrayList<Pt>(telemetry.size)
        for (s in telemetry) {
            val off = ((s.timestampEpoch - startMs) / 1000L).toInt()
            if (off < 0 || off >= totalSeconds) continue
            if (!seen.add(off)) continue
            pts.add(
                Pt(
                    offsetSec = off,
                    power = s.power.toInt().coerceAtLeast(0),
                    hr = s.heartRate.toInt().coerceIn(0, 254),
                    cad = s.cadence.toInt().coerceIn(0, 254),
                )
            )
        }
        pts.sortBy { it.offsetSec }

        // Distance is speed integrated in ride order, so it has to wait for the
        // sort. Each sample stands for one second of riding.
        var metres = 0.0
        for (p in pts) {
            p.speedMps = VirtualSpeed.speedMps(p.power.toDouble(), riderKg)
            metres += p.speedMps
            p.metres = metres
        }

        // Lap partition: one per segment, or a single whole-ride lap.
        val segSec = workout?.segments?.map { it.lengthInSeconds.coerceAtLeast(1) }
            ?: listOf(totalSeconds)
        val ends = ArrayList<Int>(segSec.size)
        var acc = 0
        for (d in segSec) { acc += d; ends.add(acc) }

        val laps = Array(segSec.size) { ArrayList<Pt>() }
        for (p in pts) {
            var li = 0
            while (li < ends.size - 1 && p.offsetSec >= ends[li]) li++
            laps[li].add(p)
        }

        val sb = StringBuilder(pts.size * 96 + 1024)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<TrainingCenterDatabase xmlns=\"").append(NS)
            .append("\" xmlns:ns3=\"").append(NS_AX)
            .append("\" xmlns:xsi=\"").append(NS_XSI).append("\">\n")
        sb.append("  <Activities>\n")
        sb.append("    <Activity Sport=\"Biking\">\n")
        sb.append("      <Id>").append(isoUtc(startMs)).append("</Id>\n")

        var lapStartOffset = 0
        for (li in segSec.indices) {
            val lapPts = laps[li]
            val lapEnd = if (li == segSec.size - 1) totalSeconds else ends[li]
            val lapDur = (lapEnd - lapStartOffset).coerceAtLeast(1)

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

            sb.append("      <Lap StartTime=\"").append(isoUtc(startMs + lapStartOffset * 1000L)).append("\">\n")
            sb.append("        <TotalTimeSeconds>").append(lapDur).append("</TotalTimeSeconds>\n")
            // Schema order is fixed: DistanceMeters, then MaximumSpeed,
            // then Calories. A reader that meets them out of order rejects
            // the file.
            sb.append("        <DistanceMeters>").append(decimals(lapMetres, 2))
                .append("</DistanceMeters>\n")
            if (hasRiderMass) {
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
            sb.append("        <Track>\n")
            for (p in lapPts) {
                sb.append("          <Trackpoint>\n")
                sb.append("            <Time>").append(isoUtc(startMs + p.offsetSec * 1000L)).append("</Time>\n")
                // Trackpoint_t orders DistanceMeters ahead of HeartRateBpm.
                sb.append("            <DistanceMeters>").append(decimals(p.metres, 2))
                    .append("</DistanceMeters>\n")
                if (p.hr > 0) {
                    sb.append("            <HeartRateBpm><Value>").append(p.hr).append("</Value></HeartRateBpm>\n")
                }
                sb.append("            <Cadence>").append(p.cad).append("</Cadence>\n")
                // TPX orders Speed ahead of Watts.
                sb.append("            <Extensions><ns3:TPX>")
                if (hasRiderMass) {
                    sb.append("<ns3:Speed>").append(decimals(p.speedMps, 3)).append("</ns3:Speed>")
                }
                sb.append("<ns3:Watts>").append(p.power)
                    .append("</ns3:Watts></ns3:TPX></Extensions>\n")
                sb.append("          </Trackpoint>\n")
            }
            sb.append("        </Track>\n")
            // LX orders AvgSpeed ahead of AvgWatts.
            sb.append("        <Extensions><ns3:LX>")
            if (hasRiderMass) {
                sb.append("<ns3:AvgSpeed>").append(decimals(lapMetres / lapDur, 3))
                    .append("</ns3:AvgSpeed>")
            }
            sb.append("<ns3:AvgWatts>").append(avgP)
                .append("</ns3:AvgWatts><ns3:MaxWatts>").append(maxP)
                .append("</ns3:MaxWatts></ns3:LX></Extensions>\n")
            sb.append("      </Lap>\n")

            lapStartOffset = ends[li]
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

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
package app.indoorbike.shared.fit

import app.indoorbike.shared.PowerZone
import app.indoorbike.shared.Workout
import app.indoorbike.shared.activity.ExportRide
import app.indoorbike.shared.activity.RideTimeline
import app.indoorbike.shared.activity.averageHeartRate
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.db.WorkoutTelemetry
import app.indoorbike.shared.progression.computeRideStats
import app.indoorbike.shared.workout.CadenceTarget
import kotlin.math.max
import kotlin.math.min

/**
 * Encodes a completed indoor cycling ride as a FIT activity file.
 *
 * Output layout:
 *   - file_id            (file type = activity, manufacturer = development)
 *   - file_creator       (Indoor Bike app version)
 *   - developer_data_id  (namespace for the custom target fields)
 *   - field_description × 4  (names/types for those fields)
 *   - event              (timer start)
 *   - record × N         (one per 1Hz telemetry sample)
 *   - event × 2P         (timer stop and start around each of P pauses)
 *   - lap × M            (one per segment the ride actually rode, cut where the
 *                         ride recorded it changing, partitioning the records
 *                         and carrying the segment's power and cadence targets
 *                         as developer fields; see [ExportRide])
 *   - event              (timer stop)
 *   - session            (summary stats + sport = cycling, sub_sport = indoor)
 *   - activity           (single-session wrapper)
 *
 * Records sit at the moment they were taken and laps are cut on ride time, so
 * a paused ride keeps every sample and reports its elapsed time (pauses
 * included) apart from its timer time (riding only). See [RideTimeline].
 *
 * If [workout] is null we fall back to a single lap covering the whole ride.
 *
 * The output is a self-contained byte array — caller can write it to disk,
 * upload it, or stuff it into a share intent.
 */
object RideFitBuilder {

    // Local-message-type slot assignments — fixed for the lifetime of the
    // file. Each definition message claims one of these, then every data
    // message of that type references it by slot number.
    private const val LOCAL_FILE_ID      = 0
    private const val LOCAL_FILE_CREATOR = 1
    private const val LOCAL_EVENT        = 2
    private const val LOCAL_RECORD       = 3
    private const val LOCAL_LAP          = 4
    private const val LOCAL_SESSION      = 5
    private const val LOCAL_ACTIVITY     = 6
    private const val LOCAL_DEVELOPER_ID = 7
    private const val LOCAL_FIELD_DESC   = 8

    // ── Developer (custom) fields carried on each lap ────────────────────
    // A lap is one workout segment, so these say what the segment *asked* for,
    // alongside the standard avg/max fields that say what the rider did. The
    // activity profile has no native place for a target, and dropping them
    // meant a structured session exported as an anonymous power trace.
    private const val DEV_DATA_INDEX = 0
    private const val DEV_FIELD_TARGET_POWER    = 0
    private const val DEV_FIELD_TARGET_POWER_PCT = 1
    private const val DEV_FIELD_TARGET_CAD_LOW  = 2
    private const val DEV_FIELD_TARGET_CAD_HIGH = 3

    /**
     * Width of every field_description string, null-padded. Must stay clear of
     * the longest name we emit ("target_cadence_high", 19) with room for the
     * terminator, since [FitWriter.ascii] truncates silently.
     */
    private const val DEV_STRING_LEN = 24

    /**
     * Namespace for our developer fields. Any fixed 16 bytes work as long as
     * they stay fixed: a reader keys custom fields on this plus the field
     * number, so changing it would orphan every file already exported.
     */
    private val APPLICATION_ID = byteArrayOf(
        0x69, 0x6E, 0x64, 0x6F, 0x6F, 0x72, 0x62, 0x69,   // "indoorbi"
        0x6B, 0x65, 0x2E, 0x61, 0x70, 0x70, 0x00, 0x01,   // "ke.app" + version tag
    )

    // Identity. We're not Garmin and not Wahoo — manufacturer 255 is the
    // FIT-spec "development" slot, intended for non-commercial / hobby apps.
    private const val MANUFACTURER_DEVELOPMENT = 255
    private const val PRODUCT_INDOORBIKE = 1
    private const val SOFTWARE_VERSION = 121   // 1.21 - bumps with shipped FIT changes

    // sport / sub_sport enum values from the FIT profile.
    private const val SPORT_CYCLING = 2
    private const val SUB_SPORT_INDOOR_CYCLING = 6

    fun build(
        session: WorkoutSession,
        telemetry: List<WorkoutTelemetry>,
        workout: Workout?,
        /** Today's FTP, used only when history did not record the ride's own. */
        ftp: Int,
        /**
         * Rider body mass, for the virtual speed and distance an indoor ride
         * has no wheel-on-road to measure. 0 = not set, in which case every
         * speed and distance field is written as the spec's invalid sentinel
         * so a reader shows nothing rather than a confident zero - unless the
         * ride was on a road, whose own distance is used either way.
         */
        riderKg: Float = 0f,
    ): ByteArray {
        val ride = ExportRide(session, telemetry, workout, ftp, riderKg)
        val rideFtp = ride.ftp
        val hasDistance = ride.hasDistance
        val w = FitWriter()
        w.writePlaceholderHeader()

        val startUnixMs = session.startTimeEpoch
        val startFit = unixMillisToFit(startUnixMs)
        val timeline = ride.timeline
        val endFit = startFit + timeline.endWallSeconds

        // ── file_id ──────────────────────────────────────────────────────
        w.writeDefinition(LOCAL_FILE_ID, FitMessage.FILE_ID, listOf(
            Triple(0, 1, FitBaseType.ENUM),     // type = activity (4)
            Triple(1, 2, FitBaseType.UINT16),   // manufacturer
            Triple(2, 2, FitBaseType.UINT16),   // product
            Triple(3, 4, FitBaseType.UINT32Z),  // serial number
            Triple(4, 4, FitBaseType.UINT32),   // time_created
        ))
        w.writeDataHeader(LOCAL_FILE_ID)
        w.u8(4)                                 // file type = activity
        w.u16(MANUFACTURER_DEVELOPMENT)
        w.u16(PRODUCT_INDOORBIKE)
        w.u32(session.id.toULong().toLong())    // serial — use session id so files are unique
        w.u32(startFit)

        // ── file_creator ─────────────────────────────────────────────────
        w.writeDefinition(LOCAL_FILE_CREATOR, FitMessage.FILE_CREATOR, listOf(
            Triple(0, 2, FitBaseType.UINT16),   // software_version
            Triple(1, 1, FitBaseType.UINT8),    // hardware_version
        ))
        w.writeDataHeader(LOCAL_FILE_CREATOR)
        w.u16(SOFTWARE_VERSION)
        w.u8(0)

        // ── developer field declarations ─────────────────────────────────
        // Must precede the lap definition that references them: a reader
        // hitting an undeclared developer field has no type or name for it.
        writeDeveloperFieldDeclarations(w)

        // ── event: timer START ───────────────────────────────────────────
        w.writeDefinition(LOCAL_EVENT, FitMessage.EVENT, listOf(
            Triple(253, 4, FitBaseType.UINT32), // timestamp
            Triple(0, 1, FitBaseType.ENUM),     // event = timer (0)
            Triple(1, 1, FitBaseType.ENUM),     // event_type (start=0, stop_all=4)
        ))
        w.writeDataHeader(LOCAL_EVENT)
        w.u32(startFit); w.u8(0); w.u8(0)       // timer / start

        // ── record definition (used by all telemetry samples) ───────────
        w.writeDefinition(LOCAL_RECORD, FitMessage.RECORD, listOf(
            Triple(253, 4, FitBaseType.UINT32),  // timestamp
            Triple(5, 4, FitBaseType.UINT32),    // distance (1/100 m)
            Triple(6, 2, FitBaseType.UINT16),    // speed (1/1000 m/s)
            Triple(7, 2, FitBaseType.UINT16),    // power (W)
            Triple(3, 1, FitBaseType.UINT8),     // heart_rate (bpm)
            Triple(4, 1, FitBaseType.UINT8),     // cadence (rpm)
        ))

        // ── records + interleaved laps ───────────────────────────────────
        var lapDefWritten = false
        var lapsWritten = 0
        fun emitLap(timerSec: Int, lapStartFit: Long, lapEndFit: Long, accum: LapAccum, segment: PowerZone?) {
            if (!lapDefWritten) {
                w.writeDefinition(
                    localType = LOCAL_LAP,
                    globalMessageNum = FitMessage.LAP,
                    fields = listOf(
                        Triple(253, 4, FitBaseType.UINT32), // timestamp (= lap end)
                        Triple(2, 4, FitBaseType.UINT32),   // start_time
                        Triple(7, 4, FitBaseType.UINT32),   // total_elapsed_time (1/1000 s)
                        Triple(8, 4, FitBaseType.UINT32),   // total_timer_time (1/1000 s)
                        Triple(9, 4, FitBaseType.UINT32),   // total_distance (1/100 m)
                        Triple(13, 2, FitBaseType.UINT16),  // avg_speed (1/1000 m/s)
                        Triple(14, 2, FitBaseType.UINT16),  // max_speed (1/1000 m/s)
                        Triple(19, 2, FitBaseType.UINT16),  // avg_power
                        Triple(20, 2, FitBaseType.UINT16),  // max_power
                        Triple(15, 1, FitBaseType.UINT8),   // avg_heart_rate
                        Triple(16, 1, FitBaseType.UINT8),   // max_heart_rate
                        Triple(17, 1, FitBaseType.UINT8),   // avg_cadence
                        Triple(18, 1, FitBaseType.UINT8),   // max_cadence
                        Triple(0, 1, FitBaseType.ENUM),     // event
                        Triple(1, 1, FitBaseType.ENUM),     // event_type
                        Triple(254, 2, FitBaseType.UINT16), // message_index
                    ),
                    developerFields = listOf(
                        Triple(DEV_FIELD_TARGET_POWER, 2, DEV_DATA_INDEX),
                        Triple(DEV_FIELD_TARGET_POWER_PCT, 2, DEV_DATA_INDEX),
                        Triple(DEV_FIELD_TARGET_CAD_LOW, 1, DEV_DATA_INDEX),
                        Triple(DEV_FIELD_TARGET_CAD_HIGH, 1, DEV_DATA_INDEX),
                    ),
                )
                lapDefWritten = true
            }
            val safeTimer = timerSec.coerceAtLeast(1)
            // A pause inside the lap is elapsed time but not timer time, so
            // the wall clock can only ever be the longer of the two.
            val safeElapsed = (lapEndFit - lapStartFit).toInt().coerceAtLeast(safeTimer)
            w.writeDataHeader(LOCAL_LAP)
            w.u32(lapStartFit + safeElapsed)
            w.u32(lapStartFit)
            w.u32((safeElapsed * 1000L))
            w.u32((safeTimer * 1000L))
            w.u32(fitDistance(accum.metres.takeIf { hasDistance }))
            w.u16(fitSpeed(accum.avgSpeedMps(safeTimer).takeIf { hasDistance }))
            w.u16(fitSpeed(accum.maxSpeedMps.takeIf { hasDistance }))
            w.u16(accum.avgPower().coerceIn(0, 65534))
            w.u16(accum.maxPower.coerceIn(0, 65534))
            w.u8(fitHeartRate(accum.avgHr()))
            w.u8(fitHeartRate(accum.maxHr))
            w.u8(accum.avgCad().coerceIn(0, 254))
            w.u8(accum.maxCad.coerceIn(0, 254))
            w.u8(9)                              // event = lap
            w.u8(1)                              // event_type = stop
            w.u16(lapsWritten)

            // Developer fields, in the order declared above. A ride with no
            // workout behind it (or a segment with no cadence ask) writes the
            // spec's invalid sentinel so readers show nothing rather than zero.
            val pct = segment?.powerPercentFTP
            w.u16(if (pct == null) FitInvalid.UINT16 else (pct * rideFtp / 100).coerceIn(0, 65534))
            w.u16(pct?.coerceIn(0, 65534) ?: FitInvalid.UINT16)
            val cadence = CadenceTarget.of(segment?.cadenceMinRpm, segment?.cadenceMaxRpm)
            w.u8(cadence?.minRpm?.coerceIn(0, 254) ?: FitInvalid.UINT8)
            w.u8(cadence?.maxRpm?.coerceIn(0, 254) ?: FitInvalid.UINT8)
            lapsWritten += 1
        }

        // Running totals for the ride as a whole, accumulated as records are
        // written so the session's distance is exactly the sum of what the
        // records claim rather than a second, separately-rounded estimate.
        var rideMetres = 0.0
        var rideMaxSpeedMps = 0.0

        // One record per integer-second sample: the timeline rounds each
        // sample's timestamp to whole seconds and de-dupes. Laps are ride
        // time, so they are placed on the ride clock while records keep the
        // wall clock they were taken on.
        var phase = 0
        for ((lapNo, lap) in ride.laps.withIndex()) {
            val accum = LapAccum()
            for (pi in lap.firstPoint until lap.endPoint) {
                val point = timeline.points[pi]
                val sample = telemetry[point.index]

                // The ride was paused between the last sample and this one.
                // Said the way a head unit says it, so a reader can tell a
                // rider who stopped from a trainer that dropped out. Written
                // after any lap closed before it: a lap ending at the pause
                // ends before it.
                if (point.phase != phase) {
                    w.writeDataHeader(LOCAL_EVENT)
                    w.u32(startFit + timeline.phases[phase].lastWallSeconds); w.u8(0); w.u8(4)  // timer / stop_all
                    w.writeDataHeader(LOCAL_EVENT)
                    w.u32(startFit + point.wallSeconds); w.u8(0); w.u8(0)                      // timer / start
                    phase = point.phase
                }

                // A trainer measures no ground speed. On a road the metres are
                // that road's, shared out over the ride; otherwise they are
                // what the sample's watts would carry the rider on the flat.
                // Either way one sample is one second, so metres are m/s.
                val speedMps = ride.metres[pi]
                rideMetres += speedMps
                if (speedMps > rideMaxSpeedMps) rideMaxSpeedMps = speedMps

                w.writeDataHeader(LOCAL_RECORD)
                w.u32(startFit + point.wallSeconds)
                w.u32(fitDistance(rideMetres.takeIf { hasDistance }))
                w.u16(fitSpeed(speedMps.takeIf { hasDistance }))
                w.u16(sample.power.toInt().coerceIn(0, 65534))
                w.u8(fitHeartRate(sample.heartRate.toInt()))
                w.u8(sample.cadence.toInt().coerceIn(0, 254))

                accum.add(
                    sample.power.toInt(),
                    sample.heartRate.toInt(),
                    sample.cadence.toInt(),
                    speedMps,
                )
            }
            val lastLap = lapNo == ride.laps.lastIndex
            emitLap(
                timerSec = lap.timerSeconds,
                lapStartFit = startFit + timeline.wallAt(lap.startRideSeconds),
                lapEndFit = if (lastLap) endFit else startFit + timeline.wallAt(lap.endRideSeconds),
                accum = accum,
                segment = lap.segment,
            )
        }

        // ── event: timer STOP ────────────────────────────────────────────
        w.writeDataHeader(LOCAL_EVENT)
        w.u32(endFit); w.u8(0); w.u8(4)         // timer / stop_all

        // ── session ──────────────────────────────────────────────────────
        val rideStats = computeRideStats(
            telemetryWatts = telemetry.map { it.power },
            durationSeconds = session.durationSeconds,
            ftp = rideFtp,
        )
        // From the samples rather than the stored average: rides saved before
        // the fix counted every reading of 0 bpm into it.
        val heartRates = telemetry.map { it.heartRate }

        w.writeDefinition(LOCAL_SESSION, FitMessage.SESSION, listOf(
            Triple(253, 4, FitBaseType.UINT32), // timestamp
            Triple(2, 4, FitBaseType.UINT32),   // start_time
            Triple(7, 4, FitBaseType.UINT32),   // total_elapsed_time (1/1000 s)
            Triple(8, 4, FitBaseType.UINT32),   // total_timer_time (1/1000 s)
            Triple(9, 4, FitBaseType.UINT32),   // total_distance (1/100 m)
            Triple(14, 2, FitBaseType.UINT16),  // avg_speed (1/1000 m/s)
            Triple(15, 2, FitBaseType.UINT16),  // max_speed (1/1000 m/s)
            Triple(20, 2, FitBaseType.UINT16),  // avg_power
            Triple(21, 2, FitBaseType.UINT16),  // max_power
            Triple(16, 1, FitBaseType.UINT8),   // avg_heart_rate
            Triple(17, 1, FitBaseType.UINT8),   // max_heart_rate
            Triple(18, 1, FitBaseType.UINT8),   // avg_cadence
            Triple(19, 1, FitBaseType.UINT8),   // max_cadence
            Triple(11, 2, FitBaseType.UINT16),  // total_calories
            Triple(34, 2, FitBaseType.UINT16),  // normalized_power
            Triple(35, 2, FitBaseType.UINT16),  // training_stress_score (0.1)
            Triple(36, 2, FitBaseType.UINT16),  // intensity_factor (0.001)
            Triple(45, 2, FitBaseType.UINT16),  // threshold_power (FTP)
            Triple(5, 1, FitBaseType.ENUM),     // sport
            Triple(6, 1, FitBaseType.ENUM),     // sub_sport
            Triple(0, 1, FitBaseType.ENUM),     // event
            Triple(1, 1, FitBaseType.ENUM),     // event_type
            Triple(26, 2, FitBaseType.UINT16),  // num_laps
        ))
        w.writeDataHeader(LOCAL_SESSION)
        w.u32(endFit)
        w.u32(startFit)
        w.u32(timeline.endWallSeconds * 1000L)
        w.u32((session.durationSeconds * 1000L).toULong().toLong())
        w.u32(fitDistance(rideMetres.takeIf { hasDistance }))
        w.u16(fitSpeed(
            (rideMetres / session.durationSeconds.coerceAtLeast(1)).takeIf { hasDistance }
        ))
        w.u16(fitSpeed(rideMaxSpeedMps.takeIf { hasDistance }))
        w.u16(session.avgPower.toInt().coerceIn(0, 65534))
        w.u16(telemetry.maxOfOrNull { it.power.toInt() }?.coerceIn(0, 65534) ?: 0)
        w.u8(fitHeartRate(averageHeartRate(heartRates).toInt()))
        w.u8(fitHeartRate(heartRates.maxOrNull()?.toInt() ?: 0))
        w.u8(session.avgCadence.toInt().coerceIn(0, 254))
        w.u8(telemetry.maxOfOrNull { it.cadence.toInt() }?.coerceIn(0, 254) ?: 0)
        w.u16(rideStats.kilojoules.coerceIn(0, 65534))      // kJ ≈ kcal for cycling
        w.u16(rideStats.normalizedPowerWatts.coerceIn(0, 65534))
        w.u16((rideStats.tss * 10).coerceIn(0, 65534))      // TSS at 0.1 res
        w.u16((rideStats.intensityFactor * 1000).toInt().coerceIn(0, 65534))
        w.u16(rideFtp.coerceIn(0, 65534))
        w.u8(SPORT_CYCLING)
        w.u8(SUB_SPORT_INDOOR_CYCLING)
        w.u8(8)                                 // event = session
        w.u8(1)                                 // event_type = stop
        // The laps actually written. A ride ended before its last segments
        // has fewer laps than the workout has segments.
        w.u16(lapsWritten)

        // ── activity ─────────────────────────────────────────────────────
        w.writeDefinition(LOCAL_ACTIVITY, FitMessage.ACTIVITY, listOf(
            Triple(253, 4, FitBaseType.UINT32), // timestamp
            Triple(0, 4, FitBaseType.UINT32),   // total_timer_time (1/1000 s)
            Triple(1, 2, FitBaseType.UINT16),   // num_sessions
            Triple(2, 1, FitBaseType.ENUM),     // type (manual=0, auto-multi-sport=1, etc.)
            Triple(3, 1, FitBaseType.ENUM),     // event
            Triple(4, 1, FitBaseType.ENUM),     // event_type
        ))
        w.writeDataHeader(LOCAL_ACTIVITY)
        w.u32(endFit)
        w.u32((session.durationSeconds * 1000L).toULong().toLong())
        w.u16(1)
        w.u8(0)                                 // type = manual
        w.u8(26)                                // event = activity
        w.u8(1)                                 // event_type = stop

        w.finalizeAndCrc()
        return w.bytes()
    }

    /**
     * Declares the developer-data namespace and names each custom field, so a
     * reader can label them instead of showing four anonymous numbers.
     *
     * `native_mesg_num` points at `lap`, which tells a reader these belong on
     * lap messages even though they have no native field number there.
     */
    private fun writeDeveloperFieldDeclarations(w: FitWriter) {
        w.writeDefinition(LOCAL_DEVELOPER_ID, FitMessage.DEVELOPER_DATA_ID, listOf(
            Triple(1, APPLICATION_ID.size, FitBaseType.BYTE),  // application_id
            Triple(3, 1, FitBaseType.UINT8),                   // developer_data_index
        ))
        w.writeDataHeader(LOCAL_DEVELOPER_ID)
        for (b in APPLICATION_ID) w.u8(b.toInt())
        w.u8(DEV_DATA_INDEX)

        w.writeDefinition(LOCAL_FIELD_DESC, FitMessage.FIELD_DESCRIPTION, listOf(
            Triple(0, 1, FitBaseType.UINT8),                 // developer_data_index
            Triple(1, 1, FitBaseType.UINT8),                 // field_definition_number
            Triple(2, 1, FitBaseType.UINT8),                 // fit_base_type_id
            Triple(3, DEV_STRING_LEN, FitBaseType.STRING),   // field_name
            Triple(8, DEV_STRING_LEN, FitBaseType.STRING),   // units
            Triple(14, 2, FitBaseType.UINT16),               // native_mesg_num
        ))
        fun describe(fieldNum: Int, baseType: Int, name: String, units: String) {
            w.writeDataHeader(LOCAL_FIELD_DESC)
            w.u8(DEV_DATA_INDEX)
            w.u8(fieldNum)
            w.u8(baseType)
            w.ascii(name, DEV_STRING_LEN)
            w.ascii(units, DEV_STRING_LEN)
            w.u16(FitMessage.LAP)
        }
        describe(DEV_FIELD_TARGET_POWER, FitBaseType.UINT16, "target_power", "watts")
        describe(DEV_FIELD_TARGET_POWER_PCT, FitBaseType.UINT16, "target_power_pct", "%FTP")
        describe(DEV_FIELD_TARGET_CAD_LOW, FitBaseType.UINT8, "target_cadence_low", "rpm")
        describe(DEV_FIELD_TARGET_CAD_HIGH, FitBaseType.UINT8, "target_cadence_high", "rpm")
    }

    private class LapAccum {
        var sumP = 0L; var sumHr = 0L; var sumCad = 0L
        var maxPower = 0; var maxHr = 0; var maxCad = 0
        var n = 0
        /** Samples with a heart rate: see [averageHeartRate]. */
        var nHr = 0
        /** Metres covered in this lap: each sample's share of the distance, held for its second. */
        var metres = 0.0
        var maxSpeedMps = 0.0
        fun add(p: Int, hr: Int, cad: Int, speedMps: Double) {
            sumP += p; sumCad += cad; n += 1
            if (hr > 0) { sumHr += hr; nHr += 1 }
            if (p > maxPower) maxPower = p
            if (hr > maxHr) maxHr = hr
            if (cad > maxCad) maxCad = cad
            metres += speedMps
            if (speedMps > maxSpeedMps) maxSpeedMps = speedMps
        }
        fun avgPower(): Int = if (n == 0) 0 else (sumP / n).toInt()
        fun avgHr(): Int = if (nHr == 0) 0 else (sumHr / nHr).toInt()
        fun avgCad(): Int = if (n == 0) 0 else (sumCad / n).toInt()
        /**
         * Over the lap's wall-clock length rather than its sample count, so a
         * lap missing a few samples reports the speed it actually averaged.
         */
        fun avgSpeedMps(elapsedSec: Int): Double =
            if (elapsedSec <= 0) 0.0 else metres / elapsedSec
    }

    /**
     * A heart rate in FIT's byte, or its "no data" value for 0. A ride with no
     * strap used to export a record of 0 bpm every second, which Garmin
     * Connect and others plot as a flatline at zero rather than as nothing.
     */
    private fun fitHeartRate(bpm: Int): Int =
        if (bpm <= 0) FitInvalid.UINT8 else bpm.coerceIn(1, 254)

    /** Metres in the FIT distance unit (1/100 m); null means "no estimate". */
    private fun fitDistance(metres: Double?): Long =
        if (metres == null) FitInvalid.UINT32
        else (metres * 100.0).toLong().coerceIn(0L, FitInvalid.UINT32 - 1L)

    /** m/s in the FIT speed unit (1/1000 m/s); null means "no estimate". */
    private fun fitSpeed(metresPerSecond: Double?): Int =
        if (metresPerSecond == null) FitInvalid.UINT16
        else (metresPerSecond * 1000.0).toInt().coerceIn(0, FitInvalid.UINT16 - 1)
}

package app.indoorbike.shared.fit

import app.indoorbike.shared.Workout
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.db.WorkoutTelemetry
import app.indoorbike.shared.progression.computeRideStats
import kotlin.math.max
import kotlin.math.min

/**
 * Encodes a completed indoor cycling ride as a FIT activity file.
 *
 * Output layout:
 *   - file_id            (file type = activity, manufacturer = development)
 *   - file_creator       (Indoor Bike app version)
 *   - event              (timer start)
 *   - record × N         (one per 1Hz telemetry sample)
 *   - lap × M            (one per workout segment, partitioning the records)
 *   - event              (timer stop)
 *   - session            (summary stats + sport = cycling, sub_sport = indoor)
 *   - activity           (single-session wrapper)
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

    // Identity. We're not Garmin and not Wahoo — manufacturer 255 is the
    // FIT-spec "development" slot, intended for non-commercial / hobby apps.
    private const val MANUFACTURER_DEVELOPMENT = 255
    private const val PRODUCT_INDOORBIKE = 1
    private const val SOFTWARE_VERSION = 100   // 1.00 — bumps with shipped FIT changes

    // sport / sub_sport enum values from the FIT profile.
    private const val SPORT_CYCLING = 2
    private const val SUB_SPORT_INDOOR_CYCLING = 6

    fun build(
        session: WorkoutSession,
        telemetry: List<WorkoutTelemetry>,
        workout: Workout?,
        ftp: Int,
    ): ByteArray {
        val w = FitWriter()
        w.writePlaceholderHeader()

        val startUnixMs = session.startTimeEpoch
        val startFit = unixMillisToFit(startUnixMs)
        val totalSeconds = session.durationSeconds.toInt().coerceAtLeast(1)
        val endFit = startFit + totalSeconds

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
            Triple(7, 2, FitBaseType.UINT16),    // power (W)
            Triple(3, 1, FitBaseType.UINT8),     // heart_rate (bpm)
            Triple(4, 1, FitBaseType.UINT8),     // cadence (rpm)
        ))

        // ── records + interleaved laps per segment ──────────────────────
        // Partition telemetry by workout segment (or whole-ride lap if none).
        val segmentsSec = workout?.segments?.map { it.lengthInSeconds.coerceAtLeast(1) }
            ?: listOf(totalSeconds)
        val segmentTargets = workout?.segments?.map { it.powerPercentFTP }
            ?: listOf<Int>()

        var segIdx = 0
        var segStartOffset = 0
        var segEndOffset = segmentsSec[0]
        var lapStartFit = startFit
        var lapStatsAccum = LapAccum()
        // Per-build flag so we only emit the lap-definition message once.
        // Captured by the local `emitLap` closure below so we don't need
        // mutable state on the object itself.
        var lapDefWritten = false
        fun emitLap(elapsedSec: Int, accum: LapAccum, plannedPct: Int?, lapIndex: Int) {
            if (!lapDefWritten) {
                w.writeDefinition(LOCAL_LAP, FitMessage.LAP, listOf(
                    Triple(253, 4, FitBaseType.UINT32), // timestamp (= lap end)
                    Triple(2, 4, FitBaseType.UINT32),   // start_time
                    Triple(7, 4, FitBaseType.UINT32),   // total_elapsed_time (1/1000 s)
                    Triple(8, 4, FitBaseType.UINT32),   // total_timer_time (1/1000 s)
                    Triple(19, 2, FitBaseType.UINT16),  // avg_power
                    Triple(20, 2, FitBaseType.UINT16),  // max_power
                    Triple(13, 1, FitBaseType.UINT8),   // avg_heart_rate
                    Triple(14, 1, FitBaseType.UINT8),   // max_heart_rate
                    Triple(17, 1, FitBaseType.UINT8),   // avg_cadence
                    Triple(18, 1, FitBaseType.UINT8),   // max_cadence
                    Triple(0, 1, FitBaseType.ENUM),     // event
                    Triple(1, 1, FitBaseType.ENUM),     // event_type
                    Triple(25, 2, FitBaseType.UINT16),  // message_index
                ))
                lapDefWritten = true
            }
            val safeElapsed = elapsedSec.coerceAtLeast(1)
            val endFit = lapStartFit + safeElapsed
            w.writeDataHeader(LOCAL_LAP)
            w.u32(endFit)
            w.u32(lapStartFit)
            w.u32((safeElapsed * 1000L))
            w.u32((safeElapsed * 1000L))
            w.u16(accum.avgPower().coerceIn(0, 65534))
            w.u16(accum.maxPower.coerceIn(0, 65534))
            w.u8(accum.avgHr().coerceIn(0, 254))
            w.u8(accum.maxHr.coerceIn(0, 254))
            w.u8(accum.avgCad().coerceIn(0, 254))
            w.u8(accum.maxCad.coerceIn(0, 254))
            w.u8(9)                              // event = lap
            w.u8(1)                              // event_type = stop
            w.u16(lapIndex)
        }

        // We deliberately enforce one record per integer-second sample. The
        // timestampEpoch on incoming telemetry can drift from a clean 1Hz, so
        // we round to whole-second offsets and de-dupe.
        val seenSecondOffsets = HashSet<Int>()
        for (sample in telemetry) {
            val offsetSec = ((sample.timestampEpoch - startUnixMs) / 1000L).toInt()
            if (offsetSec < 0 || offsetSec >= totalSeconds) continue
            if (!seenSecondOffsets.add(offsetSec)) continue

            // If this sample crosses into the next segment, close the current lap first.
            while (offsetSec >= segEndOffset && segIdx < segmentsSec.size - 1) {
                emitLap(
                    elapsedSec = segEndOffset - segStartOffset,
                    accum = lapStatsAccum,
                    plannedPct = segmentTargets.getOrNull(segIdx),
                    lapIndex = segIdx,
                )
                segIdx += 1
                segStartOffset = segEndOffset
                segEndOffset += segmentsSec[segIdx]
                lapStartFit = startFit + segStartOffset
                lapStatsAccum = LapAccum()
            }

            w.writeDataHeader(LOCAL_RECORD)
            w.u32(startFit + offsetSec)
            w.u16(sample.power.toInt().coerceIn(0, 65534))
            w.u8(sample.heartRate.toInt().coerceIn(0, 254))
            w.u8(sample.cadence.toInt().coerceIn(0, 254))

            lapStatsAccum.add(sample.power.toInt(), sample.heartRate.toInt(), sample.cadence.toInt())
        }

        // Final lap (close out the last segment / whole ride).
        emitLap(
            elapsedSec = totalSeconds - segStartOffset,
            accum = lapStatsAccum,
            plannedPct = segmentTargets.getOrNull(segIdx),
            lapIndex = segIdx,
        )

        // ── event: timer STOP ────────────────────────────────────────────
        w.writeDataHeader(LOCAL_EVENT)
        w.u32(endFit); w.u8(0); w.u8(4)         // timer / stop_all

        // ── session ──────────────────────────────────────────────────────
        val rideStats = computeRideStats(
            telemetryWatts = telemetry.map { it.power },
            durationSeconds = session.durationSeconds,
            ftp = ftp,
        )

        w.writeDefinition(LOCAL_SESSION, FitMessage.SESSION, listOf(
            Triple(253, 4, FitBaseType.UINT32), // timestamp
            Triple(2, 4, FitBaseType.UINT32),   // start_time
            Triple(7, 4, FitBaseType.UINT32),   // total_elapsed_time (1/1000 s)
            Triple(8, 4, FitBaseType.UINT32),   // total_timer_time (1/1000 s)
            Triple(20, 2, FitBaseType.UINT16),  // avg_power
            Triple(21, 2, FitBaseType.UINT16),  // max_power
            Triple(15, 1, FitBaseType.UINT8),   // avg_heart_rate
            Triple(16, 1, FitBaseType.UINT8),   // max_heart_rate
            Triple(18, 1, FitBaseType.UINT8),   // avg_cadence
            Triple(19, 1, FitBaseType.UINT8),   // max_cadence
            Triple(11, 2, FitBaseType.UINT16),  // total_calories
            Triple(34, 2, FitBaseType.UINT16),  // normalized_power
            Triple(35, 2, FitBaseType.UINT16),  // training_stress_score (0.1)
            Triple(36, 2, FitBaseType.UINT16),  // intensity_factor (0.001)
            Triple(48, 2, FitBaseType.UINT16),  // threshold_power (FTP)
            Triple(5, 1, FitBaseType.ENUM),     // sport
            Triple(6, 1, FitBaseType.ENUM),     // sub_sport
            Triple(0, 1, FitBaseType.ENUM),     // event
            Triple(1, 1, FitBaseType.ENUM),     // event_type
            Triple(26, 2, FitBaseType.UINT16),  // num_laps
        ))
        w.writeDataHeader(LOCAL_SESSION)
        w.u32(endFit)
        w.u32(startFit)
        w.u32((session.durationSeconds * 1000L).toULong().toLong())
        w.u32((session.durationSeconds * 1000L).toULong().toLong())
        w.u16(session.avgPower.toInt().coerceIn(0, 65534))
        w.u16(telemetry.maxOfOrNull { it.power.toInt() }?.coerceIn(0, 65534) ?: 0)
        w.u8(session.avgHeartRate.toInt().coerceIn(0, 254))
        w.u8(telemetry.maxOfOrNull { it.heartRate.toInt() }?.coerceIn(0, 254) ?: 0)
        w.u8(session.avgCadence.toInt().coerceIn(0, 254))
        w.u8(telemetry.maxOfOrNull { it.cadence.toInt() }?.coerceIn(0, 254) ?: 0)
        w.u16(rideStats.kilojoules.coerceIn(0, 65534))      // kJ ≈ kcal for cycling
        w.u16(rideStats.normalizedPowerWatts.coerceIn(0, 65534))
        w.u16((rideStats.tss * 10).coerceIn(0, 65534))      // TSS at 0.1 res
        w.u16((rideStats.intensityFactor * 1000).toInt().coerceIn(0, 65534))
        w.u16(ftp.coerceIn(0, 65534))
        w.u8(SPORT_CYCLING)
        w.u8(SUB_SPORT_INDOOR_CYCLING)
        w.u8(8)                                 // event = session
        w.u8(1)                                 // event_type = stop
        w.u16(segmentsSec.size)

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

    private class LapAccum {
        var sumP = 0L; var sumHr = 0L; var sumCad = 0L
        var maxPower = 0; var maxHr = 0; var maxCad = 0
        var n = 0
        fun add(p: Int, hr: Int, cad: Int) {
            sumP += p; sumHr += hr; sumCad += cad; n += 1
            if (p > maxPower) maxPower = p
            if (hr > maxHr) maxHr = hr
            if (cad > maxCad) maxCad = cad
        }
        fun avgPower(): Int = if (n == 0) 0 else (sumP / n).toInt()
        fun avgHr(): Int = if (n == 0) 0 else (sumHr / n).toInt()
        fun avgCad(): Int = if (n == 0) 0 else (sumCad / n).toInt()
    }
}

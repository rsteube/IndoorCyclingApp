package app.indoorbike.shared.workout

import app.indoorbike.shared.PowerZone
import app.indoorbike.shared.Workout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun seg(seconds: Int, ftpPct: Int, type: String = "Steady") =
    PowerZone(lengthInSeconds = seconds, powerPercentFTP = ftpPct, intervalType = type)

private fun workout(name: String, vararg segments: PowerZone) =
    Workout(id = name.lowercase(), name = name, description = null, segments = segments.toList())

class WorkoutSummaryTaggingTest {
    @Test
    fun steadyEnduranceTagsAsEndurance() {
        val w = workout("Endurance", seg(60 * 60, 70))
        assertEquals("Endurance", w.toSummary(ftpWatts = 200).tag)
    }

    @Test
    fun thresholdHeavyTagsThreshold() {
        val w = workout(
            "Threshold",
            seg(600, 60), seg(600, 95), seg(600, 60), seg(600, 95), seg(600, 60),
        )
        assertEquals("Threshold", w.toSummary().tag)
    }

    @Test
    fun vo2HeavyTagsVo2Max() {
        val w = workout(
            "VO2",
            seg(300, 50),
            seg(180, 115), seg(120, 50),
            seg(180, 115), seg(120, 50),
            seg(180, 115), seg(120, 50),
        )
        assertEquals("VO2 Max", w.toSummary().tag)
    }

    @Test
    fun anaerobicHeavyTagsAnaerobic() {
        val w = workout(
            "Anaerobic",
            seg(300, 50),
            seg(60, 140), seg(120, 50),
            seg(60, 140), seg(120, 50),
            seg(60, 140), seg(120, 50),
        )
        assertEquals("Anaerobic", w.toSummary().tag)
    }

    @Test
    fun tempoHeavyTagsTempo() {
        val w = workout("Tempo", seg(1800, 80))  // 80% sits in Z3
        assertEquals("Tempo", w.toSummary().tag)
    }

    @Test
    fun emptyWorkoutFallsBackToEndurance() {
        val w = Workout(id = "e", name = "E", description = null, segments = emptyList())
        assertEquals("Endurance", w.toSummary().tag)
    }
}

class WorkoutSummaryMathTest {
    @Test
    fun normalizedPowerEqualsIfTimesFtp() {
        val w = workout("Mixed", seg(1200, 100))
        val s = w.toSummary(ftpWatts = 200)
        assertEquals(200, s.normalizedPowerWatts)
    }

    @Test
    fun steadySegmentTssAtFtpForOneHourIsAboutOneHundred() {
        val w = workout("FTP", seg(3600, 100))
        val s = w.toSummary(ftpWatts = 200)
        assertTrue(s.tss in 99..101, "tss=${s.tss}")
        assertTrue(s.intensityFactor in 0.99f..1.01f, "if=${s.intensityFactor}")
    }

    @Test
    fun kilojoulesScaleWithFtp() {
        val w = workout("Steady", seg(3600, 100))
        val s100 = w.toSummary(ftpWatts = 100)
        val s200 = w.toSummary(ftpWatts = 200)
        // Doubling FTP doubles avg watts, so kJ should roughly double too.
        assertTrue(s200.kilojoules > s100.kilojoules, "kJ should scale with FTP")
        assertTrue(s200.kilojoules in (s100.kilojoules * 2 - 5)..(s100.kilojoules * 2 + 5))
    }

    @Test
    fun emptyWorkoutHasZeroDurationAndZeroTss() {
        val w = Workout(id = "e", name = "E", description = null, segments = emptyList())
        val s = w.toSummary(ftpWatts = 200)
        assertEquals(0, s.totalSeconds)
        assertEquals(0, s.tss)
        assertEquals(0, s.kilojoules)
    }

    @Test
    fun totalSecondsSumsAllSegments() {
        val w = workout("Sum", seg(60, 50), seg(120, 70), seg(30, 90))
        assertEquals(210, w.toSummary().totalSeconds)
    }

    @Test
    fun spikySegmentsRaiseIntensityFactorAboveAverage() {
        // RMS of FTP fraction is greater than the simple average when the workout
        // alternates between hard and easy segments at equal duration.
        val spiky = workout(
            "Spiky",
            seg(600, 60), seg(600, 120),
        )
        val s = spiky.toSummary()
        // Simple average FTP fraction is 90%; RMS is sqrt((0.36 + 1.44)/2) = 0.949.
        assertTrue(s.intensityFactor > 0.9f, "expected IF > 0.9, got ${s.intensityFactor}")
    }
}

class FormatClockTest {
    @Test
    fun usesMinutesSecondsBelowOneHour() {
        assertEquals("0:00", formatClock(0))
        assertEquals("0:30", formatClock(30))
        assertEquals("9:05", formatClock(9 * 60 + 5))
        assertEquals("59:59", formatClock(59 * 60 + 59))
    }

    @Test
    fun switchesToHmsAtOneHour() {
        assertEquals("1:00:00", formatClock(60 * 60))
        assertEquals("1:32:05", formatClock(60 * 60 + 32 * 60 + 5))
        assertEquals("10:00:00", formatClock(10 * 60 * 60))
    }

    @Test
    fun negativeSecondsAreClampedToZero() {
        assertEquals("0:00", formatClock(-100))
    }
}

class FormatDurationPrettyTest {
    @Test
    fun handlesEdges() {
        assertEquals("30 min", formatDurationPretty(30 * 60))
        assertEquals("1 h", formatDurationPretty(60 * 60))
        assertEquals("1 h 30 min", formatDurationPretty(90 * 60))
        assertEquals("2 h", formatDurationPretty(2 * 60 * 60))
    }

    @Test
    fun roundsToNearestMinute() {
        // 30s rounds up.
        assertEquals("1 min", formatDurationPretty(30))
        // 29s rounds down to 0 → "0 min" (note: pretty does not have "<1 min" sentinel).
        assertEquals("0 min", formatDurationPretty(29))
    }
}

package app.indoorbike.shared.workout

import app.indoorbike.shared.PowerZone
import app.indoorbike.shared.Workout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun seg(seconds: Int, ftpPct: Int, type: String = "Steady") =
    PowerZone(lengthInSeconds = seconds, powerPercentFTP = ftpPct, intervalType = type)

class WorkoutSummaryTest {
    @Test
    fun steadyEnduranceWorkoutTagsAsEndurance() {
        val w = Workout(
            id = "e",
            name = "Endurance",
            description = null,
            segments = listOf(seg(60 * 60, 70))
        )
        val s = w.toSummary(ftpWatts = 200)
        assertEquals("Endurance", s.tag)
    }

    @Test
    fun thresholdHeavyWorkoutTagsThreshold() {
        val w = Workout(
            id = "t",
            name = "Threshold",
            description = null,
            segments = listOf(
                seg(600, 60),
                seg(600, 95),
                seg(600, 60),
                seg(600, 95),
                seg(600, 60)
            )
        )
        val s = w.toSummary(ftpWatts = 200)
        assertEquals("Threshold", s.tag)
    }

    @Test
    fun normalizedPowerEqualsIfTimesFtp() {
        val w = Workout(
            id = "x",
            name = "Mixed",
            description = null,
            segments = listOf(seg(1200, 100))
        )
        val s = w.toSummary(ftpWatts = 200)
        assertEquals(200, s.normalizedPowerWatts)
    }

    @Test
    fun emptyWorkoutDoesNotCrash() {
        val w = Workout(
            id = "empty",
            name = "Empty",
            description = null,
            segments = emptyList()
        )
        val s = w.toSummary(ftpWatts = 200)
        assertEquals(0, s.totalSeconds)
        assertEquals(0, s.tss)
    }

    @Test
    fun formatClockSwitchesToHmsAtOneHour() {
        assertEquals("0:00", formatClock(0))
        assertEquals("0:30", formatClock(30))
        assertEquals("59:59", formatClock(59 * 60 + 59))
        assertEquals("1:00:00", formatClock(60 * 60))
        assertEquals("1:32:05", formatClock(60 * 60 + 32 * 60 + 5))
    }

    @Test
    fun formatDurationPrettyHandlesEdges() {
        assertEquals("30 min", formatDurationPretty(30 * 60))
        assertEquals("1 h", formatDurationPretty(60 * 60))
        assertEquals("1 h 30 min", formatDurationPretty(90 * 60))
    }
}

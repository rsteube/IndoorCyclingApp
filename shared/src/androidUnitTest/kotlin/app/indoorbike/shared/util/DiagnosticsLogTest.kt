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
package app.indoorbike.shared.util

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Covers the property the whole feature exists for: a line logged by one
 * process is readable by the next one. The in-memory buffer was never the
 * problem - surviving the process that wrote it was.
 */
class DiagnosticsLogTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("diagnostics-test").toFile()
        DiagnosticsLog.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        DiagnosticsLog.resetForTest()
        dir.deleteRecursively()
    }

    /** Stand in for a process death: everything in RAM goes, the files stay. */
    private fun simulateRelaunch() {
        DiagnosticsLog.resetForTest()
        DiagnosticsLog.attachStorage(dir)
    }

    @Test
    fun linesSurviveIntoTheNextLaunch() {
        DiagnosticsLog.attachStorage(dir)
        DiagnosticsLog.record('I', "Ride", "started a 75 minute session", null)
        DiagnosticsLog.record('W', "Trainer", "dropped mid-interval", null)
        DiagnosticsLog.flushToDisk()

        simulateRelaunch()

        // The report's "previous run" section, which used to be unavailable at
        // any price, now holds the run that died.
        val previous = DiagnosticsLog.previousRunLog()
        assertContains(previous, "started a 75 minute session")
        assertContains(previous, "dropped mid-interval")
        // ...and the fresh run starts clean rather than replaying it.
        assertEquals("", DiagnosticsLog.snapshot())
    }

    @Test
    fun onlyTheRunThatJustEndedIsOfferedAsPrevious() {
        DiagnosticsLog.attachStorage(dir)
        DiagnosticsLog.record('I', "Run", "first launch", null)
        DiagnosticsLog.flushToDisk()

        simulateRelaunch()
        DiagnosticsLog.record('I', "Run", "second launch", null)
        DiagnosticsLog.flushToDisk()

        simulateRelaunch()

        val previous = DiagnosticsLog.previousRunLog()
        assertContains(previous, "second launch")
        // Two runs back is gone. Mixing them would misdate every line in a
        // report and send us chasing events that happened days apart.
        assertTrue("first launch" !in previous, "only the run that just ended is kept")
    }

    @Test
    fun unflushedLinesAreStillReadableInThisProcess() {
        DiagnosticsLog.attachStorage(dir)
        DiagnosticsLog.record('E', "Ride", "not yet on disk", null)

        // A report drafted before the flusher next ticks still shows the line:
        // the in-memory buffer is what "Recent logs" reads.
        assertContains(DiagnosticsLog.snapshot(), "not yet on disk")
    }

    @Test
    fun recordingWithoutStorageDoesNotThrow() {
        // Lines logged before Application.onCreate attaches storage, and every
        // line on a device where attaching failed, must still buffer in memory.
        DiagnosticsLog.record('I', "Early", "before attach", null)
        DiagnosticsLog.flushToDisk()

        assertContains(DiagnosticsLog.snapshot(), "before attach")
        assertEquals("", DiagnosticsLog.previousRunLog())
    }

    @Test
    fun theLogFileIsCappedRatherThanGrowingWithTheRide() {
        DiagnosticsLog.attachStorage(dir)
        // A long ride logs steadily. Roughly 300 KB of lines, which is past the
        // 128 KB per-file cap.
        repeat(4_000) { i ->
            DiagnosticsLog.record('D', "Trainer", "notification $i: ".padEnd(60, 'x'), null)
            if (i % 200 == 0) DiagnosticsLog.flushToDisk()
        }
        DiagnosticsLog.flushToDisk()

        val current = File(dir, DiagnosticsLog.CURRENT_LOG_NAME)
        val previous = File(dir, DiagnosticsLog.PREVIOUS_LOG_NAME)
        val total = (if (current.exists()) current.length() else 0L) +
            (if (previous.exists()) previous.length() else 0L)
        assertTrue(
            total <= 2 * 128L * 1024L,
            "two files, each capped at 128 KB; got $total bytes",
        )
        // Rotation must not cost us the newest lines, which are the ones next
        // to whatever went wrong.
        assertContains(DiagnosticsLog.snapshot(), "notification 3999")
    }
}

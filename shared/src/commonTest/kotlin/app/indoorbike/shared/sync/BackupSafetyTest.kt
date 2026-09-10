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

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupSafetyTest {

    private fun suspicious(
        hadPreviousOwn: Boolean = true,
        local: Int,
        prior: Int,
        deleted: Int,
    ) = BackupSafety.isSuspiciousReset(hadPreviousOwn, local, prior, deleted)

    @Test
    fun firstSyncIsNeverSuspicious() {
        // No previous slice - nothing could have been lost.
        assertFalse(suspicious(hadPreviousOwn = false, local = 0, prior = 0, deleted = 0))
        assertFalse(suspicious(hadPreviousOwn = false, local = 0, prior = 50, deleted = 50))
    }

    @Test
    fun noDeletesIsNeverSuspicious() {
        assertFalse(suspicious(local = 40, prior = 40, deleted = 0))
        // Local grew (new rides added) - additive, not a reset.
        assertFalse(suspicious(local = 45, prior = 40, deleted = 0))
    }

    @Test
    fun emptiedLocalWithNonEmptyBackupIsSuspicious() {
        // The classic accidental wipe: everything gone locally at once.
        assertTrue(suspicious(local = 0, prior = 40, deleted = 40))
        assertTrue(suspicious(local = 0, prior = 1, deleted = 1))
    }

    @Test
    fun routineSingleDeletesAreNotSuspicious() {
        // Deleting one ride out of many is normal use.
        assertFalse(suspicious(local = 39, prior = 40, deleted = 1))
        // Even a handful at once, if it's a small share of the library.
        assertFalse(suspicious(local = 36, prior = 40, deleted = 4))
    }

    @Test
    fun bulkDeleteOfLargeShareIsSuspicious() {
        // >= 5 deleted AND >= half the backup - looks like a bulk wipe.
        assertTrue(suspicious(local = 5, prior = 10, deleted = 5))
        assertTrue(suspicious(local = 2, prior = 12, deleted = 10))
    }

    @Test
    fun bulkButSmallShareIsNotSuspicious() {
        // 5 deleted but the backup had 40 - only an eighth removed; allowed.
        assertFalse(suspicious(local = 35, prior = 40, deleted = 5))
    }
}

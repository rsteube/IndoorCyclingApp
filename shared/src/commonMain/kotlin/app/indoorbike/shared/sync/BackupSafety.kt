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
package app.indoorbike.shared.sync

/**
 * Guard rails so a sync round can't silently turn an accidental local wipe
 * into a backup wipe.
 *
 * The sync engine infers deletes by diffing local state against the device's
 * previous backup slice: anything in the slice but absent locally is treated
 * as "the rider deleted it" and tombstoned. That inference is correct for a
 * deliberate in-app delete, but dangerous when local data vanished for some
 * other reason (reinstall, cleared app data, a failed DB migration) - then
 * the diff says "the rider deleted everything" and the tombstones propagate
 * the loss into the backup and every other device.
 *
 * [isSuspiciousReset] catches the shape of that accident so the engine can
 * pause and ask the rider instead of publishing the deletes.
 */
object BackupSafety {

    /** A single sync deleting at least this many items is treated as bulk. */
    const val BULK_DELETE_FLOOR = 5

    /**
     * True when this sync round looks like an accidental reset rather than a
     * deliberate delete, given:
     *
     *  - [hadPreviousOwn]      this device has a previous backup slice to diff
     *                          against (no slice = first sync, nothing to lose).
     *  - [localItemCount]      sessions + imports currently on this device.
     *  - [priorItemCount]      sessions + imports in the previous own-slice.
     *  - [deletedItemCount]    items the diff would tombstone this round.
     *
     * Heuristics, in order:
     *  1. No prior slice, or nothing to delete -> never suspicious.
     *  2. Local fully emptied while the backup held data -> suspicious
     *     (classic wipe: the whole library disappeared at once).
     *  3. A single round deleting a large share of the backup
     *     (>= [BULK_DELETE_FLOOR] items AND >= half of what was backed up) ->
     *     suspicious. Normal use removes one / a few rides at a time, so this
     *     won't trip on routine deletes.
     */
    fun isSuspiciousReset(
        hadPreviousOwn: Boolean,
        localItemCount: Int,
        priorItemCount: Int,
        deletedItemCount: Int,
    ): Boolean {
        if (!hadPreviousOwn) return false
        if (deletedItemCount <= 0) return false
        if (localItemCount == 0 && priorItemCount > 0) return true
        return deletedItemCount >= BULK_DELETE_FLOOR && deletedItemCount * 2 >= priorItemCount
    }
}

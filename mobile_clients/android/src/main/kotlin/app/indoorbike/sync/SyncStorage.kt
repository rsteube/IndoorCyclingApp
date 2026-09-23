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
package app.indoorbike.sync

/**
 * Abstraction over the rider's chosen sync folder. Implementations:
 *  - [SafSyncStorage] (production) — backed by a SAF tree URI the rider
 *    picked via the system folder picker, persisted via
 *    `takePersistableUriPermission` and stored in `UserSettings`.
 *  - In-memory fakes in tests.
 *
 * Methods are suspend and run on IO dispatchers; callers don't need to
 * dispatch themselves.
 */
interface SyncStorage {

    /**
     * Reads every device-slice file in the folder, returning the raw JSON
     * text of each. Files that fail to read are silently dropped — partial
     * corruption (a half-written slice from a peer mid-crash) shouldn't
     * block the sync.
     *
     * The order is unspecified. The engine doesn't care — the merger is
     * order-independent.
     */
    suspend fun readAllSlices(): List<String>

    /**
     * Writes (or overwrites) this device's slice. Filename derives from
     * [deviceId]. Atomicity is best-effort — implementations may have a
     * brief window between deleting the old file and creating the new one
     * where the slice is missing. A crash inside that window means the
     * next successful sync re-creates the slice from current local state;
     * data is never lost because every other device still carries its own
     * copy of the shared sessions in its own slice.
     */
    suspend fun writeOwnSlice(deviceId: String, json: String)

    /**
     * This device's own slice, told apart from the rest because not being able
     * to read it means something different: see [OwnSlice].
     */
    suspend fun readOwnSlice(deviceId: String): OwnSlice
}

/** What was found of a device's own slice. */
sealed interface OwnSlice {
    /** There is none: this device has never synced to this folder. */
    data object Missing : OwnSlice

    /**
     * The slice, and the copy it replaced, newest first. The caller parses
     * them in turn, so one that was written half way still has a whole one
     * behind it.
     */
    class Found(val candidates: List<String>) : OwnSlice

    /**
     * It is there and could not be read. Not the same as [Missing]: treating
     * it as missing makes the round a first sync, which brings back rides this
     * device deleted. The round stops and tries again next time.
     */
    class Unreadable(val reason: String) : OwnSlice
}

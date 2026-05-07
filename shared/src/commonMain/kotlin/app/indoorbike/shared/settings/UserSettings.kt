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
package app.indoorbike.shared.settings

import kotlinx.coroutines.flow.StateFlow

/**
 * User-tunable settings. Backed per-platform by the most idiomatic durable
 * store (DataStore on Android, NSUserDefaults wrapper on iOS).
 */
interface UserSettings {
    val ftp: StateFlow<Int>
    fun setFtp(newFtp: Int)

    /** Body mass in kilograms — used for W/kg and XP calculations. */
    val weightKg: StateFlow<Float>
    fun setWeightKg(newWeightKg: Float)

    /**
     * Maximum heart rate in bpm. Used to classify the live HR readout into
     * zones (Garmin's default 5-zone scheme as % of max HR). Set explicitly
     * by the rider in Settings — we don't auto-derive (the 220-age formula
     * is wildly inaccurate for trained athletes).
     */
    val maxHr: StateFlow<Int>
    fun setMaxHr(newMaxHr: Int)

    /** id of the last paired trainer ("mock" for the simulator). */
    val pairedTrainerId: StateFlow<String?>
    fun setPairedTrainerId(id: String?)

    /** id of the last paired heart rate monitor (BLE peripheral identifier). */
    val pairedHrmId: StateFlow<String?>
    fun setPairedHrmId(id: String?)

    /** id of the last paired rider controller (e.g. Zwift Click v1) — BLE peripheral identifier. */
    val pairedControllerId: StateFlow<String?>
    fun setPairedControllerId(id: String?)

    /**
     * True once the user has dismissed the first-run welcome flow on the Rides
     * tab. Used to gate the welcome banner so it only shows once per install.
     */
    val hasCompletedOnboarding: StateFlow<Boolean>
    fun setHasCompletedOnboarding(value: Boolean)

    // ── Intervals.icu cloud sync ──────────────────────────────────────────
    // Off-by-default opt-in cloud sync for completed rides. When the athlete
    // id and API key are both non-blank we treat the integration as
    // "configured"; auto-upload only kicks in if the user also enables it.

    /** Numeric Intervals.icu athlete id (e.g. "i12345" — the leading "i" is part of it). */
    val intervalsAthleteId: StateFlow<String?>
    fun setIntervalsAthleteId(id: String?)

    /** Personal API key from the user's Intervals.icu settings page. */
    val intervalsApiKey: StateFlow<String?>
    fun setIntervalsApiKey(key: String?)

    /** When true, finished rides upload to Intervals.icu automatically. */
    val intervalsAutoUpload: StateFlow<Boolean>
    fun setIntervalsAutoUpload(value: Boolean)

    // ── Favourite workouts ────────────────────────────────────────────────
    // The set of workout ids the rider has starred. Persisted as a flat set
    // of strings on disk so source of the workout (bundled / imported)
    // doesn't matter — favouriting a workout you imported and later removed
    // leaves a stale id, which the UI just filters out.

    val favouriteWorkoutIds: StateFlow<Set<String>>
    fun setWorkoutFavourite(workoutId: String, favourite: Boolean)

    // ── Device identity ───────────────────────────────────────────────────
    // Stable per-install identifier, generated once on first launch and never
    // changed. Used to namespace this device's slice in the backup folder and
    // to derive `BackupSession.externalId` so two phones can't collide on
    // session ids. Opaque to the rider.
    //
    // [deviceLabel] is the rider-facing name shown in the Backup UI ("Pixel
    // 8"), defaulted by the platform impl and editable from Settings.

    val deviceId: String

    val deviceLabel: StateFlow<String>
    fun setDeviceLabel(label: String)

    // ── Backup-sync folder ────────────────────────────────────────────────
    // The platform-specific identifier for the rider's chosen sync folder.
    // On Android it's the URI string of a SAF tree URI persisted via
    // `takePersistableUriPermission`; on iOS it'll be a security-scoped
    // bookmark blob (base64-encoded). `null` = sync not configured.
    //
    // The sync engine treats null as "never sync"; setting it to a URI is
    // what enables the feature.

    val syncFolderUri: StateFlow<String?>
    fun setSyncFolderUri(uri: String?)
}

/** FTP fallback used when a new rider taps "I don't know my FTP" in onboarding. */
const val UNKNOWN_FTP_DEFAULT = 150

const val DEFAULT_FTP = 200
const val MIN_FTP = 50
const val MAX_FTP = 600

const val DEFAULT_WEIGHT_KG = 75f
const val MIN_WEIGHT_KG = 30f
const val MAX_WEIGHT_KG = 250f

/** Sensible average; the rider should set their own in Settings. */
const val DEFAULT_MAX_HR = 185
const val MIN_MAX_HR = 100
const val MAX_MAX_HR = 230

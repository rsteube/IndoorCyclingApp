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

    // ── Simulation mode ───────────────────────────────────────────────────
    // Only a simulated route ride reads these. A workout or a free ride has no
    // road under it, so neither the bike's weight nor its gearing can change
    // what the trainer is asked for.

    /**
     * Mass of the bike itself, in kg, added to the rider's body mass when
     * working out what a hill costs. Matters most on a climb, where a 7 kg
     * road bike and a 15 kg mountain bike are genuinely different rides.
     */
    val bikeWeightKg: StateFlow<Float>
    fun setBikeWeightKg(value: Float)

    /**
     * Which drivetrain the virtual gears model, by
     * [app.indoorbike.shared.simulation.Drivetrain.id]. An unknown id falls
     * back to the default rather than failing, so a setting written by a newer
     * build never breaks an older one.
     */
    val drivetrainId: StateFlow<String>
    fun setDrivetrainId(id: String)

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

    /**
     * True once the rider has explicitly tapped ✕ on the "Start here · Your
     * first ride" pinned card. Independent of whether they've actually ridden
     * yet — a rider who'd rather build their own opener can dismiss the card
     * and never see it again. Becomes implicitly true once any session is
     * recorded (the card is also gated on an empty history), so this flag
     * only matters for the brief window between onboarding and first ride.
     */
    val hasDismissedStarterCard: StateFlow<Boolean>
    fun setHasDismissedStarterCard(value: Boolean)

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

    // ── FitTrackee ────────────────────────────────────────────────────────
    // Self-hosted activity tracker. Unlike Intervals.icu there is no single
    // service to talk to: the rider supplies the address of their own server,
    // which is the whole point of it. Sign-in trades an email and password for
    // a token; only the token is kept, never the password.

    /** Base URL of the rider's FitTrackee instance, e.g. `https://ft.example.org`. */
    val fitTrackeeUrl: StateFlow<String?>
    fun setFitTrackeeUrl(url: String?)

    /**
     * OAuth2 client the rider registered on their own instance. FitTrackee
     * won't let a client register itself (creating one needs an already
     * authenticated request), so these are created in the rider's FitTrackee
     * profile and entered here. They are not a password: they grant only the
     * scopes the app asks for, and revoking them server-side doesn't touch
     * the account.
     */
    val fitTrackeeClientId: StateFlow<String?>
    fun setFitTrackeeClientId(id: String?)

    val fitTrackeeClientSecret: StateFlow<String?>
    fun setFitTrackeeClientSecret(secret: String?)

    /**
     * OAuth2 access token, obtained in a browser so the rider's password never
     * reaches this app. Short-lived; [fitTrackeeRefreshToken] renews it without
     * asking the rider for anything.
     */
    val fitTrackeeAccessToken: StateFlow<String?>
    fun setFitTrackeeAccessToken(token: String?)

    val fitTrackeeRefreshToken: StateFlow<String?>
    fun setFitTrackeeRefreshToken(token: String?)

    /**
     * Which FitTrackee sport a ride is filed under. Sport ids are per-instance
     * rather than global, so this is resolved by asking the server and letting
     * the rider pick. 0 = not chosen yet.
     */
    val fitTrackeeSportId: StateFlow<Int>
    fun setFitTrackeeSportId(id: Int)

    /** When true, finished rides upload to FitTrackee automatically. */
    val fitTrackeeAutoUpload: StateFlow<Boolean>
    fun setFitTrackeeAutoUpload(value: Boolean)

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

    // ── Backup safety pause ───────────────────────────────────────────────
    // Set by the sync engine when a sync round looks like an accidental local
    // reset (e.g. a reinstall / cleared data / bad migration left the local
    // DB empty while the backup still holds rides). While paused, all
    // automated and manual syncs are blocked from *writing* until the rider
    // explicitly resolves it (restore from backup, or confirm the deletes) so
    // a wipe can't silently propagate into the backup. Persisted so a relaunch
    // doesn't quietly resume and overwrite the backup.

    val syncPaused: StateFlow<Boolean>
    fun setSyncPaused(paused: Boolean)
}

/** FTP fallback used when a new rider taps "I don't know my FTP" in onboarding. */
const val UNKNOWN_FTP_DEFAULT = 150

const val DEFAULT_FTP = 200
const val MIN_FTP = 50
const val MAX_FTP = 600

const val DEFAULT_WEIGHT_KG = 75f

/** A road bike with pedals, cages and a couple of bottles. */
const val DEFAULT_BIKE_WEIGHT_KG = 9f
const val MIN_BIKE_WEIGHT_KG = 4f
const val MAX_BIKE_WEIGHT_KG = 30f
const val MIN_WEIGHT_KG = 30f
const val MAX_WEIGHT_KG = 250f

/** Sensible average; the rider should set their own in Settings. */
const val DEFAULT_MAX_HR = 185
const val MIN_MAX_HR = 100
const val MAX_MAX_HR = 230

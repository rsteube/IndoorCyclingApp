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
package app.indoorbike.settings

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import app.indoorbike.shared.settings.DEFAULT_FTP
import app.indoorbike.shared.settings.DEFAULT_MAX_HR
import app.indoorbike.shared.settings.DEFAULT_BIKE_WEIGHT_KG
import app.indoorbike.shared.settings.DEFAULT_WEIGHT_KG
import app.indoorbike.shared.settings.MAX_BIKE_WEIGHT_KG
import app.indoorbike.shared.settings.MIN_BIKE_WEIGHT_KG
import app.indoorbike.shared.simulation.Drivetrain
import app.indoorbike.shared.settings.MAX_FTP
import app.indoorbike.shared.settings.MAX_MAX_HR
import app.indoorbike.shared.settings.MAX_WEIGHT_KG
import app.indoorbike.shared.settings.MIN_FTP
import app.indoorbike.shared.settings.MIN_MAX_HR
import app.indoorbike.shared.settings.MIN_WEIGHT_KG
import app.indoorbike.shared.settings.UserSettings
import app.indoorbike.shared.sync.FitTrackeeUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.userPrefs: androidx.datastore.core.DataStore<Preferences> by preferencesDataStore(
    name = "cadence_user_settings"
)

class AndroidUserSettings(
    private val appContext: Context,
) : UserSettings {

    /**
     * Where every write to the store runs, one at a time and in the order the
     * setters were called. Each write used to be launched on the shared IO
     * pool, where two close together could land in either order: the older
     * value won, and the next read-modify-write started from it.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val store = appContext.userPrefs

    /**
     * Single bulk read of the DataStore on construction so the rest of the
     * StateFlows can be seeded from one disk hit instead of eleven. Construction
     * runs on Main (via `AppContainer.create` in `IndoorBikeApp.onCreate`), so
     * minimising this is a real cold-start win — DataStore's cached-after-first
     * Flow means subsequent collectors read from memory, but that first read is
     * the one we pay for.
     */
    private val initialPrefs: Preferences = runBlocking { store.data.first() }

    /** The credentials are kept sealed: see [SecretBox]. */
    private val secrets = SecretBox()

    /** A credential as stored at start-up, opened. */
    private fun secret(key: Preferences.Key<String>): String? = secrets.open(initialPrefs[key])

    /** [value] as it is written under [key]: sealed if [key] is a credential. */
    private fun stored(key: Preferences.Key<String>, value: String): String {
        if (key !in SECRET_KEYS) return value
        return try {
            secrets.seal(value)
        } catch (t: Throwable) {
            // A device whose Keystore is broken still gets a working
            // connection, kept as it always was before sealing.
            app.indoorbike.shared.util.Log.w("AndroidUserSettings", "Keystore unavailable, credential kept unsealed: ${t.javaClass.simpleName}")
            value
        }
    }

    init {
        // Credentials written before they were sealed are sealed now, once.
        val unsealed = SECRET_KEYS.filter { key -> initialPrefs[key]?.let { !SecretBox.isSealed(it) } == true }
        if (unsealed.isNotEmpty()) {
            scope.launch {
                store.edit { prefs ->
                    for (key in unsealed) {
                        val value = prefs[key] ?: continue
                        if (!SecretBox.isSealed(value)) prefs[key] = stored(key, value)
                    }
                }
            }
        }
    }

    private val _ftp = MutableStateFlow(initialPrefs[KEY_FTP] ?: DEFAULT_FTP)
    override val ftp: StateFlow<Int> = _ftp.asStateFlow()

    private val _weightKg = MutableStateFlow(initialPrefs[KEY_WEIGHT] ?: DEFAULT_WEIGHT_KG)
    override val weightKg: StateFlow<Float> = _weightKg.asStateFlow()

    private val _bikeWeightKg =
        MutableStateFlow(initialPrefs[KEY_BIKE_WEIGHT] ?: DEFAULT_BIKE_WEIGHT_KG)
    override val bikeWeightKg: StateFlow<Float> = _bikeWeightKg.asStateFlow()

    private val _drivetrainId =
        MutableStateFlow(initialPrefs[KEY_DRIVETRAIN] ?: Drivetrain.DEFAULT.id)
    override val drivetrainId: StateFlow<String> = _drivetrainId.asStateFlow()

    private val _maxHr = MutableStateFlow(initialPrefs[KEY_MAX_HR] ?: DEFAULT_MAX_HR)
    override val maxHr: StateFlow<Int> = _maxHr.asStateFlow()

    private val _pairedTrainerId = MutableStateFlow<String?>(initialPrefs[KEY_TRAINER])
    override val pairedTrainerId: StateFlow<String?> = _pairedTrainerId.asStateFlow()

    private val _pairedHrmId = MutableStateFlow<String?>(initialPrefs[KEY_HRM])
    override val pairedHrmId: StateFlow<String?> = _pairedHrmId.asStateFlow()

    private val _pairedControllerId = MutableStateFlow<String?>(initialPrefs[KEY_CONTROLLER])
    override val pairedControllerId: StateFlow<String?> = _pairedControllerId.asStateFlow()

    private val _hasCompletedOnboarding = MutableStateFlow(initialPrefs[KEY_ONBOARDED] ?: false)
    override val hasCompletedOnboarding: StateFlow<Boolean> = _hasCompletedOnboarding.asStateFlow()

    private val _hasDismissedStarterCard = MutableStateFlow(initialPrefs[KEY_DISMISSED_STARTER] ?: false)
    override val hasDismissedStarterCard: StateFlow<Boolean> = _hasDismissedStarterCard.asStateFlow()

    private val _intervalsAthleteId = MutableStateFlow<String?>(initialPrefs[KEY_INTERVALS_ATHLETE])
    override val intervalsAthleteId: StateFlow<String?> = _intervalsAthleteId.asStateFlow()

    private val _intervalsApiKey = MutableStateFlow<String?>(secret(KEY_INTERVALS_API_KEY))
    override val intervalsApiKey: StateFlow<String?> = _intervalsApiKey.asStateFlow()

    private val _intervalsAutoUpload = MutableStateFlow(initialPrefs[KEY_INTERVALS_AUTO] ?: false)
    override val intervalsAutoUpload: StateFlow<Boolean> = _intervalsAutoUpload.asStateFlow()

    private val _companionShowAllData = MutableStateFlow(initialPrefs[KEY_COMPANION_ALL_DATA] ?: false)
    override val companionShowAllData: StateFlow<Boolean> = _companionShowAllData.asStateFlow()

    private val _intervalsAccessToken = MutableStateFlow<String?>(secret(KEY_INTERVALS_ACCESS))
    override val intervalsAccessToken: StateFlow<String?> = _intervalsAccessToken.asStateFlow()

    private val _intervalsAthleteName = MutableStateFlow<String?>(initialPrefs[KEY_INTERVALS_ATHLETE_NAME])
    override val intervalsAthleteName: StateFlow<String?> = _intervalsAthleteName.asStateFlow()

    private val _fitTrackeeUrl = MutableStateFlow<String?>(initialPrefs[KEY_FT_URL])
    override val fitTrackeeUrl: StateFlow<String?> = _fitTrackeeUrl.asStateFlow()

    private val _fitTrackeeClientId = MutableStateFlow<String?>(initialPrefs[KEY_FT_CLIENT_ID])
    override val fitTrackeeClientId: StateFlow<String?> = _fitTrackeeClientId.asStateFlow()

    private val _fitTrackeeClientSecret = MutableStateFlow<String?>(secret(KEY_FT_CLIENT_SECRET))
    override val fitTrackeeClientSecret: StateFlow<String?> = _fitTrackeeClientSecret.asStateFlow()

    private val _fitTrackeeAccessToken = MutableStateFlow<String?>(secret(KEY_FT_ACCESS))
    override val fitTrackeeAccessToken: StateFlow<String?> = _fitTrackeeAccessToken.asStateFlow()

    private val _fitTrackeeRefreshToken = MutableStateFlow<String?>(secret(KEY_FT_REFRESH))
    override val fitTrackeeRefreshToken: StateFlow<String?> = _fitTrackeeRefreshToken.asStateFlow()

    private val _fitTrackeeSportId =
        MutableStateFlow(initialPrefs[KEY_FT_SPORT] ?: FitTrackeeUploader.DEFAULT_SPORT_ID)
    override val fitTrackeeSportId: StateFlow<Int> = _fitTrackeeSportId.asStateFlow()

    private val _fitTrackeeAutoUpload = MutableStateFlow(initialPrefs[KEY_FT_AUTO] ?: false)
    override val fitTrackeeAutoUpload: StateFlow<Boolean> = _fitTrackeeAutoUpload.asStateFlow()

    private val _favouriteWorkoutIds = MutableStateFlow<Set<String>>(initialPrefs[KEY_FAVOURITES] ?: emptySet())
    override val favouriteWorkoutIds: StateFlow<Set<String>> = _favouriteWorkoutIds.asStateFlow()

    /**
     * Resolved at construction. If absent in DataStore (first launch, or a
     * fresh install after uninstall), generate a UUID and persist it
     * synchronously so the value is stable for the life of the install.
     * The runBlocking write is one-time-per-install — negligible cold-start
     * cost vs. the alternative of plumbing async-init through every consumer.
     */
    override val deviceId: String = run {
        val existing = initialPrefs[KEY_DEVICE_ID]
        if (existing != null) existing
        else {
            val generated = UUID.randomUUID().toString()
            runBlocking { store.edit { it[KEY_DEVICE_ID] = generated } }
            generated
        }
    }

    private val _deviceLabel = MutableStateFlow(initialPrefs[KEY_DEVICE_LABEL] ?: defaultDeviceLabel())
    override val deviceLabel: StateFlow<String> = _deviceLabel.asStateFlow()

    private val _syncFolderUri = MutableStateFlow<String?>(initialPrefs[KEY_SYNC_FOLDER_URI])
    override val syncFolderUri: StateFlow<String?> = _syncFolderUri.asStateFlow()

    private val _syncPaused = MutableStateFlow(initialPrefs[KEY_SYNC_PAUSED] ?: false)
    override val syncPaused: StateFlow<Boolean> = _syncPaused.asStateFlow()

    // No collectors copying the store back into the flows. This class is the
    // store's only writer, and the flows already hold what it wrote, so all a
    // collector ever did was deliver an older write after a newer one: a text
    // field that saves on every keystroke lost characters as its earlier
    // values came back, and a set of favourites rebuilt from a stale copy lost
    // entries.

    override fun setFtp(newFtp: Int) {
        if (newFtp !in MIN_FTP..MAX_FTP) return
        _ftp.value = newFtp
        scope.launch { store.edit { it[KEY_FTP] = newFtp } }
    }

    override fun setWeightKg(newWeightKg: Float) {
        if (newWeightKg !in MIN_WEIGHT_KG..MAX_WEIGHT_KG) return
        _weightKg.value = newWeightKg
        scope.launch { store.edit { it[KEY_WEIGHT] = newWeightKg } }
    }

    override fun setBikeWeightKg(value: Float) {
        if (value !in MIN_BIKE_WEIGHT_KG..MAX_BIKE_WEIGHT_KG) return
        _bikeWeightKg.value = value
        scope.launch { store.edit { it[KEY_BIKE_WEIGHT] = value } }
    }

    override fun setDrivetrainId(id: String) {
        // Only ids the app actually knows: a typo or a value from a newer
        // build would otherwise persist and silently fall back forever.
        if (Drivetrain.ALL.none { it.id == id }) return
        _drivetrainId.value = id
        scope.launch { store.edit { it[KEY_DRIVETRAIN] = id } }
    }

    override fun setMaxHr(newMaxHr: Int) {
        if (newMaxHr !in MIN_MAX_HR..MAX_MAX_HR) return
        _maxHr.value = newMaxHr
        scope.launch { store.edit { it[KEY_MAX_HR] = newMaxHr } }
    }

    override fun setPairedTrainerId(id: String?) {
        _pairedTrainerId.value = id
        scope.launch {
            store.edit {
                if (id == null) it.remove(KEY_TRAINER) else it[KEY_TRAINER] = id
            }
        }
    }

    override fun setPairedHrmId(id: String?) {
        _pairedHrmId.value = id
        scope.launch {
            store.edit {
                if (id == null) it.remove(KEY_HRM) else it[KEY_HRM] = id
            }
        }
    }

    override fun setPairedControllerId(id: String?) {
        _pairedControllerId.value = id
        scope.launch {
            store.edit {
                if (id == null) it.remove(KEY_CONTROLLER) else it[KEY_CONTROLLER] = id
            }
        }
    }

    override fun setHasCompletedOnboarding(value: Boolean) {
        _hasCompletedOnboarding.value = value
        scope.launch { store.edit { it[KEY_ONBOARDED] = value } }
    }

    override fun setHasDismissedStarterCard(value: Boolean) {
        if (value == _hasDismissedStarterCard.value) return
        _hasDismissedStarterCard.value = value
        scope.launch { store.edit { it[KEY_DISMISSED_STARTER] = value } }
    }

    override fun setIntervalsAthleteId(id: String?) {
        val cleaned = id?.trim()?.takeIf { it.isNotEmpty() }
        _intervalsAthleteId.value = cleaned
        scope.launch {
            store.edit {
                if (cleaned == null) it.remove(KEY_INTERVALS_ATHLETE)
                else it[KEY_INTERVALS_ATHLETE] = cleaned
            }
        }
    }

    override fun setIntervalsApiKey(key: String?) {
        val cleaned = key?.trim()?.takeIf { it.isNotEmpty() }
        _intervalsApiKey.value = cleaned
        scope.launch {
            store.edit {
                if (cleaned == null) it.remove(KEY_INTERVALS_API_KEY)
                else it[KEY_INTERVALS_API_KEY] = stored(KEY_INTERVALS_API_KEY, cleaned)
            }
        }
    }

    override fun setIntervalsAutoUpload(value: Boolean) {
        _intervalsAutoUpload.value = value
        scope.launch { store.edit { it[KEY_INTERVALS_AUTO] = value } }
    }

    override fun setCompanionShowAllData(value: Boolean) {
        _companionShowAllData.value = value
        scope.launch { store.edit { it[KEY_COMPANION_ALL_DATA] = value } }
    }

    override fun setIntervalsAccessToken(token: String?) {
        val cleaned = token?.trim()?.takeIf { it.isNotEmpty() }
        _intervalsAccessToken.value = cleaned
        scope.launch {
            store.edit {
                if (cleaned == null) it.remove(KEY_INTERVALS_ACCESS)
                else it[KEY_INTERVALS_ACCESS] = stored(KEY_INTERVALS_ACCESS, cleaned)
            }
        }
    }

    override fun setIntervalsAthleteName(name: String?) {
        val cleaned = name?.trim()?.takeIf { it.isNotEmpty() }
        _intervalsAthleteName.value = cleaned
        scope.launch {
            store.edit {
                if (cleaned == null) it.remove(KEY_INTERVALS_ATHLETE_NAME)
                else it[KEY_INTERVALS_ATHLETE_NAME] = cleaned
            }
        }
    }

    override fun setFitTrackeeUrl(url: String?) {
        // Normalise once, here, so every caller can concatenate paths without
        // worrying about a trailing slash the rider may or may not have typed.
        val cleaned = url?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        _fitTrackeeUrl.value = cleaned
        scope.launch {
            store.edit {
                if (cleaned == null) it.remove(KEY_FT_URL) else it[KEY_FT_URL] = cleaned
            }
        }
    }

    override fun setFitTrackeeClientId(id: String?) = putFitTrackee(KEY_FT_CLIENT_ID, _fitTrackeeClientId, id)

    override fun setFitTrackeeClientSecret(secret: String?) =
        putFitTrackee(KEY_FT_CLIENT_SECRET, _fitTrackeeClientSecret, secret)

    override fun setFitTrackeeAccessToken(token: String?) =
        putFitTrackee(KEY_FT_ACCESS, _fitTrackeeAccessToken, token)

    override fun setFitTrackeeRefreshToken(token: String?) =
        putFitTrackee(KEY_FT_REFRESH, _fitTrackeeRefreshToken, token)

    /** The four FitTrackee credential fields all store the same way. */
    private fun putFitTrackee(
        key: Preferences.Key<String>,
        flow: MutableStateFlow<String?>,
        raw: String?,
    ) {
        val cleaned = raw?.trim()?.takeIf { it.isNotEmpty() }
        flow.value = cleaned
        scope.launch {
            store.edit { if (cleaned == null) it.remove(key) else it[key] = stored(key, cleaned) }
        }
    }

    override fun setFitTrackeeSportId(id: Int) {
        _fitTrackeeSportId.value = id
        scope.launch { store.edit { it[KEY_FT_SPORT] = id } }
    }

    override fun setFitTrackeeAutoUpload(value: Boolean) {
        _fitTrackeeAutoUpload.value = value
        scope.launch { store.edit { it[KEY_FT_AUTO] = value } }
    }

    override fun setSyncFolderUri(uri: String?) {
        val cleaned = uri?.trim()?.takeIf { it.isNotEmpty() }
        if (cleaned == _syncFolderUri.value) return
        _syncFolderUri.value = cleaned
        scope.launch {
            store.edit {
                if (cleaned == null) it.remove(KEY_SYNC_FOLDER_URI)
                else it[KEY_SYNC_FOLDER_URI] = cleaned
            }
        }
    }

    override fun setSyncPaused(paused: Boolean) {
        if (paused == _syncPaused.value) return
        _syncPaused.value = paused
        scope.launch { store.edit { it[KEY_SYNC_PAUSED] = paused } }
    }

    override fun setDeviceLabel(label: String) {
        val cleaned = label.trim().take(MAX_DEVICE_LABEL_LEN).ifEmpty { return }
        if (cleaned == _deviceLabel.value) return
        _deviceLabel.value = cleaned
        scope.launch { store.edit { it[KEY_DEVICE_LABEL] = cleaned } }
    }

    override fun setFavouriteWorkoutIds(ids: Set<String>) {
        if (ids == _favouriteWorkoutIds.value) return
        _favouriteWorkoutIds.value = ids
        scope.launch { store.edit { it[KEY_FAVOURITES] = ids } }
    }

    override fun setWorkoutFavourite(workoutId: String, favourite: Boolean) {
        val current = _favouriteWorkoutIds.value
        // No-op fast path: avoids both an unnecessary StateFlow emission and a
        // disk write for the common "already in this state" tap (e.g. the
        // favourite icon being toggled by recompositions).
        if (favourite == workoutId in current) return
        val updated = if (favourite) current + workoutId else current - workoutId
        _favouriteWorkoutIds.value = updated
        scope.launch { store.edit { it[KEY_FAVOURITES] = updated } }
    }

    fun dispose() {
        scope.cancel()
    }

    companion object {
        private val KEY_FTP = intPreferencesKey("user_ftp")
        private val KEY_WEIGHT = floatPreferencesKey("user_weight_kg")
        private val KEY_BIKE_WEIGHT = floatPreferencesKey("bike_weight_kg")
        private val KEY_DRIVETRAIN = stringPreferencesKey("simulation_drivetrain_id")
        private val KEY_MAX_HR = intPreferencesKey("user_max_hr")
        private val KEY_TRAINER = stringPreferencesKey("paired_trainer_id")
        private val KEY_HRM = stringPreferencesKey("paired_hrm_id")
        private val KEY_CONTROLLER = stringPreferencesKey("paired_controller_id")
        private val KEY_ONBOARDED = booleanPreferencesKey("has_completed_onboarding")
        private val KEY_DISMISSED_STARTER = booleanPreferencesKey("has_dismissed_starter_card")
        // Intervals.icu sync — opt-in. The API key sits in DataStore (plaintext)
        // for now; if/when device-credentials hardening lands we'll move it to
        // EncryptedSharedPreferences (or AndroidX security-crypto) without
        // touching the UserSettings interface.
        private val KEY_INTERVALS_ATHLETE = stringPreferencesKey("intervals_athlete_id")
        private val KEY_INTERVALS_API_KEY = stringPreferencesKey("intervals_api_key")
        private val KEY_INTERVALS_AUTO = booleanPreferencesKey("intervals_auto_upload")
        private val KEY_COMPANION_ALL_DATA = booleanPreferencesKey("companion_show_all_data")
        private val KEY_INTERVALS_ACCESS = stringPreferencesKey("intervals_access_token")
        private val KEY_INTERVALS_ATHLETE_NAME = stringPreferencesKey("intervals_athlete_name")
        private val KEY_FT_URL = stringPreferencesKey("fittrackee_url")
        private val KEY_FT_CLIENT_ID = stringPreferencesKey("fittrackee_client_id")
        private val KEY_FT_CLIENT_SECRET = stringPreferencesKey("fittrackee_client_secret")
        private val KEY_FT_ACCESS = stringPreferencesKey("fittrackee_access_token")
        private val KEY_FT_REFRESH = stringPreferencesKey("fittrackee_refresh_token")
        private val KEY_FT_SPORT = intPreferencesKey("fittrackee_sport_id")

        /** What [SecretBox] seals. The FitTrackee client id and server address are not secret. */
        private val SECRET_KEYS = setOf(
            KEY_INTERVALS_API_KEY, KEY_INTERVALS_ACCESS,
            KEY_FT_CLIENT_SECRET, KEY_FT_ACCESS, KEY_FT_REFRESH,
        )
        private val KEY_FT_AUTO = booleanPreferencesKey("fittrackee_auto_upload")
        private val KEY_FAVOURITES = stringSetPreferencesKey("favourite_workout_ids")
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_DEVICE_LABEL = stringPreferencesKey("device_label")
        private val KEY_SYNC_FOLDER_URI = stringPreferencesKey("sync_folder_uri")
        private val KEY_SYNC_PAUSED = booleanPreferencesKey("sync_paused")

        /**
         * Cap the label so it can't poison the backup file (a multi-MB string
         * smuggled into a sync slice would balloon every device's read).
         */
        private const val MAX_DEVICE_LABEL_LEN = 64

        /**
         * Free-form, rider-editable. `Build.MODEL` ("Pixel 8", "SM-G998B") is
         * a reasonable starting point — matches what users see in their
         * phone's Settings → About — and is overwritten on first explicit
         * edit. Falls back to "Phone" if the device exposes a blank model.
         */
        private fun defaultDeviceLabel(): String =
            Build.MODEL?.trim()?.takeIf { it.isNotEmpty() } ?: "Phone"
    }
}

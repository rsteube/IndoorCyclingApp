package app.indoorbike.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.indoorbike.shared.settings.DEFAULT_FTP
import app.indoorbike.shared.settings.DEFAULT_MAX_HR
import app.indoorbike.shared.settings.DEFAULT_WEIGHT_KG
import app.indoorbike.shared.settings.MAX_FTP
import app.indoorbike.shared.settings.MAX_MAX_HR
import app.indoorbike.shared.settings.MAX_WEIGHT_KG
import app.indoorbike.shared.settings.MIN_FTP
import app.indoorbike.shared.settings.MIN_MAX_HR
import app.indoorbike.shared.settings.MIN_WEIGHT_KG
import app.indoorbike.shared.settings.UserSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.userPrefs: androidx.datastore.core.DataStore<Preferences> by preferencesDataStore(
    name = "cadence_user_settings"
)

class AndroidUserSettings(
    private val appContext: Context,
) : UserSettings {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val store = appContext.userPrefs

    private val _ftp = MutableStateFlow(loadFtpBlocking())
    override val ftp: StateFlow<Int> = _ftp.asStateFlow()

    private val _weightKg = MutableStateFlow(loadWeightBlocking())
    override val weightKg: StateFlow<Float> = _weightKg.asStateFlow()

    private val _maxHr = MutableStateFlow(loadMaxHrBlocking())
    override val maxHr: StateFlow<Int> = _maxHr.asStateFlow()

    private val _pairedTrainerId = MutableStateFlow<String?>(loadStringBlocking(KEY_TRAINER))
    override val pairedTrainerId: StateFlow<String?> = _pairedTrainerId.asStateFlow()

    private val _pairedHrmId = MutableStateFlow<String?>(loadStringBlocking(KEY_HRM))
    override val pairedHrmId: StateFlow<String?> = _pairedHrmId.asStateFlow()

    private val _pairedControllerId = MutableStateFlow<String?>(loadStringBlocking(KEY_CONTROLLER))
    override val pairedControllerId: StateFlow<String?> = _pairedControllerId.asStateFlow()

    private val _hasCompletedOnboarding = MutableStateFlow(loadBooleanBlocking(KEY_ONBOARDED))
    override val hasCompletedOnboarding: StateFlow<Boolean> = _hasCompletedOnboarding.asStateFlow()

    private val _intervalsAthleteId = MutableStateFlow<String?>(loadStringBlocking(KEY_INTERVALS_ATHLETE))
    override val intervalsAthleteId: StateFlow<String?> = _intervalsAthleteId.asStateFlow()

    private val _intervalsApiKey = MutableStateFlow<String?>(loadStringBlocking(KEY_INTERVALS_API_KEY))
    override val intervalsApiKey: StateFlow<String?> = _intervalsApiKey.asStateFlow()

    private val _intervalsAutoUpload = MutableStateFlow(loadBooleanBlocking(KEY_INTERVALS_AUTO))
    override val intervalsAutoUpload: StateFlow<Boolean> = _intervalsAutoUpload.asStateFlow()

    init {
        scope.launch {
            store.data.map { it[KEY_FTP] ?: DEFAULT_FTP }.collect { value ->
                if (value != _ftp.value) _ftp.value = value
            }
        }
        scope.launch {
            store.data.map { it[KEY_WEIGHT] ?: DEFAULT_WEIGHT_KG }.collect { value ->
                if (value != _weightKg.value) _weightKg.value = value
            }
        }
        scope.launch {
            store.data.map { it[KEY_MAX_HR] ?: DEFAULT_MAX_HR }.collect { value ->
                if (value != _maxHr.value) _maxHr.value = value
            }
        }
        scope.launch {
            store.data.map { it[KEY_TRAINER] }.collect { value ->
                if (value != _pairedTrainerId.value) _pairedTrainerId.value = value
            }
        }
        scope.launch {
            store.data.map { it[KEY_HRM] }.collect { value ->
                if (value != _pairedHrmId.value) _pairedHrmId.value = value
            }
        }
        scope.launch {
            store.data.map { it[KEY_CONTROLLER] }.collect { value ->
                if (value != _pairedControllerId.value) _pairedControllerId.value = value
            }
        }
        scope.launch {
            store.data.map { it[KEY_ONBOARDED] ?: false }.collect { value ->
                if (value != _hasCompletedOnboarding.value) _hasCompletedOnboarding.value = value
            }
        }
        scope.launch {
            store.data.map { it[KEY_INTERVALS_ATHLETE] }.collect { value ->
                if (value != _intervalsAthleteId.value) _intervalsAthleteId.value = value
            }
        }
        scope.launch {
            store.data.map { it[KEY_INTERVALS_API_KEY] }.collect { value ->
                if (value != _intervalsApiKey.value) _intervalsApiKey.value = value
            }
        }
        scope.launch {
            store.data.map { it[KEY_INTERVALS_AUTO] ?: false }.collect { value ->
                if (value != _intervalsAutoUpload.value) _intervalsAutoUpload.value = value
            }
        }
    }

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
                else it[KEY_INTERVALS_API_KEY] = cleaned
            }
        }
    }

    override fun setIntervalsAutoUpload(value: Boolean) {
        _intervalsAutoUpload.value = value
        scope.launch { store.edit { it[KEY_INTERVALS_AUTO] = value } }
    }

    fun dispose() {
        scope.cancel()
    }

    private fun loadFtpBlocking(): Int = runBlocking {
        store.data.first()[KEY_FTP] ?: DEFAULT_FTP
    }

    private fun loadWeightBlocking(): Float = runBlocking {
        store.data.first()[KEY_WEIGHT] ?: DEFAULT_WEIGHT_KG
    }

    private fun loadMaxHrBlocking(): Int = runBlocking {
        store.data.first()[KEY_MAX_HR] ?: DEFAULT_MAX_HR
    }

    private fun loadStringBlocking(key: androidx.datastore.preferences.core.Preferences.Key<String>): String? =
        runBlocking { store.data.first()[key] }

    private fun loadBooleanBlocking(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>): Boolean =
        runBlocking { store.data.first()[key] ?: false }

    companion object {
        private val KEY_FTP = intPreferencesKey("user_ftp")
        private val KEY_WEIGHT = floatPreferencesKey("user_weight_kg")
        private val KEY_MAX_HR = intPreferencesKey("user_max_hr")
        private val KEY_TRAINER = stringPreferencesKey("paired_trainer_id")
        private val KEY_HRM = stringPreferencesKey("paired_hrm_id")
        private val KEY_CONTROLLER = stringPreferencesKey("paired_controller_id")
        private val KEY_ONBOARDED = booleanPreferencesKey("has_completed_onboarding")
        // Intervals.icu sync — opt-in. The API key sits in DataStore (plaintext)
        // for now; if/when device-credentials hardening lands we'll move it to
        // EncryptedSharedPreferences (or AndroidX security-crypto) without
        // touching the UserSettings interface.
        private val KEY_INTERVALS_ATHLETE = stringPreferencesKey("intervals_athlete_id")
        private val KEY_INTERVALS_API_KEY = stringPreferencesKey("intervals_api_key")
        private val KEY_INTERVALS_AUTO = booleanPreferencesKey("intervals_auto_upload")
    }
}

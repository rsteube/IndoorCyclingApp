package app.indoorbike.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.indoorbike.shared.settings.DEFAULT_FTP
import app.indoorbike.shared.settings.MAX_FTP
import app.indoorbike.shared.settings.MIN_FTP
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

    private val _pairedTrainerId = MutableStateFlow<String?>(loadStringBlocking(KEY_TRAINER))
    override val pairedTrainerId: StateFlow<String?> = _pairedTrainerId.asStateFlow()

    private val _pairedHrmId = MutableStateFlow<String?>(loadStringBlocking(KEY_HRM))
    override val pairedHrmId: StateFlow<String?> = _pairedHrmId.asStateFlow()

    init {
        scope.launch {
            store.data.map { it[KEY_FTP] ?: DEFAULT_FTP }.collect { value ->
                if (value != _ftp.value) _ftp.value = value
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
    }

    override fun setFtp(newFtp: Int) {
        if (newFtp !in MIN_FTP..MAX_FTP) return
        _ftp.value = newFtp
        scope.launch { store.edit { it[KEY_FTP] = newFtp } }
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

    fun dispose() {
        scope.cancel()
    }

    private fun loadFtpBlocking(): Int = runBlocking {
        store.data.first()[KEY_FTP] ?: DEFAULT_FTP
    }

    private fun loadStringBlocking(key: androidx.datastore.preferences.core.Preferences.Key<String>): String? =
        runBlocking { store.data.first()[key] }

    companion object {
        private val KEY_FTP = intPreferencesKey("user_ftp")
        private val KEY_TRAINER = stringPreferencesKey("paired_trainer_id")
        private val KEY_HRM = stringPreferencesKey("paired_hrm_id")
    }
}

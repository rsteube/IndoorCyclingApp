package app.indoorbike

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object UserSettings {
    private const val PREFS_NAME = "cadence_prefs"
    private const val KEY_FTP = "user_ftp"
    private const val DEFAULT_FTP = 200

    private var prefs: SharedPreferences? = null

    private val _ftp = MutableStateFlow(DEFAULT_FTP)
    val ftp: StateFlow<Int> = _ftp.asStateFlow()

    fun init(context: Context) {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sharedPrefs
        _ftp.value = sharedPrefs.getInt(KEY_FTP, DEFAULT_FTP)
    }

    fun setFtp(newFtp: Int) {
        _ftp.value = newFtp
        prefs?.edit()?.putInt(KEY_FTP, newFtp)?.apply()
    }
}

package app.indoorbike.shared.settings

import kotlinx.coroutines.flow.StateFlow

/**
 * User-tunable settings. Backed per-platform by the most idiomatic durable
 * store (DataStore on Android, NSUserDefaults wrapper on iOS).
 */
interface UserSettings {
    val ftp: StateFlow<Int>
    fun setFtp(newFtp: Int)

    /** id of the last paired trainer ("mock" for the simulator). */
    val pairedTrainerId: StateFlow<String?>
    fun setPairedTrainerId(id: String?)

    /** id of the last paired heart rate monitor (BLE peripheral identifier). */
    val pairedHrmId: StateFlow<String?>
    fun setPairedHrmId(id: String?)
}

const val DEFAULT_FTP = 200
const val MIN_FTP = 50
const val MAX_FTP = 600

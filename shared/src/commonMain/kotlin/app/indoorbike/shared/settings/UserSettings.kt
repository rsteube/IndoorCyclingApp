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

    /** id of the last paired trainer ("mock" for the simulator). */
    val pairedTrainerId: StateFlow<String?>
    fun setPairedTrainerId(id: String?)

    /** id of the last paired heart rate monitor (BLE peripheral identifier). */
    val pairedHrmId: StateFlow<String?>
    fun setPairedHrmId(id: String?)

    /**
     * True once the user has dismissed the first-run welcome flow on the Rides
     * tab. Used to gate the welcome banner so it only shows once per install.
     */
    val hasCompletedOnboarding: StateFlow<Boolean>
    fun setHasCompletedOnboarding(value: Boolean)
}

/** FTP fallback used when a new rider taps "I don't know my FTP" in onboarding. */
const val UNKNOWN_FTP_DEFAULT = 150

const val DEFAULT_FTP = 200
const val MIN_FTP = 50
const val MAX_FTP = 600

const val DEFAULT_WEIGHT_KG = 75f
const val MIN_WEIGHT_KG = 30f
const val MAX_WEIGHT_KG = 250f

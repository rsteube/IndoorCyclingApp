package app.indoorbike.shared.ble

/**
 * Domain-level representation of a discovered trainer. Decouples the UI from
 * `com.juul.kable.Advertisement`, so swapping BLE libraries doesn't ripple
 * through every screen.
 */
data class TrainerCandidate(
    val id: String,            // stable identifier (BLE address on Android)
    val name: String?,         // user-visible name, null if not advertised
    val rssi: Int? = null,     // signal strength if known
)

package app.indoorbike.shared.util

/**
 * Minimal cross-platform logger. F-Droid forbids Crashlytics, so this is just
 * platform-native logging (Logcat on Android, NSLog on iOS) — no remote sink.
 *
 * Use sparingly: prefer flow-based state for runtime conditions; this is for
 * diagnostics, parse failures, and unrecoverable BLE errors.
 */
expect object Log {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

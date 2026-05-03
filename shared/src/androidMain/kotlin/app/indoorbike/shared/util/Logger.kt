package app.indoorbike.shared.util

import android.util.Log as AndroidLog

actual object Log {
    actual fun d(tag: String, message: String) { AndroidLog.d(tag, message) }
    actual fun i(tag: String, message: String) { AndroidLog.i(tag, message) }
    actual fun w(tag: String, message: String, throwable: Throwable?) {
        if (throwable == null) AndroidLog.w(tag, message) else AndroidLog.w(tag, message, throwable)
    }
    actual fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable == null) AndroidLog.e(tag, message) else AndroidLog.e(tag, message, throwable)
    }
}

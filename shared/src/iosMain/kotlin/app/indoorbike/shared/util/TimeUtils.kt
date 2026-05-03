package app.indoorbike.shared.util

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual fun getCurrentEpochMillis(): Long {
    return (NSDate().timeIntervalSince1970 * 1000).toLong()
}

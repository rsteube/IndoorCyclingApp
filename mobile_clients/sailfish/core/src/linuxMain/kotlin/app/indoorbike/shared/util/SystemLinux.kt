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
package app.indoorbike.shared.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.CLOCK_REALTIME
import platform.posix.O_CLOEXEC
import platform.posix.O_RDONLY
import platform.posix.PTHREAD_MUTEX_RECURSIVE
import platform.posix.clock_gettime
import platform.posix.close
import platform.posix.open
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import platform.posix.pthread_mutexattr_destroy
import platform.posix.pthread_mutexattr_init
import platform.posix.pthread_mutexattr_settype
import platform.posix.pthread_mutexattr_t
import platform.posix.read
import platform.posix.timespec
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner

@OptIn(ExperimentalForeignApi::class)
actual fun getCurrentEpochMillis(): Long = memScoped {
    val now = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, now.ptr)
    now.tv_sec.toLong() * 1000L + now.tv_nsec.toLong() / 1_000_000L
}

/**
 * The kernel's CSPRNG through `/dev/urandom`, which never blocks once the
 * system has booted. `getrandom()` would be the modern call, but Kotlin/Native's
 * x86_64 sysroot predates it. Do not fall back to `kotlin.random.Random`: see
 * the expect declaration for why. A phone that cannot read it fails loudly.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(n: Int): ByteArray {
    require(n >= 0) { "cannot ask for $n bytes" }
    val bytes = ByteArray(n)
    if (n == 0) return bytes
    val fd = open("/dev/urandom", O_RDONLY or O_CLOEXEC)
    check(fd >= 0) { "Cannot open /dev/urandom" }
    try {
        var filled = 0
        bytes.usePinned { pinned ->
            while (filled < n) {
                val got = read(fd, pinned.addressOf(filled), (n - filled).convert()).toInt()
                check(got > 0) { "Cannot read /dev/urandom" }
                filled += got
            }
        }
    } finally {
        close(fd)
    }
    return bytes
}

/** A recursive pthread mutex, the counterpart of iOS's `NSRecursiveLock`. */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual class ReentrantLock actual constructor() {
    private val mutex = nativeHeap.alloc<pthread_mutex_t>()

    init {
        memScoped {
            val attributes = alloc<pthread_mutexattr_t>()
            pthread_mutexattr_init(attributes.ptr)
            pthread_mutexattr_settype(attributes.ptr, PTHREAD_MUTEX_RECURSIVE.convert())
            pthread_mutex_init(mutex.ptr, attributes.ptr)
            pthread_mutexattr_destroy(attributes.ptr)
        }
    }

    @Suppress("unused") // held for its side effect: freeing the mutex with the lock
    private val cleaner = createCleaner(mutex) {
        pthread_mutex_destroy(it.ptr)
        nativeHeap.free(it)
    }

    actual fun lock() {
        pthread_mutex_lock(mutex.ptr)
    }

    actual fun unlock() {
        pthread_mutex_unlock(mutex.ptr)
    }
}

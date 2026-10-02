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
package app.indoorbike.shared.ios

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * A Kotlin [Flow] as Swift can hold one: a callback per value, on the main
 * thread, until [Watching.cancel].
 *
 * Swift has no way to collect a Flow itself - `collect` is a suspend function
 * taking a FlowCollector, which Objective-C export turns into a completion
 * handler Swift cannot drive from SwiftUI. This is the one bridge the iOS app
 * needs to read the StateFlows the shared managers already expose, without
 * pulling in a Gradle plugin to generate one.
 *
 * Generic on the class rather than on a function, because Objective-C export
 * keeps a class's type parameter and drops a function's: Swift then gets
 * `FlowWatcher<KotlinInt>` and a typed value instead of `Any?`.
 */
class FlowWatcher<T>(private val flow: Flow<T>) {
    fun watch(onEach: (T) -> Unit): Watching {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        flow.onEach { onEach(it) }.launchIn(scope)
        return Watching { scope.cancel() }
    }
}

/** Stops a [FlowWatcher.watch]. Cancelling twice is harmless. */
fun interface Watching {
    fun cancel()
}

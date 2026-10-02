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

import kotlin.concurrent.Volatile

/**
 * A map any thread may use, one call at a time: the common stand-in for the
 * JVM's ConcurrentHashMap where Kotlin/Native has none.
 */
class LockedMap<K, V> {
    private val lock = ReentrantLock()
    private val map = HashMap<K, V>()

    operator fun get(key: K): V? = lock.withLock { map[key] }
    operator fun set(key: K, value: V) { lock.withLock { map[key] = value } }
    fun remove(key: K): V? = lock.withLock { map.remove(key) }
    fun containsKey(key: K): Boolean = lock.withLock { map.containsKey(key) }
    fun clear() { lock.withLock { map.clear() } }
    fun getOrPut(key: K, make: () -> V): V = lock.withLock { map.getOrPut(key, make) }
}

/**
 * A list read far more often than it changes: every change makes a new list,
 * so iterating a snapshot never races a change. The common stand-in for the
 * JVM's CopyOnWriteArrayList.
 */
class CopyOnWriteList<T> : Iterable<T> {
    private val lock = ReentrantLock()

    @Volatile
    private var items: List<T> = emptyList()

    fun add(item: T) { lock.withLock { items = items + item } }
    fun remove(item: T): Boolean = lock.withLock {
        val before = items.size
        items = items - item
        items.size != before
    }
    fun clear() { lock.withLock { items = emptyList() } }
    val size: Int get() = items.size
    override fun iterator(): Iterator<T> = items.iterator()
}

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
package app.indoorbike.shared.sailfish

import app.indoorbike.shared.util.PosixFiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonPrimitive

/**
 * OpenStreetMap's standard tiles for the route map, as the Android app draws
 * them through osmdroid: open data, no account and no key.
 *
 * Kept to OSM's tile usage policy (operations.osmfoundation.org/policies/tiles):
 * the app identifies itself in its User-Agent, no more than two tiles are
 * fetched at once, a tile is fetched once and kept in the cache folder, and the
 * map shows the attribution. Tiles are the only part of a route ride that needs
 * the network: with none, the road is drawn on an empty ground.
 */
internal class MapTiles {
    private val downloads = Semaphore(2)

    /** One download per tile, however many views ask for it at once. */
    private val pending = HashMap<String, Deferred<String>>()

    /** The tile's file in the cache, fetched first if it is not there yet. */
    suspend fun tile(z: Int, x: Int, y: Int): String {
        require(z in 0..MAX_ZOOM) { "No zoom level $z" }
        val n = 1 shl z
        require(x in 0 until n && y in 0 until n) { "No tile $x,$y at zoom $z" }
        val key = "$z/$x/$y"
        val path = SailfishPaths.cache("tiles/$key.png")
        if (PosixFiles.exists(path)) return path
        pending[key]?.let { return it.await() }
        val result = CompletableDeferred<String>()
        pending[key] = result
        try {
            downloads.withPermit {
                val bytes = LinuxHttp.getBytes("https://tile.openstreetmap.org/$key.png", MAX_TILE_BYTES)
                PosixFiles.mkdirs(path.substringBeforeLast('/'))
                PosixFiles.writeAtomically(path, bytes)
            }
            result.complete(path)
            return path
        } catch (e: Exception) {
            result.completeExceptionally(e)
            throw e
        } finally {
            pending.remove(key)
        }
    }

    companion object {
        /** OpenStreetMap's standard style stops at 19. */
        const val MAX_ZOOM = 19
        const val MAX_TILE_BYTES = 1L * 1024 * 1024
    }
}

/** {"cmd": "map.tile", "z": 14, "x": 9301, "y": 4740}: the cached tile's path, fetched if need be. */
internal fun SailfishCore.installMapTiles() {
    val tiles = MapTiles()
    bus.request("map.tile") { args ->
        JsonPrimitive(tiles.tile(args.requireInt("z"), args.requireInt("x"), args.requireInt("y")))
    }
}

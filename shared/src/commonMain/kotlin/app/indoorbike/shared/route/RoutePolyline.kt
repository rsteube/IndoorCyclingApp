/*
 * Copyright (C) 2026 Juhani Lehtimäki
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
package app.indoorbike.shared.route

/**
 * The encoded polyline format, used for [app.indoorbike.shared.repo.RouteEntry.shape].
 *
 * A route listing wants to draw the shape of the road on a card, which needs a
 * couple of hundred coordinate pairs. As JSON numbers that is around 4 kB per
 * route and the listing stops being small. The standard polyline encoding
 * stores each point as a delta from the last one in base64-ish characters and
 * gets the same shape into roughly 800 bytes, which is the difference between
 * a listing a phone fetches without thinking and one it does not.
 *
 * This is the well-known algorithm with precision 5, about 1 m of resolution:
 * take the difference from the previous coordinate, round to five decimals,
 * zig-zag it so negatives stay short, then emit five bits at a time with a
 * continuation flag and an offset of 63 to land in printable ASCII.
 *
 * Precision 5 is chosen because it is what every other tool speaks. A shape
 * this coarse is for drawing only; the ride reads the GPX.
 */
object RoutePolyline {

    private const val PRECISION = 1e5

    /** Encode [points] as an encoded polyline string. */
    fun encode(points: List<Pair<Double, Double>>): String {
        val out = StringBuilder()
        var previousLat = 0L
        var previousLon = 0L
        for ((latitude, longitude) in points) {
            val lat = kotlin.math.round(latitude * PRECISION).toLong()
            val lon = kotlin.math.round(longitude * PRECISION).toLong()
            encodeValue(lat - previousLat, out)
            encodeValue(lon - previousLon, out)
            previousLat = lat
            previousLon = lon
        }
        return out.toString()
    }

    /**
     * Decode an encoded polyline into lat/lon pairs. Returns an empty list for
     * anything malformed rather than throwing: a broken shape in a community
     * listing should cost the card its thumbnail, not crash the picker.
     */
    fun decode(encoded: String): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            val latResult = decodeValue(encoded, index) ?: return emptyList()
            index = latResult.second
            val lonResult = decodeValue(encoded, index) ?: return emptyList()
            index = lonResult.second
            lat += latResult.first
            lon += lonResult.first
            out.add((lat / PRECISION) to (lon / PRECISION))
        }
        return out
    }

    private fun encodeValue(value: Long, out: StringBuilder) {
        // Zig-zag: shift left one and flip every bit of a negative, so small
        // magnitudes encode short whatever their sign.
        var v = if (value < 0) (value shl 1).inv() else (value shl 1)
        while (v >= 0x20) {
            out.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        out.append((v.toInt() + 63).toChar())
    }

    /** Returns the decoded delta and the index just past it, or null if truncated. */
    private fun decodeValue(encoded: String, start: Int): Pair<Long, Int>? {
        var index = start
        var shift = 0
        var result = 0L
        while (true) {
            if (index >= encoded.length) return null
            // A chunk wider than a coordinate can ever be means the string is
            // not a polyline; stop rather than shifting into nonsense.
            if (shift > 30) return null
            val chunk = encoded[index].code - 63
            if (chunk < 0) return null
            index++
            result = result or ((chunk and 0x1f).toLong() shl shift)
            if (chunk < 0x20) break
            shift += 5
        }
        val value = if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)
        return value to index
    }
}

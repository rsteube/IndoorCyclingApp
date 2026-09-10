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
package app.indoorbike.shared.route

/**
 * Reads the handful of GPX elements a route simulation actually needs, with a
 * hand-rolled scanner rather than an XML library.
 *
 * The same reasoning as the FIT and TCX encoders in `shared/`: the parser has
 * to run in `commonMain` so iOS gets it for free, and Kotlin Multiplatform has
 * no XML parser in the standard library. Pulling one in for four element names
 * would cost a dependency on every target to read `lat`, `lon`, `ele` and
 * `name`.
 *
 * What it accepts, deliberately generously, because GPX in the wild is written
 * by dozens of tools that each interpret the schema slightly differently:
 *
 *  - Namespaced tags (`<gpx:trkpt>`), any attribute order, single or double
 *    quotes, self-closing points, arbitrary whitespace and newlines inside a
 *    tag, and unknown child elements (`<time>`, `<extensions>`, power and
 *    heart-rate data from a recorded ride) which are skipped.
 *  - `<trkpt>` first; a file with no track falls back to `<rtept>` (a planned
 *    route rather than a recorded one), which is what route planners export.
 *  - A missing `<ele>` gives that point zero elevation. A route with no
 *    elevation at all simulates as flat rather than failing to load, which is
 *    the right answer for a track exported without altitude.
 *
 * What it does not do is validate. A malformed file yields whatever points
 * could be read, and [parseGpx] returns null only when there are too few to
 * ride. Callers get a route or an explanation, never an exception.
 */
object GpxParser {

    /**
     * Parse [xml] into a ride-ready [RouteTrack], or null if it holds fewer
     * than two usable points.
     *
     * [id] is the caller's stable identifier for the route (the asset name,
     * a content hash, a row id) and is what a resumed ride uses to find its
     * way back to the same track. [fallbackName] is used when the file has no
     * name of its own.
     */
    fun parseGpx(xml: String, id: String, fallbackName: String = "Route"): RouteTrack? {
        val points = parsePoints(xml)
        if (points.size < 2) return null
        val name = parseName(xml) ?: fallbackName
        val route = routeTrackOf(id = id, name = name, raw = points)
        return if (route.isEmpty) null else route
    }

    /** Every track point in document order, falling back to route points. */
    internal fun parsePoints(xml: String): List<RawRoutePoint> {
        val trackPoints = collectPoints(xml, "trkpt")
        if (trackPoints.size >= 2) return trackPoints
        val routePoints = collectPoints(xml, "rtept")
        return if (routePoints.size >= trackPoints.size) routePoints else trackPoints
    }

    /**
     * The route's name: the first `<name>` inside `<trk>` or `<rte>`, else the
     * one in `<metadata>`. Falls back to null so the caller decides.
     */
    internal fun parseName(xml: String): String? {
        for (container in listOf("trk", "rte", "metadata")) {
            val open = findTag(xml, container, 0) ?: continue
            val name = firstChildText(xml, "name", open.contentStart)
            if (!name.isNullOrBlank()) return name
        }
        return null
    }

    private fun collectPoints(xml: String, tagName: String): List<RawRoutePoint> {
        val out = ArrayList<RawRoutePoint>()
        var cursor = 0
        while (true) {
            val tag = findTag(xml, tagName, cursor) ?: break
            cursor = tag.contentStart
            val lat = attribute(tag.attributes, "lat")?.toDoubleOrNull()
            val lon = attribute(tag.attributes, "lon")?.toDoubleOrNull()
            if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite()) continue
            // Latitudes beyond the poles or longitudes beyond the antimeridian
            // are corrupt rather than exotic, and would throw the projection
            // and the distance maths off for the whole route.
            if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue

            val elevation = if (tag.selfClosing) {
                0.0
            } else {
                val bodyEnd = closingTagIndex(xml, tagName, tag.contentStart) ?: xml.length
                firstChildText(xml, "ele", tag.contentStart, bodyEnd)?.toDoubleOrNull() ?: 0.0
            }
            out.add(
                RawRoutePoint(
                    latitude = lat,
                    longitude = lon,
                    elevationMeters = if (elevation.isFinite()) elevation else 0.0,
                )
            )
        }
        return out
    }

    /** An opening tag we found: where its attributes are and where its body starts. */
    private class OpenTag(
        val attributes: String,
        val contentStart: Int,
        val selfClosing: Boolean,
    )

    /**
     * Find the next opening tag named [tagName] at or after [from], ignoring
     * any namespace prefix and any longer tag that merely starts with the same
     * letters (`trkpt` must not match `trkpts`, and `trk` must not match
     * `trkseg`).
     */
    private fun findTag(xml: String, tagName: String, from: Int, until: Int = xml.length): OpenTag? {
        var i = from
        while (i < until) {
            val open = xml.indexOf('<', i)
            // Past the caller's bound is the same as absent: a point's <ele>
            // search must not wander into the next point, and must not pay to
            // find out that it would have.
            if (open < 0 || open >= until || open + 1 >= xml.length) return null
            // Skip closing tags, comments, declarations and CDATA outright.
            val next = xml[open + 1]
            if (next == '/' || next == '!' || next == '?') {
                i = open + 2
                continue
            }
            var nameStart = open + 1
            // Step past a namespace prefix, e.g. the "gpx:" of "<gpx:trkpt".
            val tagEnd = xml.indexOf('>', open)
            if (tagEnd < 0) return null
            // Searched within this tag rather than with indexOf, which takes a
            // start but no end: it would scan to the end of the document and
            // only then be bounded by the check below. A GPX using no
            // namespace prefixes has no colon to stop it, so every tag
            // examined cost a scan of the whole remaining file and the parse
            // became quadratic in file size - a 40 KB route parsed in
            // milliseconds while a 500 KB one took minutes and read to a
            // rider as a download that never finished.
            var colon = -1
            var scan = nameStart + 1
            while (scan < tagEnd) {
                if (xml[scan] == ':') { colon = scan; break }
                scan++
            }
            if (colon > nameStart) {
                val prefixHasSpace = xml.substring(nameStart, colon).any { it.isWhitespace() }
                if (!prefixHasSpace) nameStart = colon + 1
            }
            val nameEnd = run {
                var k = nameStart
                while (k < tagEnd && !xml[k].isWhitespace() && xml[k] != '/' && xml[k] != '>') k++
                k
            }
            if (xml.regionMatches(nameStart, tagName, 0, tagName.length, ignoreCase = true) &&
                nameEnd - nameStart == tagName.length
            ) {
                // Self-closing means the last non-space character before ">"
                // is "/". Scanning back for any "/" would be fooled by one
                // inside an attribute value, e.g. a URL.
                var back = tagEnd - 1
                while (back > nameEnd && xml[back].isWhitespace()) back--
                val selfClosing = back > nameEnd && xml[back] == '/'
                return OpenTag(
                    attributes = xml.substring(nameEnd, tagEnd),
                    contentStart = tagEnd + 1,
                    selfClosing = selfClosing,
                )
            }
            i = tagEnd + 1
        }
        return null
    }

    /** Index of the matching closing tag, or null if the document is truncated. */
    private fun closingTagIndex(xml: String, tagName: String, from: Int): Int? {
        var i = from
        while (i < xml.length) {
            val close = xml.indexOf("</", i)
            if (close < 0) return null
            val tagEnd = xml.indexOf('>', close)
            if (tagEnd < 0) return null
            val inner = xml.substring(close + 2, tagEnd).trim().substringAfterLast(':')
            if (inner.equals(tagName, ignoreCase = true)) return close
            i = tagEnd + 1
        }
        return null
    }

    /**
     * Text content of the first [tagName] element starting at [from], stopping
     * at [limit] so a point's `<ele>` search can't wander into the next point.
     */
    private fun firstChildText(
        xml: String,
        tagName: String,
        from: Int,
        limit: Int = xml.length,
    ): String? {
        val tag = findTag(xml, tagName, from, limit) ?: return null
        if (tag.contentStart > limit) return null
        if (tag.selfClosing) return ""
        val end = xml.indexOf('<', tag.contentStart)
        if (end < 0) return null
        return decodeEntities(xml.substring(tag.contentStart, end)).trim()
    }

    /** Value of [name] in an attribute string, single or double quoted. */
    private fun attribute(attributes: String, name: String): String? {
        var i = 0
        while (i < attributes.length) {
            val at = attributes.indexOf(name, i, ignoreCase = true)
            if (at < 0) return null
            // Must be a whole attribute name: preceded by whitespace or the
            // start, and followed by "=" (allowing spaces around it). Without
            // this, "lon" would also match the tail of "minlon".
            val boundaryBefore = at == 0 || attributes[at - 1].isWhitespace()
            var k = at + name.length
            while (k < attributes.length && attributes[k].isWhitespace()) k++
            if (!boundaryBefore || k >= attributes.length || attributes[k] != '=') {
                i = at + name.length
                continue
            }
            k++
            while (k < attributes.length && attributes[k].isWhitespace()) k++
            if (k >= attributes.length) return null
            val quote = attributes[k]
            if (quote != '"' && quote != '\'') return null
            val end = attributes.indexOf(quote, k + 1)
            if (end < 0) return null
            return attributes.substring(k + 1, end)
        }
        return null
    }

    /** The five predefined XML entities. Route names are the only text we read. */
    private fun decodeEntities(text: String): String =
        if (text.indexOf('&') < 0) {
            text
        } else {
            text.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
        }
}

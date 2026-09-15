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
package app.indoorbike.shared.companion

import kotlin.math.abs
import kotlin.math.floor
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * What came off the wire.
 *
 * The three outcomes are deliberately distinct, because the protocol asks for
 * three different responses. [Unknown] is *ignored* - that is what lets a 0.1
 * peer share a socket with a 0.4 peer that has learned to say something new.
 * [Malformed] is fatal. Only [Ok] is a frame.
 */
sealed interface Decoded {
    /**
     * @param fields every top-level key that was present on the wire, so a
     *   caller can tell a field that was sent-and-ignored from one that was
     *   never sent at all. Deserialisation alone cannot answer that: an
     *   unhonoured field and an absent one both arrive as a default.
     * @param unreadable fields that were sent but could not be read, and were
     *   left out so the rest of the frame could be used, each with the reason.
     *   Only ever filled for `course` and `finish`; see
     *   [CompanionCodec.decodeClientFrame]. Nested fields are dotted, e.g.
     *   `summary.profile`.
     */
    data class Ok(
        val frame: ClientFrame,
        val fields: Set<String>,
        val unreadable: Map<String, String> = emptyMap(),
    ) : Decoded

    data class Unknown(val type: String) : Decoded

    data class Malformed(val reason: String) : Decoded
}

/**
 * Turns Companion frames into JSON and back.
 *
 * ## Why this dispatches by hand
 *
 * kotlinx.serialization will happily dispatch a sealed hierarchy on a
 * discriminator, but it throws on a discriminator it has not been told about.
 * The protocol requires the opposite - an unknown frame type is ignored, never
 * fatal - so the type is read out of a [JsonObject] here and each frame is
 * decoded on its own. The cost is one `when`; the payoff is that a companion
 * built against a later version can send frames this build has never heard of
 * and the socket stays up.
 *
 * `ignoreUnknownKeys` gives the same tolerance one level down, for fields. It
 * is the same setting, and the same reasoning, as the workout and manifest
 * parsers already use.
 */
object CompanionCodec {

    @OptIn(ExperimentalSerializationApi::class)
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // A field with no value is omitted rather than written as null, so
        // "no heart-rate strap" and "a heart rate of zero" stay different
        // things on the wire. This is the one experimental setting the codec
        // relies on, and the protocol is specified around it.
        explicitNulls = false
        isLenient = false
    }

    /**
     * Decode one text frame sent by a companion.
     *
     * Never throws. Anything that is not parseable JSON, is not an object, or
     * has no usable `t` comes back as [Decoded.Malformed] with a reason a human
     * can read, because that reason is what ends up in the close frame.
     *
     * ## Numbers are read for their value, not their spelling
     *
     * `921.0` is a whole number, and an integer field takes it. Godot's
     * `JSON.parse_string` turns every number into a float and `JSON.stringify`
     * writes a whole float with its `.0`, so a game that loads its own data
     * from JSON sends integers spelled that way without ever meaning to. This
     * was found in a real ride: a `finish` whose `profile` read
     * `[921.0, 919.0, ...]` was refused as malformed, the socket closed, the
     * ride never ended, and the next ride was saved on top of it. A number with
     * a genuine fraction is still refused where a count is expected: `steps:
     * 1.5` is a bug, not a spelling. Profile samples are the exception, rounded
     * to the metre, because they are measurements and a metre is all the
     * precision a 64-point elevation strip has.
     *
     * ## A `course` or `finish` is used even when part of it is not
     *
     * These two describe a ride rather than steer it, and refusing one whole
     * costs far more than the field that was wrong. A `finish` that is refused
     * does not end the ride; a `course` that is refused closes the socket in the
     * middle of it. So when one of them will not decode, each field is tried on
     * its own, the ones that cannot be read are left out and named in
     * [Decoded.Ok.unreadable], and the rest is used. Every field of both is
     * optional, which is what makes that sound. Every other frame is still
     * all-or-nothing.
     */
    fun decodeClientFrame(text: String): Decoded {
        val parsed = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return Decoded.Malformed("not valid JSON: " + (e.message ?: "parse failed"))
        }
        if (parsed !is JsonObject) return Decoded.Malformed("frame is not a JSON object")

        val type = parsed["t"]?.let {
            runCatching { it.jsonPrimitive.content }.getOrNull()
        } ?: return Decoded.Malformed("frame has no 't' discriminator")

        val fields = parsed.keys.toSet()

        val serializer: DeserializationStrategy<ClientFrame> = when (type) {
            "hello" -> HelloFrame.serializer()
            "world" -> WorldFrame.serializer()
            "requestShift" -> RequestShiftFrame.serializer()
            "label" -> LabelFrame.serializer()
            "course" -> CourseFrame.serializer()
            "controls" -> ControlsFrame.serializer()
            "drivetrain" -> DrivetrainFrame.serializer()
            "brand" -> BrandFrame.serializer()
            "pairRequest" -> PairRequestFrame.serializer()
            "pairProof" -> PairProofFrame.serializer()
            "setState" -> SetStateFrame.serializer()
            "finish" -> FinishFrame.serializer()
            "screenshot" -> ScreenshotFrame.serializer()
            "ping" -> PingFrame.serializer()
            else -> return Decoded.Unknown(type)
        }

        val root = roundedProfiles(type, wholeNumbers(parsed) as JsonObject)
        return try {
            Decoded.Ok(json.decodeFromJsonElement(serializer, root), fields)
        } catch (e: Exception) {
            salvage(type, root, fields) ?: Decoded.Malformed(
                "frame '" + type + "' could not be read: " + (e.message ?: "missing or wrong-typed field")
            )
        }
    }

    /** The largest magnitude at which every whole double is exactly a long. */
    private const val EXACT_WHOLE_LIMIT = 9_007_199_254_740_992.0

    /**
     * Every whole number spelled with a fraction or an exponent, respelled as
     * an integer. Strings are untouched: `"1.0"` is text.
     */
    private fun wholeNumbers(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { wholeNumbers(it.value) })
        is JsonArray -> JsonArray(element.map(::wholeNumbers))
        is JsonPrimitive -> {
            val content = element.content
            val spelledAsFraction = !element.isString && content.any { it == '.' || it == 'e' || it == 'E' }
            val value = if (spelledAsFraction) content.toDoubleOrNull() else null
            if (value != null && value.isFinite() && abs(value) <= EXACT_WHOLE_LIMIT &&
                value == floor(value)
            ) {
                JsonPrimitive(value.toLong())
            } else {
                element
            }
        }
    }

    /** `course.profile` and `finish.summary.profile`, each number rounded to the metre. */
    private fun roundedProfiles(type: String, root: JsonObject): JsonObject = when (type) {
        "course" -> roundedProfile(root)
        "finish" -> (root["summary"] as? JsonObject)
            ?.let { JsonObject(root + ("summary" to roundedProfile(it))) }
            ?: root
        else -> root
    }

    private fun roundedProfile(holder: JsonObject): JsonObject {
        val profile = holder["profile"] as? JsonArray ?: return holder
        return JsonObject(
            holder + ("profile" to JsonArray(
                profile.map { sample ->
                    val metres = (sample as? JsonPrimitive)
                        ?.takeUnless { it.isString }
                        ?.content?.toDoubleOrNull()
                        ?.takeIf { it.isFinite() && abs(it) <= EXACT_WHOLE_LIMIT }
                    if (metres != null) JsonPrimitive(kotlin.math.round(metres).toLong()) else sample
                }
            ))
        )
    }

    /**
     * The readable part of a `course` or `finish` that would not decode whole,
     * or null for any other frame, or if even that will not decode.
     */
    private fun salvage(type: String, root: JsonObject, fields: Set<String>): Decoded? {
        val unreadable = linkedMapOf<String, String>()
        val serializer: DeserializationStrategy<ClientFrame> = when (type) {
            "course" -> CourseFrame.serializer()
            "finish" -> FinishFrame.serializer()
            else -> return null
        }
        var body = root
        val summary = root["summary"]
        if (type == "finish" && summary is JsonObject) {
            // One level down first, so a readable shape survives a broken profile.
            val (kept, lost) = readableFields(summary, RideSummary.serializer())
            lost.forEach { (field, reason) -> unreadable["summary.$field"] = reason }
            body = JsonObject(root + ("summary" to kept))
        }
        val (kept, lost) = readableFields(body, serializer)
        unreadable.putAll(lost)
        return runCatching {
            Decoded.Ok(json.decodeFromJsonElement(serializer, kept), fields, unreadable)
        }.getOrNull()
    }

    /**
     * [obj] without the fields that cannot be read on their own, and why each
     * could not. Only sound for a type whose every field is optional, where an
     * object holding one field decodes exactly when that field is readable.
     */
    private fun readableFields(
        obj: JsonObject,
        serializer: DeserializationStrategy<*>,
    ): Pair<JsonObject, Map<String, String>> {
        val lost = linkedMapOf<String, String>()
        val kept = obj.filter { (field, value) ->
            if (field == "t") return@filter true
            val alone = runCatching {
                json.decodeFromJsonElement(serializer, JsonObject(mapOf(field to value)))
            }
            alone.exceptionOrNull()?.let { lost[field] = it.message ?: "wrong type" }
            alone.isSuccess
        }
        return JsonObject(kept) to lost
    }

    /**
     * Encode a frame the app is sending, rounded to wire precision.
     *
     * ## Why rounding belongs here rather than at every call site
     *
     * A gradient that has been through `coerceIn` is a double like
     * `0.06199999999999999`, which is a genuinely different double from `0.062`
     * and so serialises to fifteen characters where five would do. Left alone
     * it makes frames a variable size for no reason, and makes the wire much
     * harder to read while both ends of the protocol are still being written.
     *
     * Doing it on the way out means it cannot be forgotten by whichever part of
     * the app happens to build the frame, and it costs nothing: every value
     * here is already far beyond the precision the thing it describes actually
     * has. A trainer does not resolve a tenth of a newton, and nobody can feel
     * the fourth decimal of a gradient.
     */
    fun encode(frame: ServerFrame): String = when (frame) {
        is WelcomeFrame -> json.encodeToString(WelcomeFrame.serializer(), frame.rounded())
        is TelemetryFrame -> json.encodeToString(TelemetryFrame.serializer(), frame.rounded())
        is EventFrame -> json.encodeToString(EventFrame.serializer(), frame.rounded())
        is WarningFrame -> json.encodeToString(WarningFrame.serializer(), frame.rounded())
        is ErrorFrame -> json.encodeToString(ErrorFrame.serializer(), frame)
        is PongFrame -> json.encodeToString(PongFrame.serializer(), frame)
        is ScreenshotRequestFrame -> json.encodeToString(ScreenshotRequestFrame.serializer(), frame)
        is ChallengeFrame -> json.encodeToString(ChallengeFrame.serializer(), frame)
        is PairedFrame -> json.encodeToString(PairedFrame.serializer(), frame)
        is PairDeclinedFrame -> json.encodeToString(PairDeclinedFrame.serializer(), frame)
    }

    private fun TelemetryFrame.rounded() = copy(
        // Two decimals is a centimetre per second, which is far past anything a
        // companion can do with it and keeps the frame a predictable size.
        speedMps = speedMps?.let { round(it, 2) },
        loadFactor = round(loadFactor, 2),
        appliedGradient = round(appliedGradient, 4),
        trainerForceN = round(trainerForceN, 1),
        trainerGradePercent = round(trainerGradePercent, 1),
    )

    private fun WarningFrame.rounded() = copy(
        // Carries whatever field was clamped, so it takes the finest precision
        // any of them needs rather than guessing from the field name.
        sent = sent?.let { round(it, 4) },
        applied = applied?.let { round(it, 4) },
    )

    private fun EventFrame.rounded() = copy(
        gears = gears?.map { it.copy(ratio = round(it.ratio, 2), loadFactor = round(it.loadFactor, 2)) },
    )

    private fun WelcomeFrame.rounded() = copy(
        rider = rider.copy(massKg = round(rider.massKg, 1)),
        bike = bike.copy(massKg = round(bike.massKg, 1)),
        gears = gears.map {
            it.copy(ratio = round(it.ratio, 2), loadFactor = round(it.loadFactor, 2))
        },
        resume = resume?.let {
            it.copy(distanceM = round(it.distanceM, 1), ascentM = round(it.ascentM, 1))
        },
    )

    /**
     * Round to [decimals] places, mapping a value back onto the double that
     * prints shortest.
     *
     * A non-finite value becomes zero rather than `NaN` or `Infinity`: neither
     * is valid JSON, and a frame that fails to encode mid-ride is a worse
     * outcome than a zero the receiver can see is wrong.
     */
    private fun round(value: Double, decimals: Int): Double {
        if (value.isNaN() || value.isInfinite()) return 0.0
        var factor = 1.0
        repeat(decimals) { factor *= 10.0 }
        return kotlin.math.round(value * factor) / factor
    }
}

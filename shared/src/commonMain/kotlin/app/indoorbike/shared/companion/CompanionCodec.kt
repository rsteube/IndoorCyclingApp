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

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
     */
    data class Ok(val frame: ClientFrame, val fields: Set<String>) : Decoded

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
     */
    fun decodeClientFrame(text: String): Decoded {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return Decoded.Malformed("not valid JSON: " + (e.message ?: "parse failed"))
        }
        if (root !is JsonObject) return Decoded.Malformed("frame is not a JSON object")

        val type = root["t"]?.let {
            runCatching { it.jsonPrimitive.content }.getOrNull()
        } ?: return Decoded.Malformed("frame has no 't' discriminator")

        val fields = root.keys.toSet()

        return try {
            val frame: ClientFrame = when (type) {
                "hello" -> json.decodeFromJsonElement(HelloFrame.serializer(), root)
                "world" -> json.decodeFromJsonElement(WorldFrame.serializer(), root)
                "requestShift" -> json.decodeFromJsonElement(RequestShiftFrame.serializer(), root)
                "label" -> json.decodeFromJsonElement(LabelFrame.serializer(), root)
                "course" -> json.decodeFromJsonElement(CourseFrame.serializer(), root)
                "controls" -> json.decodeFromJsonElement(ControlsFrame.serializer(), root)
                "drivetrain" -> json.decodeFromJsonElement(DrivetrainFrame.serializer(), root)
                "brand" -> json.decodeFromJsonElement(BrandFrame.serializer(), root)
                "pairRequest" -> json.decodeFromJsonElement(PairRequestFrame.serializer(), root)
                "pairProof" -> json.decodeFromJsonElement(PairProofFrame.serializer(), root)
                "setState" -> json.decodeFromJsonElement(SetStateFrame.serializer(), root)
                "finish" -> json.decodeFromJsonElement(FinishFrame.serializer(), root)
                "ping" -> json.decodeFromJsonElement(PingFrame.serializer(), root)
                else -> return Decoded.Unknown(type)
            }
            Decoded.Ok(frame, fields)
        } catch (e: Exception) {
            Decoded.Malformed(
                "frame '" + type + "' could not be read: " + (e.message ?: "missing or wrong-typed field")
            )
        }
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

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

/**
 * The turns a junction option can be labelled with, in left-to-right order.
 *
 * A label and an accessibility name, never the arrow: the arrow is drawn at
 * the option's bearing, which says the same thing more exactly. A kind this
 * build has not heard of is kept as null and the bearing alone is drawn.
 */
enum class TurnKind(val wire: String, val label: String) {
    SHARP_LEFT("sharpLeft", "Sharp left"),
    LEFT("left", "Left"),
    SLIGHT_LEFT("slightLeft", "Slight left"),
    STRAIGHT("straight", "Straight on"),
    SLIGHT_RIGHT("slightRight", "Slight right"),
    RIGHT("right", "Right"),
    SHARP_RIGHT("sharpRight", "Sharp right");

    companion object {
        fun fromWire(value: String?): TurnKind? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The junction card, as the game last described it.
 *
 * Only ever what the game said. A tap sends `turnChosen` and changes nothing
 * here until the game answers with a frame selecting it: the card shows what
 * the ride is going to do, and the phone's guess at that would be wrong
 * exactly when it matters, after the commit point.
 */
data class RouteJunction(
    val junctionId: String,
    val seq: Long,
    val phase: Phase,
    /** Metres to the junction when the frame was sent. */
    val distanceM: Double,
    /** Metres until the choice is final; 0 once committed. */
    val commitInM: Double,
    /** Left to right as the rider sees them, 2 to 6. */
    val options: List<RouteOption>,
    val selectedOptionId: String,
    val selectedBy: SelectedBy,
) {
    enum class Phase { OPEN, COMMITTED, PASSED }
    enum class SelectedBy { DEFAULT, RIDER, NAVIGATION }

    /** A tap can still change what the ride does. */
    val isOpen: Boolean get() = phase == Phase.OPEN

    val selected: RouteOption? get() = options.firstOrNull { it.optionId == selectedOptionId }
}

data class RouteOption(
    val optionId: String,
    val bearingDeg: Double,
    val kind: TurnKind?,
    val roadName: String?,
    val roadClass: String?,
    val surface: String?,
    val trailGrade: Int?,
    val gradientAheadPct: Double?,
    val climbAheadM: Double?,
    val toNextJunctionM: Double?,
    val leadsTo: String?,
    val isDefault: Boolean,
    val isNavigation: Boolean,
) {
    /** What a screen reader says, and what is drawn when nothing else is known. */
    val spokenLabel: String
        get() = (kind?.label ?: CompanionRoute.turnLabelForBearing(bearingDeg)) +
            (roadName?.let { " onto $it" } ?: "")
}

/** Where the game is taking the rider. Null in the host's flow means nowhere. */
data class RouteNavigation(
    val placeId: String,
    val name: String,
    val distanceM: Double?,
    val etaS: Double?,
    val next: Instruction?,
) {
    data class Instruction(val distanceM: Double, val kind: TurnKind?, val roadName: String?)
}

data class RoutePlace(
    val placeId: String,
    val name: String,
    val kind: String?,
    val distanceM: Double?,
    val bearingDeg: Double?,
)

/**
 * The checks that turn the 0.12 frames into what the phone draws.
 *
 * Pure, so the session can apply them without a clock and the tests can reach
 * them without a socket. The game guarantees every option it offers is legal
 * and leads on; nothing here second-guesses the routing. What is checked is
 * the shape: a card the phone cannot draw honestly is refused whole and the
 * last good one stays, because a junction with no default, or two, is a card
 * that lies about what happens if the rider does nothing.
 */
object CompanionRoute {

    /** Every text field on these frames, in characters. The game trims too. */
    const val MAX_TEXT_CHARS = 48

    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 6

    /** `placesQuery.limit`: the default, and the most the game will send. */
    const val DEFAULT_PLACES_LIMIT = 20
    const val MAX_PLACES_LIMIT = 50

    /** How long typing must stop before a search goes out. */
    const val PLACES_DEBOUNCE_MS = 300L

    /**
     * Most `turnChosen` a second, and most `placesQuery`, and most
     * destination events between them. The game ignores the surplus.
     */
    const val MAX_ROUTE_EVENTS_PER_SECOND = 2

    sealed interface Checked<out T> {
        data class Ok<T>(val value: T) : Checked<T>

        /** [field] is what the warning names; [reason] says what was wrong. */
        data class Refused(val field: String, val reason: String) : Checked<Nothing>
    }

    fun junction(frame: JunctionFrame): Checked<RouteJunction> {
        val id = frame.junctionId?.takeIf { it.isNotBlank() }
            ?: return Checked.Refused("junctionId", "A junction needs a 'junctionId'.")
        val seq = frame.seq ?: return Checked.Refused("seq", "A junction needs a 'seq'.")
        val phase = when (frame.phase) {
            JunctionFrame.PHASE_OPEN -> RouteJunction.Phase.OPEN
            JunctionFrame.PHASE_COMMITTED -> RouteJunction.Phase.COMMITTED
            JunctionFrame.PHASE_PASSED -> RouteJunction.Phase.PASSED
            else -> return Checked.Refused(
                "phase",
                "Unknown junction phase '" + frame.phase + "'; expected 'open', 'committed' or 'passed'.",
            )
        }
        val distance = frame.distanceM?.takeIf { it.isFinite() }
            ?: return Checked.Refused("distanceM", "A junction needs a finite 'distanceM'.")
        val selectedBy = when (frame.selectedBy) {
            JunctionFrame.BY_DEFAULT -> RouteJunction.SelectedBy.DEFAULT
            JunctionFrame.BY_RIDER -> RouteJunction.SelectedBy.RIDER
            JunctionFrame.BY_NAVIGATION -> RouteJunction.SelectedBy.NAVIGATION
            else -> return Checked.Refused(
                "selectedBy",
                "Unknown 'selectedBy' '" + frame.selectedBy + "'; expected 'default', 'rider' or 'navigation'.",
            )
        }

        // A passed junction is only ever cleared, so its options do not matter.
        if (phase == RouteJunction.Phase.PASSED) {
            return Checked.Ok(
                RouteJunction(
                    junctionId = id, seq = seq, phase = phase, distanceM = distance, commitInM = 0.0,
                    options = emptyList(), selectedOptionId = frame.selectedOptionId.orEmpty(),
                    selectedBy = selectedBy,
                )
            )
        }

        if (frame.options.size !in MIN_OPTIONS..MAX_OPTIONS) {
            return Checked.Refused(
                "options",
                "A junction offers " + MIN_OPTIONS + " to " + MAX_OPTIONS + " options; this one had " +
                    frame.options.size + ". A junction with one way on is never sent.",
            )
        }
        val options = frame.options.map { option ->
            val optionId = option.optionId?.takeIf { it.isNotBlank() }
                ?: return Checked.Refused("options.optionId", "Every junction option needs an 'optionId'.")
            RouteOption(
                optionId = optionId,
                bearingDeg = option.bearingDeg.takeIf { it.isFinite() }?.coerceIn(-180.0, 180.0) ?: 0.0,
                kind = TurnKind.fromWire(option.kind),
                roadName = text(option.roadName),
                roadClass = text(option.roadClass),
                surface = text(option.surface),
                trailGrade = option.trailGrade?.takeIf { it in 0..6 },
                gradientAheadPct = option.gradientAheadPct?.takeIf { it.isFinite() },
                climbAheadM = option.climbAheadM?.takeIf { it.isFinite() },
                toNextJunctionM = option.toNextJunctionM?.takeIf { it.isFinite() },
                leadsTo = text(option.leadsTo),
                isDefault = option.isDefault,
                isNavigation = option.isNavigation,
            )
        }
        if (options.map { it.optionId }.toSet().size != options.size) {
            return Checked.Refused("options.optionId", "Two options at one junction share an 'optionId'.")
        }
        val defaults = options.count { it.isDefault }
        if (defaults != 1) {
            return Checked.Refused(
                "options.isDefault",
                "Exactly one option is the default, what the ride takes if nothing is chosen; this junction had " +
                    defaults + ".",
            )
        }
        if (options.count { it.isNavigation } > 1) {
            return Checked.Refused("options.isNavigation", "At most one option is on the way to the destination.")
        }
        val selected = frame.selectedOptionId
        if (selected == null || options.none { it.optionId == selected }) {
            return Checked.Refused(
                "selectedOptionId",
                "'selectedOptionId' must name one of the junction's options; got " +
                    (selected?.let { "'$it'" } ?: "none") + ".",
            )
        }
        return Checked.Ok(
            RouteJunction(
                junctionId = id,
                seq = seq,
                phase = phase,
                distanceM = distance,
                commitInM = if (phase == RouteJunction.Phase.COMMITTED) {
                    0.0
                } else {
                    frame.commitInM?.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
                },
                options = options,
                selectedOptionId = selected,
                selectedBy = selectedBy,
            )
        )
    }

    /**
     * What a `navigation` frame says to show, or null for nothing.
     *
     * Only a frame that is active and names a destination puts anything up;
     * an active one without a destination is refused, since there is nothing
     * to say the rider is being taken to.
     */
    fun navigation(frame: NavigationFrame): Checked<RouteNavigation?> {
        val active = frame.active
            ?: return Checked.Refused("active", "A navigation frame needs 'active'.")
        if (!active) return Checked.Ok(null)
        val destination = frame.destination
        val placeId = destination?.placeId?.takeIf { it.isNotBlank() }
            ?: return Checked.Refused(
                "destination",
                "An active navigation frame needs a 'destination' with a 'placeId'.",
            )
        return Checked.Ok(
            RouteNavigation(
                placeId = placeId,
                name = text(destination.name) ?: text(placeId) ?: placeId,
                distanceM = frame.distanceM?.takeIf { it.isFinite() && it >= 0.0 },
                etaS = frame.etaS?.takeIf { it.isFinite() && it >= 0.0 },
                next = frame.next?.let { next ->
                    next.distanceM?.takeIf { it.isFinite() }?.let { distance ->
                        RouteNavigation.Instruction(
                            distanceM = distance.coerceAtLeast(0.0),
                            kind = TurnKind.fromWire(next.kind),
                            roadName = text(next.roadName),
                        )
                    }
                },
            )
        )
    }

    /** The places worth listing. One without an id or a name is left out. */
    fun places(frame: PlacesFrame): List<RoutePlace> =
        frame.places.mapNotNull { place ->
            val id = place.placeId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = text(place.name) ?: return@mapNotNull null
            RoutePlace(
                placeId = id,
                name = name,
                kind = text(place.kind),
                distanceM = place.distanceM?.takeIf { it.isFinite() && it >= 0.0 },
                bearingDeg = place.bearingDeg?.takeIf { it.isFinite() }?.coerceIn(-180.0, 180.0),
            )
        }.take(MAX_PLACES_LIMIT)

    /** A label for an option whose `kind` this build does not know. */
    fun turnLabelForBearing(bearingDeg: Double): String = when {
        bearingDeg < -135 -> TurnKind.SHARP_LEFT.label
        bearingDeg < -45 -> TurnKind.LEFT.label
        bearingDeg < -15 -> TurnKind.SLIGHT_LEFT.label
        bearingDeg <= 15 -> TurnKind.STRAIGHT.label
        bearingDeg <= 45 -> TurnKind.SLIGHT_RIGHT.label
        bearingDeg <= 135 -> TurnKind.RIGHT.label
        else -> TurnKind.SHARP_RIGHT.label
    }

    private fun text(value: String?): String? = CompanionText.clean(value, MAX_TEXT_CHARS)
}

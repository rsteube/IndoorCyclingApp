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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TOKEN = "8F3K2M"

private val ROUTE_PHONE = CompanionProtocol.CAPABILITIES +
    CompanionProtocol.ROUTE_CHOICE_CAPABILITY + CompanionProtocol.NAVIGATION_CAPABILITY

private fun snapshot() = CompanionSnapshot(
    rider = RiderInfo(massKg = 78.0, ftpWatts = 250),
    bike = BikeInfo(massKg = 9.5, drivetrain = "road-double"),
    gears = listOf(GearInfo(0, 1.21, "34/28", 0.72)),
    gearIndex = 0,
    trainer = TrainerInfo(name = "KICKR CORE", connected = true, control = "sim"),
)

private fun hello(capabilities: String = """["routeChoice","navigation"]""") =
    """{"t":"hello","protoMin":"0.1","protoMax":"0.13","token":"$TOKEN","client":{"name":"Col de Berg"},""" +
        """"capabilities":$capabilities}"""

private fun session(
    phone: List<String> = ROUTE_PHONE,
    game: String = """["routeChoice","navigation"]""",
): Pair<CompanionSession, WelcomeFrame> {
    val s = CompanionSession(
        config = CompanionConfig(appVersion = "2.10.0", sessionId = "3f9a1c", token = TOKEN, capabilities = phone),
        snapshot = ::snapshot,
        rideState = { CompanionState.ACTIVE },
    )
    val welcome = s.onFrame(hello(game), nowMs = 0).sent().filterIsInstance<WelcomeFrame>().single()
    return s to welcome
}

private fun List<CompanionEffect>.sent(): List<ServerFrame> =
    filterIsInstance<CompanionEffect.Send>().map { it.frame }

private fun List<CompanionEffect>.warnings(): List<WarningFrame> = sent().filterIsInstance<WarningFrame>()

private fun List<CompanionEffect>.junction(): CompanionEffect.SetJunction? =
    filterIsInstance<CompanionEffect.SetJunction>().singleOrNull()

private fun option(id: String, bearing: Int, default: Boolean = false, navigation: Boolean = false, extra: String = "") =
    """{"optionId":"$id","bearingDeg":$bearing,"kind":"straight","roadClass":"residential","surface":"asphalt",""" +
        """"gradientAheadPct":0.4,"climbAheadM":2,"toNextJunctionM":180,"isDefault":$default,""" +
        """"isNavigation":$navigation$extra}"""

private fun junction(
    seq: Long,
    id: String = "j-1",
    phase: String = "open",
    selected: String = "b",
    by: String = "default",
    options: String = listOf(option("a", -84), option("b", 3, default = true), option("c", 71)).joinToString(","),
) = """{"t":"junction","junctionId":"$id","seq":$seq,"phase":"$phase","distanceM":342.0,"commitInM":132.0,""" +
    """"selectedOptionId":"$selected","selectedBy":"$by","options":[$options]}"""

/**
 * Companion protocol 0.12 and 0.13, the phone's side: a free ride, the junction card,
 * and being taken somewhere. Section 10 of the Col de Berg proposal, case by
 * case, plus the shape checks the phone adds.
 */
class CompanionFreeRideTest {

    // Negotiation ────────────────────────────────────────────────────────────

    @Test
    fun `0_13 is spoken, and route choice is listed only by a phone that draws it`() {
        val (_, plain) = session(phone = CompanionProtocol.CAPABILITIES)
        assertEquals("0.13", plain.proto)
        assertFalse(CompanionProtocol.ROUTE_CHOICE_CAPABILITY in plain.capabilities)
        assertFalse(CompanionProtocol.NAVIGATION_CAPABILITY in plain.capabilities)

        val (_, drawing) = session()
        assertTrue(CompanionProtocol.ROUTE_CHOICE_CAPABILITY in drawing.capabilities)
        assertTrue(CompanionProtocol.NAVIGATION_CAPABILITY in drawing.capabilities)
    }

    @Test
    fun `a junction reaches a phone without routeChoice as nothing at all`() {
        val (s, _) = session(phone = CompanionProtocol.CAPABILITIES)
        val effects = s.onFrame(junction(1), nowMs = 100)
        assertTrue(effects.isEmpty(), "got $effects")
        assertTrue(s.chooseTurn("j-1", "a", nowMs = 200).isEmpty())
    }

    @Test
    fun `nor is a turn sent to a game that did not list routeChoice`() {
        val (s, _) = session(game = "[]")
        assertFalse(s.choosesRoute)
        assertFalse(s.navigates)
        assertTrue(s.onFrame(junction(1), nowMs = 100).isEmpty())
    }

    // course with mode ───────────────────────────────────────────────────────

    @Test
    fun `a course with a mode replaces, and its nulls say unknown`() {
        val (s, _) = session()
        val effects = s.onFrame(
            """{"t":"course","mode":"freeRide","name":"München - Altstadt","world":"Col de Berg",""" +
                """"area":{"id":"muenchen-altstadt","name":"München - Altstadt","release":"2026.11.1"},""" +
                """"distanceM":null,"ascentM":null}""",
            nowMs = 100,
        )
        val set = effects.filterIsInstance<CompanionEffect.SetCourse>().single()
        assertTrue(set.replace)
        assertTrue(set.course.freeRide)
        assertTrue(set.course.mergedWith(CompanionCourse(shape = "abc")).freeRide, "an update keeps the mode")
        assertEquals("München - Altstadt", set.course.name)
        assertNull(set.course.distanceMeters)
        assertNull(set.course.ascentMeters)
    }

    @Test
    fun `a course without a mode merges, as before`() {
        val (s, _) = session()
        val set = s.onFrame("""{"t":"course","name":"Lap 2"}""", nowMs = 100)
            .filterIsInstance<CompanionEffect.SetCourse>().single()
        assertFalse(set.replace)
    }

    @Test
    fun `a course then a free ride - the free ride has no length`() {
        val engine = CompanionEngine(powerWatts = { 0 }, applyRoad = { }, riderMassKg = { 75f })
        engine.setCourse(CompanionCourse(name = "Volcano Circuit", distanceMeters = 12_000.0, ascentMeters = 300.0))
        engine.setCourse(CompanionCourse(name = "München - Altstadt", world = "Col de Berg"), replace = true)
        assertNull(engine.course.value.distanceMeters)
        assertNull(engine.course.value.ascentMeters)
        assertEquals("München - Altstadt", engine.course.value.name)
    }

    @Test
    fun `a free ride then a course - the course's length is shown`() {
        val engine = CompanionEngine(powerWatts = { 0 }, applyRoad = { }, riderMassKg = { 75f })
        engine.setCourse(CompanionCourse(name = "München - Altstadt"), replace = true)
        engine.setCourse(CompanionCourse(name = "Volcano Circuit", distanceMeters = 12_000.0), replace = true)
        assertEquals(12_000.0, engine.course.value.distanceMeters)
    }

    // junction ───────────────────────────────────────────────────────────────

    @Test
    fun `the spec's own junction is drawn as sent`() {
        val (s, _) = session()
        val shown = assertNotNull(
            s.onFrame(
                """{"t":"junction","junctionId":"j-4471-1","seq":12,"phase":"open","distanceM":342.0,""" +
                    """"commitInM":132.0,"selectedOptionId":"b","selectedBy":"default","options":[""" +
                    """{"optionId":"a","bearingDeg":-84,"kind":"left","roadName":"Rumfordstraße","roadClass":"residential",""" +
                    """"surface":"asphalt","gradientAheadPct":0.4,"climbAheadM":2,"toNextJunctionM":180,"isDefault":false,""" +
                    """"isNavigation":false},""" +
                    """{"optionId":"b","bearingDeg":3,"kind":"straight","roadName":"Reichenbachstraße","roadClass":"tertiary",""" +
                    """"surface":"asphalt","gradientAheadPct":-0.2,"climbAheadM":0,"toNextJunctionM":260,"leadsTo":"Isar",""" +
                    """"isDefault":true,"isNavigation":false},""" +
                    """{"optionId":"c","bearingDeg":71,"kind":"right","roadName":"Fraunhoferstraße","roadClass":"secondary",""" +
                    """"surface":"asphalt","gradientAheadPct":0.1,"climbAheadM":1,"toNextJunctionM":410,"isDefault":false,""" +
                    """"isNavigation":false}]}""",
                nowMs = 100,
            ).junction()?.junction
        )
        assertEquals("j-4471-1", shown.junctionId)
        assertEquals(listOf("a", "b", "c"), shown.options.map { it.optionId })
        assertEquals(TurnKind.LEFT, shown.options[0].kind)
        assertEquals("Isar", shown.selected?.leadsTo)
        assertEquals(RouteJunction.SelectedBy.DEFAULT, shown.selectedBy)
        assertEquals(132.0, shown.commitInM)
        assertEquals("Left onto Rumfordstraße", shown.options[0].spokenLabel)
    }

    @Test
    fun `a frame with a lower seq is dropped`() {
        val (s, _) = session()
        s.onFrame(junction(5), nowMs = 100)
        assertNull(s.onFrame(junction(4, selected = "a", by = "rider"), nowMs = 200).junction())
        assertEquals("b", s.junction?.selectedOptionId)
    }

    @Test
    fun `a new junctionId replaces the card`() {
        val (s, _) = session()
        s.onFrame(junction(5), nowMs = 100)
        val next = s.onFrame(junction(6, id = "j-2"), nowMs = 200).junction()?.junction
        assertEquals("j-2", next?.junctionId)
    }

    @Test
    fun `passed clears the card it describes and no other`() {
        val (s, _) = session()
        s.onFrame(junction(5, id = "j-2"), nowMs = 100)
        // A late passed for the junction before leaves the one coming up alone.
        assertNull(s.onFrame(junction(6, id = "j-1", phase = "passed"), nowMs = 150).junction())
        assertEquals("j-2", s.junction?.junctionId)

        val cleared = s.onFrame(junction(7, id = "j-2", phase = "passed"), nowMs = 200).junction()
        assertNotNull(cleared)
        assertNull(cleared.junction)
        assertNull(s.junction)
    }

    @Test
    fun `seq starts again with each ride`() {
        val (s, _) = session()
        s.onFrame(junction(40), nowMs = 100)
        val finished = s.onFrame("""{"t":"finish"}""", nowMs = 200)
        assertNull(assertNotNull(finished.junction()).junction, "the card goes with the ride")
        assertTrue(
            finished.indexOfFirst { it is CompanionEffect.SetJunction } <
                finished.indexOfFirst { it is CompanionEffect.Finish },
        )
        assertNotNull(s.onFrame(junction(1), nowMs = 300).junction()?.junction)

        s.onFrame(junction(9), nowMs = 400)
        s.onFrame("""{"t":"course","mode":"freeRide","name":"Isar"}""", nowMs = 500)
        assertNotNull(s.onFrame(junction(1, id = "j-9"), nowMs = 600).junction()?.junction)
    }

    @Test
    fun `a junction the phone cannot draw honestly is refused whole, said once`() {
        val (s, _) = session()
        s.onFrame(junction(1), nowMs = 100)
        val twoDefaults = listOf(option("a", -84, default = true), option("b", 3, default = true)).joinToString(",")
        val first = s.onFrame(junction(2, options = twoDefaults), nowMs = 200)
        assertNull(first.junction())
        assertEquals(listOf("options.isDefault"), first.warnings().map { it.field })
        assertEquals("junction", first.warnings().single().frame)
        // The last good card stays.
        assertEquals(1L, s.junction?.seq)
        assertTrue(s.onFrame(junction(3, options = twoDefaults), nowMs = 300).isEmpty(), "said once")
    }

    @Test
    fun `one option, seven, an unknown phase and a stray selection are each refused`() {
        // A session each: a refusal is said once per field per connection.
        fun refused(frame: String): String? {
            val (s, _) = session()
            val effects = s.onFrame(frame, nowMs = 100)
            assertNull(s.junction)
            return effects.warnings().single().field
        }
        val one = option("a", 0, default = true)
        val seven = (1..7).joinToString(",") { option("o$it", it * 10, default = it == 1) }
        assertEquals("options", refused(junction(1, options = one, selected = "a")))
        assertEquals("options", refused(junction(1, options = seven, selected = "o1")))
        assertEquals("phase", refused(junction(1, phase = "maybe")))
        assertEquals("selectedBy", refused(junction(1, by = "magic")))
        assertEquals("selectedOptionId", refused(junction(1, selected = "z")))
    }

    @Test
    fun `a junction missing a field costs the card, not the socket`() {
        val (s, _) = session()
        val effects = s.onFrame("""{"t":"junction","seq":1,"phase":"open"}""", nowMs = 100)
        assertTrue(effects.none { it is CompanionEffect.Close }, "got $effects")
        assertEquals("junctionId", effects.warnings().single().field)
    }

    @Test
    fun `text is trimmed to 48 characters and an unknown kind falls back to the bearing`() {
        val (s, _) = session()
        val long = "x".repeat(80)
        val options = listOf(
            option("a", -84, extra = ""","roadName":"$long","kind":"hairpin""""),
            option("b", 3, default = true),
        ).joinToString(",")
        // The later "kind" wins in the object, which is the point of the test.
        val shown = assertNotNull(s.onFrame(junction(1, options = options), nowMs = 100).junction()?.junction)
        assertEquals(CompanionRoute.MAX_TEXT_CHARS, shown.options[0].roadName?.length)
        assertNull(shown.options[0].kind)
        assertTrue(shown.options[0].spokenLabel.startsWith("Left"))
    }

    // turnChosen ─────────────────────────────────────────────────────────────

    @Test
    fun `a tap sends turnChosen and leaves the card to the game's answer`() {
        val (s, _) = session()
        s.onFrame(junction(1), nowMs = 100)
        val tap = s.chooseTurn("j-1", "a", nowMs = 200)
        val event = tap.sent().filterIsInstance<EventFrame>().single()
        assertEquals(EventFrame.TURN_CHOSEN, event.event)
        assertEquals("j-1", event.junctionId)
        assertEquals("a", event.optionId)
        assertTrue(tap.none { it is CompanionEffect.SetJunction }, "not optimistic")
        assertEquals("b", s.junction?.selectedOptionId)

        val answer = s.onFrame(junction(2, selected = "a", by = "rider"), nowMs = 250).junction()?.junction
        assertEquals("a", answer?.selectedOptionId)
        assertEquals(RouteJunction.SelectedBy.RIDER, answer?.selectedBy)
    }

    @Test
    fun `turnChosen goes out on the wire as the spec spells it`() {
        val (s, _) = session()
        s.onFrame(junction(1), nowMs = 100)
        val event = s.chooseTurn("j-1", "a", nowMs = 200).sent().single()
        assertEquals(
            """{"event":"turnChosen","junctionId":"j-1","optionId":"a","t":"event"}""",
            CompanionCodec.encode(event),
        )
    }

    @Test
    fun `no tap goes out once the junction is committed, or for another card`() {
        val (s, _) = session()
        s.onFrame(junction(1), nowMs = 100)
        assertTrue(s.chooseTurn("j-0", "a", nowMs = 200).isEmpty())
        assertTrue(s.chooseTurn("j-1", "z", nowMs = 200).isEmpty())
        s.onFrame(junction(2, phase = "committed"), nowMs = 300)
        assertEquals(0.0, s.junction?.commitInM)
        assertTrue(s.chooseTurn("j-1", "a", nowMs = 400).isEmpty())
    }

    @Test
    fun `at most two taps a second`() {
        val (s, _) = session()
        s.onFrame(junction(1), nowMs = 100)
        assertEquals(1, s.chooseTurn("j-1", "a", nowMs = 1_000).size)
        assertEquals(1, s.chooseTurn("j-1", "c", nowMs = 1_100).size)
        assertTrue(s.chooseTurn("j-1", "a", nowMs = 1_200).isEmpty())
        assertEquals(1, s.chooseTurn("j-1", "a", nowMs = 2_000).size)
    }

    // Navigation ─────────────────────────────────────────────────────────────

    private val navigating =
        """{"t":"navigation","active":true,"destination":{"placeId":"p-viktualienmarkt","name":"Viktualienmarkt"},""" +
            """"distanceM":2140,"etaS":371,"next":{"distanceM":342,"kind":"left","roadName":"Rumfordstraße"},""" +
            """"arrived":false}"""

    @Test
    fun `the spec's navigation frame is shown, and active false clears it`() {
        val (s, _) = session()
        val shown = assertNotNull(
            s.onFrame(navigating, nowMs = 100).filterIsInstance<CompanionEffect.SetNavigation>().single().navigation
        )
        assertEquals("Viktualienmarkt", shown.name)
        assertEquals(2140.0, shown.distanceM)
        assertEquals(371.0, shown.etaS)
        assertEquals(TurnKind.LEFT, shown.next?.kind)

        val cleared = s.onFrame("""{"t":"navigation","active":false}""", nowMs = 200)
        assertNull(cleared.filterIsInstance<CompanionEffect.SetNavigation>().single().navigation)
    }

    @Test
    fun `arrived is shown once`() {
        val (s, _) = session()
        val arrived = navigating.replace("\"arrived\":false", "\"arrived\":true")
        assertEquals(
            "Viktualienmarkt",
            s.onFrame(arrived, nowMs = 100).filterIsInstance<CompanionEffect.Arrived>().single().name,
        )
        assertTrue(s.onFrame(arrived, nowMs = 200).none { it is CompanionEffect.Arrived })
    }

    @Test
    fun `a finish clears navigation, and only when there was some`() {
        val (s, _) = session()
        assertTrue(s.onFrame("""{"t":"finish"}""", nowMs = 50).none { it is CompanionEffect.SetNavigation })
        s.onFrame(navigating, nowMs = 100)
        val finished = s.onFrame("""{"t":"finish"}""", nowMs = 200)
        assertNull(finished.filterIsInstance<CompanionEffect.SetNavigation>().single().navigation)
    }

    // Places ─────────────────────────────────────────────────────────────────

    @Test
    fun `a browse goes out numbered, with nothing typed, and only its answer is kept`() {
        val (s, _) = session()
        val first = s.browsePlaces(nowMs = 100).sent().filterIsInstance<EventFrame>().single()
        val second = s.browsePlaces(nowMs = 700).sent().filterIsInstance<EventFrame>().single()
        assertEquals(EventFrame.PLACES_QUERY, second.event)
        assertEquals("", second.query)
        assertEquals(CompanionRoute.MAX_PLACES_LIMIT, second.limit)

        val stale = s.onFrame(
            """{"t":"places","requestId":"${first.requestId}","places":[{"placeId":"p-1","name":"Old"}]}""",
            nowMs = 800,
        )
        assertTrue(stale.isEmpty(), "stale answer dropped")

        val answer = s.onFrame(
            """{"t":"places","requestId":"${second.requestId}","places":[""" +
                """{"placeId":"p-viktualienmarkt","name":"Viktualienmarkt","kind":"marketplace","distanceM":2140,"bearingDeg":-35},""" +
                """{"placeId":"","name":"No id"},""" +
                """{"placeId":"p-elisabethmarkt","name":"Elisabethmarkt","kind":"marketplace","distanceM":5310,"bearingDeg":12}]}""",
            nowMs = 900,
        ).filterIsInstance<CompanionEffect.SetPlaces>().single()
        assertEquals(listOf("Viktualienmarkt", "Elisabethmarkt"), answer.places.map { it.name })
    }

    @Test
    fun `destinations go out as events`() {
        val (s, _) = session()
        val chosen = s.chooseDestination("p-viktualienmarkt", nowMs = 100).sent().single() as EventFrame
        assertEquals(EventFrame.DESTINATION_CHOSEN, chosen.event)
        assertEquals("p-viktualienmarkt", chosen.placeId)
        val cleared = s.clearDestination(nowMs = 200).sent().single() as EventFrame
        assertEquals(EventFrame.DESTINATION_CLEARED, cleared.event)
        // The two share one allowance of two a second.
        assertTrue(s.chooseDestination("p-1", nowMs = 300).isEmpty())
    }

    @Test
    fun `no search goes to a game that did not list navigation`() {
        val (s, _) = session(game = """["routeChoice"]""")
        assertTrue(s.choosesRoute)
        assertTrue(s.browsePlaces(nowMs = 100).isEmpty())
        assertTrue(s.chooseDestination("p-1", nowMs = 100).isEmpty())
        assertTrue(s.onFrame(navigating, nowMs = 100).isEmpty())
    }

    // 0.13: categories ───────────────────────────────────────────────────────

    @Test
    fun `a category goes only to a game that sorts its places`() {
        val (sorting, _) = session(game = """["routeChoice","navigation","placeCategories"]""")
        assertTrue(sorting.sortsPlaces)
        val asked = sorting.browsePlaces(nowMs = 100, category = PlaceCategory.MONUMENTS)
            .sent().single() as EventFrame
        assertEquals("monuments", asked.category)
        assertEquals(
            """{"event":"placesQuery","requestId":"q1","query":"","limit":50,"category":"monuments","t":"event"}""",
            CompanionCodec.encode(asked),
        )

        // A 0.12 game is asked for everything rather than for a category it never heard of.
        val (plain, _) = session()
        assertFalse(plain.sortsPlaces)
        assertNull((plain.browsePlaces(nowMs = 100, category = PlaceCategory.FOOD).sent().single() as EventFrame).category)
    }

    @Test
    fun `a place keeps its category, and an unknown one is none`() {
        val (s, _) = session(game = """["navigation","placeCategories"]""")
        val id = (s.browsePlaces(nowMs = 100).sent().single() as EventFrame).requestId
        val places = s.onFrame(
            """{"t":"places","requestId":"$id","places":[""" +
                """{"placeId":"p-1","name":"Marienplatz","kind":"square","category":"squares"},""" +
                """{"placeId":"p-2","name":"Englischer Garten","kind":"park","category":"nature"},""" +
                """{"placeId":"p-3","name":"Isar","kind":"river","category":"rivers"}]}""",
            nowMs = 200,
        ).filterIsInstance<CompanionEffect.SetPlaces>().single().places
        assertEquals(listOf(PlaceCategory.SQUARES, PlaceCategory.NATURE, null), places.map { it.category })
    }

    @Test
    fun `never more than four categories`() {
        assertTrue(PlaceCategory.entries.size <= 4)
    }

    // 0.13: the junction diagram ─────────────────────────────────────────────

    @Test
    fun `a junction with points draws its diagram, and commitInS is kept`() {
        val (s, _) = session()
        val options = listOf(
            option("a", -84, extra = ""","points":[[0,0],[-12.5,1.2],[-40,3]]"""),
            option("b", 3, default = true, extra = ""","points":[[0,0],[0.4,20],[1.5,40]]"""),
        ).joinToString(",")
        val frame = junction(1, options = options).dropLast(1) +
            ""","commitInS":4.5,"approachPoints":[[0,-40],[0,0]]}"""
        val effects = s.onFrame(frame, nowMs = 100)
        val shown = assertNotNull(effects.junction()?.junction)
        assertTrue(shown.hasDiagram)
        assertEquals(RoutePoint(-12.5, 1.2), shown.options[0].points[1])
        assertEquals(listOf(RoutePoint(0.0, -40.0), RoutePoint(0.0, 0.0)), shown.approach)
        assertEquals(4.5, shown.commitInS)
        assertTrue(effects.warnings().isEmpty())
    }

    @Test
    fun `points that cannot be drawn cost the diagram, said once, and the card stays`() {
        val (s, _) = session()
        val tooMany = (0..16).joinToString(",") { "[0,$it]" }
        val options = listOf(
            option("a", -84, extra = ""","points":[$tooMany]"""),
            option("b", 3, default = true, extra = ""","points":[[0,0],[0,5,1]]"""),
        ).joinToString(",")
        val first = s.onFrame(junction(1, options = options), nowMs = 100)
        val shown = assertNotNull(first.junction()?.junction)
        assertFalse(shown.hasDiagram)
        assertEquals(listOf("options.points"), first.warnings().map { it.field })
        assertTrue(s.onFrame(junction(2, options = options), nowMs = 200).warnings().isEmpty(), "said once")
    }

    @Test
    fun `a mistyped junction field never closes the socket`() {
        val (s, _) = session()
        val options = listOf(
            option("a", -84, extra = ""","points":[["left","up"]]"""),
            option("b", 3, default = true),
        ).joinToString(",")
        val effects = s.onFrame(junction(1, options = options), nowMs = 100)
        assertTrue(effects.none { it is CompanionEffect.Close }, "got $effects")
        assertTrue(effects.warnings().isNotEmpty())
        // And it is said once, not at the two frames a second junctions come at.
        assertTrue(s.onFrame(junction(2, options = options), nowMs = 600).warnings().isEmpty())
    }
}

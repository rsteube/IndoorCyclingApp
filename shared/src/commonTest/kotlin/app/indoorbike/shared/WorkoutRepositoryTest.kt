package app.indoorbike.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkoutRepositoryTest {

    private val sampleA = """
        [
          {
            "id": "first-spin",
            "name": "First Spin",
            "description": "Easy intro ride",
            "segments": [
              { "lengthInSeconds": 300, "powerPercentFTP": 50, "intervalType": "Warmup" },
              { "lengthInSeconds": 600, "powerPercentFTP": 70, "intervalType": "Steady" }
            ]
          }
        ]
    """.trimIndent()

    private val sampleB = """
        [
          {
            "id": "threshold-30",
            "name": "Threshold 30",
            "description": null,
            "segments": [
              { "lengthInSeconds": 1800, "powerPercentFTP": 95, "intervalType": "Threshold" }
            ]
          }
        ]
    """.trimIndent()

    @Test
    fun parsesSingleSourceIntoListOfWorkouts() {
        val workouts = WorkoutRepository.parseWorkouts(listOf(sampleA))
        assertEquals(1, workouts.size)
        val w = workouts[0]
        assertEquals("first-spin", w.id)
        assertEquals("First Spin", w.name)
        assertEquals(2, w.segments.size)
        assertEquals(50, w.segments[0].powerPercentFTP)
    }

    @Test
    fun mergesMultipleSourcesPreservingOrder() {
        val workouts = WorkoutRepository.parseWorkouts(listOf(sampleA, sampleB))
        assertEquals(2, workouts.size)
        assertEquals("first-spin", workouts[0].id)
        assertEquals("threshold-30", workouts[1].id)
    }

    @Test
    fun deduplicatesByIdFirstWins() {
        val duplicateA = """
            [
              {
                "id": "first-spin",
                "name": "Different Name",
                "description": "Different description",
                "segments": [
                  { "lengthInSeconds": 60, "powerPercentFTP": 200, "intervalType": "Sprint" }
                ]
              }
            ]
        """.trimIndent()
        val workouts = WorkoutRepository.parseWorkouts(listOf(sampleA, duplicateA))
        assertEquals(1, workouts.size)
        // First-occurrence wins.
        assertEquals("First Spin", workouts[0].name)
        assertEquals(2, workouts[0].segments.size)
    }

    @Test
    fun malformedJsonInOneSourceDoesNotKillOthers() {
        val malformed = "this is not json"
        val workouts = WorkoutRepository.parseWorkouts(listOf(malformed, sampleA, "{also broken"))
        assertEquals(1, workouts.size)
        assertEquals("first-spin", workouts[0].id)
    }

    @Test
    fun emptyInputReturnsEmptyList() {
        assertTrue(WorkoutRepository.parseWorkouts(emptyList()).isEmpty())
        assertTrue(WorkoutRepository.parseWorkouts(listOf("[]")).isEmpty())
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val withExtras = """
            [
              {
                "id": "x",
                "name": "X",
                "description": "ok",
                "futureField": "should-be-ignored",
                "segments": [
                  { "lengthInSeconds": 60, "powerPercentFTP": 70, "intervalType": "Steady", "trailingExtra": 1 }
                ]
              }
            ]
        """.trimIndent()
        val workouts = WorkoutRepository.parseWorkouts(listOf(withExtras))
        assertEquals(1, workouts.size)
        assertEquals("x", workouts[0].id)
    }

    @Test
    fun optionalScreenshotAndUrlAreNullable() {
        val withOptional = """
            [
              {
                "id": "a",
                "name": "A",
                "description": "without optional fields",
                "segments": [
                  { "lengthInSeconds": 60, "powerPercentFTP": 70, "intervalType": "Steady" }
                ]
              },
              {
                "id": "b",
                "name": "B",
                "description": "with optional fields",
                "screenshot": "b.png",
                "url": "https://example/b",
                "segments": [
                  { "lengthInSeconds": 60, "powerPercentFTP": 70, "intervalType": "Steady" }
                ]
              }
            ]
        """.trimIndent()
        val workouts = WorkoutRepository.parseWorkouts(listOf(withOptional))
        assertEquals(2, workouts.size)
        assertEquals(null, workouts[0].screenshot)
        assertEquals(null, workouts[0].url)
        assertEquals("b.png", workouts[1].screenshot)
        assertEquals("https://example/b", workouts[1].url)
    }

    @Test
    fun emptySegmentsListIsAccepted() {
        // Edge-case data file shouldn't crash the parser; downstream code gates on segments.
        val empty = """
            [
              {
                "id": "skeleton",
                "name": "Skeleton",
                "description": null,
                "segments": []
              }
            ]
        """.trimIndent()
        val workouts = WorkoutRepository.parseWorkouts(listOf(empty))
        assertEquals(1, workouts.size)
        assertTrue(workouts[0].segments.isEmpty())
    }
}

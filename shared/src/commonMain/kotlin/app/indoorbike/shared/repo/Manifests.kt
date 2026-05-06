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
package app.indoorbike.shared.repo

import app.indoorbike.shared.Workout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * On-disk format for a workout repository's `indoorbike.json` manifest. Hosted
 * over HTTPS at any URL — GitHub raw, gh-pages, plain web folder, anywhere.
 *
 * Workouts are embedded inline so subscribing only takes one HTTP request.
 */
@Serializable
data class RepositoryManifest(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val description: String? = null,
    val author: String? = null,
    val homepage: String? = null,
    val updatedEpoch: Long? = null,
    val workouts: List<Workout> = emptyList(),
)

/**
 * Top-level community registry served at the well-known URL (default
 * `https://indoorbike.app/registry.json`). The app fetches it lazily — when
 * absent or unreachable, browse just shows an empty state.
 */
@Serializable
data class RegistryIndex(
    val schemaVersion: Int = 1,
    val updatedEpoch: Long? = null,
    val repositories: List<RegistryEntry> = emptyList(),
)

@Serializable
data class RegistryEntry(
    val id: String,
    val name: String,
    val description: String? = null,
    val author: String? = null,
    val manifestUrl: String,
    val tags: List<String> = emptyList(),
    val homepage: String? = null,
)

object ManifestParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseRepositoryManifest(text: String): RepositoryManifest =
        json.decodeFromString(RepositoryManifest.serializer(), text)

    fun parseRegistryIndex(text: String): RegistryIndex =
        json.decodeFromString(RegistryIndex.serializer(), text)

    fun parseSingleWorkout(text: String): Workout =
        json.decodeFromString(Workout.serializer(), text)

    /**
     * Heuristic: does this JSON look like a manifest, a registry, or a single
     * workout? Used by the universal import flow so the user doesn't have to
     * pick the right mode up front.
     */
    fun classify(text: String): ImportKind {
        // Cheap pre-checks first.
        val trimmed = text.trimStart()
        if (trimmed.startsWith("[")) return ImportKind.WorkoutList
        return when {
            "\"repositories\"" in text -> ImportKind.Registry
            "\"workouts\"" in text && "\"manifestUrl\"" !in text -> ImportKind.RepositoryManifest
            "\"segments\"" in text -> ImportKind.SingleWorkout
            else -> ImportKind.Unknown
        }
    }

    fun encodeRepositoryManifest(manifest: RepositoryManifest): String =
        json.encodeToString(RepositoryManifest.serializer(), manifest)
}

enum class ImportKind {
    SingleWorkout, WorkoutList, RepositoryManifest, Registry, Unknown,
}

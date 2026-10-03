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

import app.indoorbike.shared.Workout
import app.indoorbike.shared.library.ImportQuestions
import app.indoorbike.shared.library.ImportResult
import app.indoorbike.shared.repo.FollowedRepo
import app.indoorbike.shared.repo.ManifestParser
import app.indoorbike.shared.repo.RegistryEntry
import app.indoorbike.shared.repo.RepoClient
import app.indoorbike.shared.repo.RepoFetchResult
import app.indoorbike.shared.repo.RouteEntry
import app.indoorbike.shared.repo.RouteFetchResult
import app.indoorbike.shared.repo.WorkoutLinkParser
import app.indoorbike.shared.util.Log
import app.indoorbike.shared.util.PosixFiles
import app.indoorbike.shared.util.getCurrentEpochMillis
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The workout library beyond the bundled one: importing a file or a link, the
 * community registry, a repository's workouts and routes, and the
 * repositories the rider follows, each with what is new in it. The rules are
 * shared/repo's and shared/library's; this is their bus.
 */
internal fun SailfishCore.installLibrary() {
    // What a repository listed, by its manifest's address, so adding one needs no second fetch.
    val repoWorkouts = HashMap<String, List<Workout>>()
    val repoRoutes = HashMap<String, List<RouteEntry>>()
    var registry: List<RegistryEntry> = emptyList()

    bus.publish(
        "followed",
        combine(followedRepositories.repos, followedRepositories.status, followedRepositories.refreshing) { repos, status, refreshing ->
            Triple(repos, status, refreshing)
        },
    ) { (repos, status, refreshing) ->
        buildJsonObject {
            put("refreshing", refreshing)
            put("totalNew", status.values.sumOf { it.total })
            put("repos", buildJsonArray {
                repos.forEach { repo ->
                    val s = status[repo.id]
                    add(buildJsonObject {
                        put("id", repo.id)
                        put("name", repo.name)
                        put("manifestUrl", repo.manifestUrl)
                        put("author", repo.author)
                        put("homepage", repo.homepage)
                        put("newWorkouts", s?.newWorkouts ?: 0)
                        put("newRoutes", s?.newRoutes ?: 0)
                    })
                }
            })
        }
    }

    // An import that would replace one of the rider's workouts asks first; QML answers.
    var question: ImportQuestions.Question? = null
    bus.publish("importQuestion", workoutLibrary.questions.pending) { pending ->
        question = pending
        if (pending == null) JsonNull else buildJsonObject {
            put("names", JsonArray(pending.names.map(::JsonPrimitive)))
        }
    }
    bus.command("import.answer") { args ->
        val asked = question ?: throw UserFacing("There is no question to answer.")
        val answer = when (args.requireString("answer")) {
            "replace" -> ImportQuestions.Answer.REPLACE
            "keepBoth" -> ImportQuestions.Answer.KEEP_BOTH
            else -> ImportQuestions.Answer.CANCEL
        }
        workoutLibrary.questions.answer(asked, answer)
        JsonNull
    }

    /** The community registry, fresh, or the copy kept from last time when that fails. */
    bus.request("repo.registry") {
        val cache = SailfishPaths.cache(REGISTRY_CACHE)
        val (index, error) = try {
            val text = LinuxHttp.getText(REGISTRY_URL)
            runCatching { PosixFiles.writeTextAtomically(cache, text) }
            ManifestParser.parseRegistryIndex(text) to null
        } catch (t: Throwable) {
            Log.w(TAG, "Registry refresh failed: ${t.message}")
            val cached = runCatching { PosixFiles.readText(cache)?.let(ManifestParser::parseRegistryIndex) }.getOrNull()
            cached to (t.message ?: "Couldn't reach the registry")
        }
        registry = index?.repositories.orEmpty()
        buildJsonObject {
            put("error", error)
            put("repositories", buildJsonArray {
                registry.forEach { entry ->
                    add(buildJsonObject {
                        put("id", entry.id)
                        put("name", entry.name)
                        put("description", entry.description)
                        put("author", entry.author)
                        put("manifestUrl", entry.manifestUrl)
                        put("homepage", entry.homepage)
                        put("followed", followedRepositories.isFollowed(entry.id))
                    })
                }
            })
        }
    }

    bus.request("repo.workouts") { args ->
        val url = args.requireString("url")
        when (val result = RepoClient.fetchRepoWorkouts(url, LinuxHttp.textFetcher)) {
            is RepoFetchResult.Failure -> throw UserFacing(result.message)
            is RepoFetchResult.Ok -> {
                repoWorkouts[url] = result.workouts
                val mine = workoutLibrary.entries.value.map { it.workout.id }.toSet()
                buildJsonObject {
                    put("repoId", result.manifest.id)
                    put("name", result.manifest.name)
                    put("description", result.manifest.description)
                    put("workouts", buildJsonArray {
                        result.workouts.forEach { w ->
                            add(JsonObject(encodeWorkout(w, userSettings.ftp.value) + ("have" to JsonPrimitive(w.id in mine))))
                        }
                    })
                }
            }
        }
    }

    bus.request("repo.addWorkout") { args ->
        val url = args.requireString("url")
        val workout = repoWorkouts[url]?.firstOrNull { it.id == args.requireString("id") }
            ?: throw UserFacing("That workout is no longer listed. Open the repository again.")
        workoutLibrary.addFromRepo(workout, args.requireString("repoId"), args.string("repoName") ?: "")
        JsonNull
    }

    bus.request("repo.routes") { args ->
        val url = args.requireString("url")
        when (val result = RepoClient.fetchRepoRoutes(url, LinuxHttp.textFetcher)) {
            is RouteFetchResult.Failure -> throw UserFacing(result.message)
            is RouteFetchResult.Ok -> {
                repoRoutes[url] = result.routes
                val mine = routeLibrary.routes.value.map { it.id }.toSet()
                buildJsonObject {
                    put("repoId", result.manifest.id)
                    put("name", result.manifest.name)
                    put("routes", buildJsonArray {
                        result.routes.forEach { r ->
                            add(buildJsonObject {
                                put("id", r.id)
                                put("name", r.name)
                                put("description", r.description)
                                put("place", r.place)
                                put("distanceMeters", r.distanceMeters)
                                put("ascentMeters", r.ascentMeters)
                                put("have", r.id in mine)
                            })
                        }
                    })
                }
            }
        }
    }

    bus.request("repo.addRoute") { args ->
        val url = args.requireString("url")
        val entry = repoRoutes[url]?.firstOrNull { it.id == args.requireString("id") }
            ?: throw UserFacing("That route is no longer listed. Open the repository again.")
        when (val result = routeLibrary.addFromRepo(entry, args.string("repoId"), args.string("repoName"))) {
            is RouteImportResult.Added -> JsonPrimitive(result.track.id)
            is RouteImportResult.Failure -> throw UserFacing(result.message)
        }
    }

    bus.command("repo.follow") { args ->
        val entry = registry.firstOrNull { it.id == args.requireString("id") }
            ?: throw UserFacing("That repository is not in the registry any more.")
        followedRepositories.follow(entry)
        scope.launch { followedRepositories.refresh(force = true) }
        JsonNull
    }
    /** Follow a repository by its manifest's address; the answer is null, or what went wrong. */
    bus.request("repo.followUrl") { args ->
        val url = args.requireString("url").trim()
        if (!url.startsWith("https://", ignoreCase = true)) throw UserFacing("Repository addresses have to be https://")
        val manifest = try {
            RepoClient.fetchManifest(url, LinuxHttp.textFetcher)
        } catch (t: Throwable) {
            throw UserFacing(t.message ?: "Could not read that repository")
        }
        if (followedRepositories.isFollowed(manifest.id)) throw UserFacing("You already follow \"${manifest.name}\".")
        followedRepositories.follow(
            FollowedRepo(
                id = manifest.id,
                name = manifest.name,
                manifestUrl = url,
                author = manifest.author,
                homepage = manifest.homepage,
                addedEpoch = getCurrentEpochMillis(),
            ),
        )
        followedRepositories.refresh(force = true)
        JsonPrimitive(manifest.name)
    }
    bus.command("repo.unfollow") { args ->
        followedRepositories.unfollow(args.requireString("id"))
        JsonNull
    }
    bus.command("repo.markSeen") { args ->
        followedRepositories.markSeen(args.requireString("id"))
        JsonNull
    }
    bus.command("repo.refresh") { args ->
        scope.launch { followedRepositories.refresh(args.bool("force") ?: false) }
        JsonNull
    }

    /**
     * Import from a link: a share link or indoorbike://import carrying the
     * workout itself, or one pointing at an https file to fetch. What a QR code
     * holds, what a tapped link opens, and what the rider pastes. As on iOS.
     */
    bus.request("workouts.importLink") { args ->
        val link = args.requireString("link").trim()
        val embedded = WorkoutLinkParser.extractEmbeddedJson(link)
        val result = if (embedded != null) {
            workoutLibrary.importJsonText(embedded)
        } else {
            val url = if (link.startsWith("indoorbike://", ignoreCase = true)) {
                link.substringAfter("url=", "").substringBefore('&').trim()
            } else {
                link
            }
            when {
                url.isEmpty() -> ImportResult.Failure("That link has no workout in it.")
                !url.startsWith("https://", ignoreCase = true) -> ImportResult.Failure("That link didn't contain a workout or an https address.")
                else -> try {
                    workoutLibrary.importJsonText(LinuxHttp.getText(url))
                } catch (t: Throwable) {
                    ImportResult.Failure("Couldn't fetch: ${t.message}")
                }
            }
        }
        encodeImport(result)
    }
    bus.request("workouts.importFile") { args ->
        val bytes = PosixFiles.readBytes(args.requireString("path")) ?: throw UserFacing("That file could not be read.")
        if (bytes.size > LinuxHttp.MAX_BODY_BYTES) throw UserFacing("That file is too large to be a workout.")
        encodeImport(workoutLibrary.importJsonText(bytes.decodeToString()))
    }
    bus.request("workouts.delete") { args -> JsonPrimitive(workoutLibrary.delete(args.requireString("id"))) }
    bus.request("workouts.rename") { args ->
        val renamed = workoutLibrary.rename(args.requireString("id"), args.requireString("name"))
            ?: throw UserFacing("That workout could not be renamed.")
        JsonPrimitive(renamed.id)
    }

    scope.launch { followedRepositories.refresh(force = false) }
}

/** What an import came to, for the page that asked: the workouts added, a repository to open, or why not. */
private fun encodeImport(result: ImportResult): JsonElement = when (result) {
    is ImportResult.Workouts -> buildJsonObject {
        put("kind", "workouts")
        put("ids", JsonArray(result.workouts.map { JsonPrimitive(it.id) }))
        put("names", JsonArray(result.workouts.map { JsonPrimitive(it.name) }))
    }
    is ImportResult.Manifest -> buildJsonObject {
        put("kind", "repository")
        put("name", result.manifest.name)
    }
    is ImportResult.Cancelled -> buildJsonObject { put("kind", "cancelled") }
    is ImportResult.Failure -> throw UserFacing(result.message)
}

private const val TAG = "Library"

/** The community registry, as the Android build points at it. */
private const val REGISTRY_URL = "https://indoorbike.app/registry.json"
private const val REGISTRY_CACHE = "registry.json"

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
package app.indoorbike.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.LocalAppContainer
import app.indoorbike.library.FollowedRepo
import app.indoorbike.library.HttpFetcher
import app.indoorbike.library.RegistryState
import app.indoorbike.library.RepoStatus
import app.indoorbike.shared.repo.ManifestParser
import app.indoorbike.shared.repo.RegistryEntry
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.marginRidges
import app.indoorbike.ui.components.ContentMaxWidth
import app.indoorbike.ui.components.rememberCollapsingHeaderState
import app.indoorbike.ui.components.listBottomPadding
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Manage the repositories the rider follows.
 *
 * Following is about being told, not about being given: a followed repository
 * is checked for new workouts and routes, and nothing it publishes reaches the
 * library until the rider opens it and taps Add. That distinction is why
 * subscriptions could come back at all, having been removed once for letting a
 * maintainer change what was on someone's device.
 *
 * Two ways in. The community registry lists repositories that have been
 * submitted upstream, which is the discoverable path; the URL field takes any
 * manifest at all, which is the one that matters for a coach handing a link to
 * a training group, or for testing your own before submitting it.
 */
@Composable
fun RepositoriesScreen(
    onBack: () -> Unit,
    onOpenRepo: (manifestUrl: String) -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val followed by container.followedRepositories.repos.collectAsState()
    val status by container.followedRepositories.status.collectAsState()
    val refreshing by container.followedRepositories.refreshing.collectAsState()
    val registry by container.repositoryManager.registry.collectAsState()

    var url by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }

    // Always refetch, rather than only when there is no cache at all. This is
    // the screen whose entire job is finding repositories, and a cache written
    // once is a list frozen on the day it was written: a repository added to
    // the registry afterwards would never appear, with nothing on screen to
    // suggest anything was missing. The registry is about a kilobyte, and the
    // cache stays as the offline fallback.
    LaunchedEffect(Unit) {
        container.repositoryManager.refreshRegistry()
    }

    val followedIds = remember(followed) { followed.mapTo(mutableSetOf()) { it.id } }
    val suggestions = remember(registry, followedIds) {
        val index = (registry as? RegistryState.Loaded)?.index
            ?: (registry as? RegistryState.Failed)?.cachedIndex
        index?.repositories.orEmpty().filterNot { it.id in followedIds }
    }

    val header = rememberCollapsingHeaderState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CadenceColors.Bg0)
            .marginRidges()
            .nestedScroll(header.nestedScrollConnection)
    ) {
        AppBar(
            header = header,
            title = "Repositories",
            eyebrow = "Community",
            leading = { IconBtn(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onBack) },
            trailing = {
                if (refreshing) {
                    CircularProgressIndicator(
                        color = CadenceColors.Fg1,
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconBtn(
                        Icons.Outlined.Refresh,
                        "Check for new items",
                        onClick = {
                            scope.launch {
                                container.repositoryManager.refreshRegistry()
                                container.followedRepositories.refresh(force = true)
                            }
                        },
                    )
                }
            },
        )

        LazyColumn(
            // Capped and left aligned, matching the screen title above it.
            modifier = Modifier.imePadding().widthIn(max = ContentMaxWidth),
            contentPadding = PaddingValues(
                start = Spacing.s4,
                end = Spacing.s4,
                top = Spacing.s4,
                bottom = listBottomPadding(Spacing.s4)
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.s3),
        ) {
            item(key = "following-head") {
                Text(
                    if (followed.isEmpty()) "Not following anything yet" else "Following",
                    style = CadenceType.eyebrow,
                    color = CadenceColors.Fg3,
                )
            }

            if (followed.isEmpty()) {
                item(key = "following-empty") {
                    Text(
                        "Follow a repository and the app checks it for new workouts and roads. " +
                            "Nothing is added to your library until you pick it.",
                        style = CadenceType.small,
                        color = CadenceColors.Fg3,
                    )
                }
            } else {
                items(followed, key = { it.id }) { repo ->
                    FollowedRow(
                        repo = repo,
                        status = status[repo.id],
                        onOpen = { onOpenRepo(repo.manifestUrl) },
                        onUnfollow = { container.followedRepositories.unfollow(repo.id) },
                    )
                }
            }

            item(key = "add-head") {
                Spacer(Modifier.height(Spacing.s2))
                Text("ADD BY URL", style = CadenceType.eyebrow, color = CadenceColors.Fg3)
            }
            item(key = "add-url") {
                AddByUrlRow(
                    url = url,
                    busy = adding,
                    onUrlChange = { url = it },
                    onAdd = {
                        val trimmed = url.trim()
                        if (trimmed.isEmpty()) return@AddByUrlRow
                        adding = true
                        scope.launch {
                            val message = followByUrl(container, trimmed)
                            adding = false
                            if (message == null) {
                                url = ""
                                container.followedRepositories.refresh(force = true)
                            } else {
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                )
            }

            if (suggestions.isNotEmpty()) {
                item(key = "suggest-head") {
                    Spacer(Modifier.height(Spacing.s2))
                    Text("From the community", style = CadenceType.eyebrow, color = CadenceColors.Fg3)
                }
                items(suggestions, key = { "s-${it.id}" }) { entry ->
                    SuggestionRow(
                        entry = entry,
                        onFollow = {
                            container.followedRepositories.follow(entry)
                            scope.launch { container.followedRepositories.refresh(force = true) }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Follows the repository at [manifestUrl]. Returns null on success, or a
 * message explaining why not.
 *
 * The manifest is fetched before anything is stored, so a typo or a dead host
 * fails here rather than becoming a permanently broken row that only reveals
 * itself on the next refresh.
 */
private suspend fun followByUrl(
    container: app.indoorbike.AppContainer,
    manifestUrl: String,
): String? = try {
    if (!manifestUrl.startsWith("https://", ignoreCase = true)) {
        "Repository URLs have to be https://"
    } else {
        val manifest = ManifestParser.parseRepositoryManifest(HttpFetcher.getString(manifestUrl))
        when {
            container.followedRepositories.isFollowed(manifest.id) ->
                "You already follow \"${manifest.name}\"."
            else -> {
                container.followedRepositories.follow(
                    FollowedRepo(
                        id = manifest.id,
                        name = manifest.name,
                        manifestUrl = manifestUrl,
                        author = manifest.author,
                        homepage = manifest.homepage,
                        addedEpoch = System.currentTimeMillis(),
                    )
                )
                null
            }
        }
    }
} catch (t: Throwable) {
    t.message ?: "Could not read that repository"
}

@Composable
private fun FollowedRow(
    repo: FollowedRepo,
    status: RepoStatus?,
    onOpen: () -> Unit,
    onUnfollow: () -> Unit,
) {
    val shape = RoundedCornerShape(Radii.lg)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .clickable(onClick = onOpen)
            .padding(Spacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                repo.name,
                style = CadenceType.bodyStrong,
                color = CadenceColors.Fg1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            repo.author?.takeIf { it.isNotBlank() }?.let {
                Text("by $it", style = CadenceType.tick, color = CadenceColors.Fg4)
            }
            val total = status?.total ?: 0
            Text(
                when {
                    status is RepoStatus.Checking -> "Checking…"
                    status is RepoStatus.Failed && total == 0 -> "Could not reach it"
                    total > 0 -> "$total new"
                    else -> "Up to date"
                },
                style = CadenceType.tick,
                color = if (total > 0) CadenceColors.Fg1 else CadenceColors.Fg3,
            )
        }
        Text(
            "Unfollow",
            style = CadenceType.tick,
            color = CadenceColors.Fg3,
            modifier = Modifier
                .clickable(onClick = onUnfollow)
                .padding(Spacing.s2),
        )
    }
}

@Composable
private fun SuggestionRow(entry: RegistryEntry, onFollow: () -> Unit) {
    val shape = RoundedCornerShape(Radii.lg)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .padding(Spacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
            entry.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = CadenceType.tick, color = CadenceColors.Fg3, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (entry.hasRoutes) {
                Text("workouts and routes", style = CadenceType.tick, color = CadenceColors.Fg4)
            }
        }
        Spacer(Modifier.width(Spacing.s2))
        IconBtn(Icons.Outlined.Add, "Follow ${entry.name}", onClick = onFollow)
    }
}

@Composable
private fun AddByUrlRow(
    url: String,
    busy: Boolean,
    onUrlChange: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val shape = RoundedCornerShape(Radii.lg)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .padding(horizontal = Spacing.s4, vertical = Spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = url,
            onValueChange = onUrlChange,
            singleLine = true,
            textStyle = CadenceType.body.copy(color = CadenceColors.Fg1),
            cursorBrush = SolidColor(CadenceColors.Fg1),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { onAdd() }),
            modifier = Modifier.weight(1f).padding(vertical = Spacing.s2),
            decorationBox = { inner ->
                if (url.isEmpty()) {
                    Text(
                        "https://…/manifest.json",
                        style = CadenceType.body,
                        color = CadenceColors.Fg4,
                        fontSize = 14.sp,
                    )
                }
                inner()
            },
        )
        if (busy) {
            CircularProgressIndicator(
                color = CadenceColors.Fg1,
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else {
            IconBtn(Icons.Outlined.Check, "Follow this repository", onClick = onAdd)
        }
    }
}

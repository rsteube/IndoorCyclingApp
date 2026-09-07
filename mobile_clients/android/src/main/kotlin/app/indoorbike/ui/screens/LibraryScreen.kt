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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.indoorbike.LocalAppContainer
import app.indoorbike.library.ImportResult
import app.indoorbike.library.RegistryState
import app.indoorbike.shared.repo.ImportKind
import app.indoorbike.shared.repo.ManifestParser
import app.indoorbike.shared.repo.RegistryEntry
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.marginRidges
import app.indoorbike.ui.components.ContentMaxWidth
import app.indoorbike.ui.components.rememberCollapsingHeaderState
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.SheetHandle
import app.indoorbike.ui.components.SheetScrim
import app.indoorbike.ui.components.SheetShape
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * "Get workouts" screen — the entry point for adding workouts to the rider's
 * library, regardless of where they come from.
 *
 *   ┌─────────────────────────────────┐
 *   │  Scan from web editor           │  hero: the most common path is
 *   │                                 │  "built it in the browser, ride
 *   │                                 │  it now" — the card teaches it.
 *   ├─────────────────────────────────┤
 *   │  Import file · Paste URL        │  secondary acquisition tiles.
 *   ├─────────────────────────────────┤
 *   │  Browse community               │  registry-driven repo cards;
 *   │                                 │  tapping a card opens the
 *   │                                 │  remote-repo browser to pick
 *   │                                 │  individual workouts to add.
 *   ├─────────────────────────────────┤
 *   │  Manage imported workouts › N   │  collapsed row; expands to the
 *   │                                 │  delete-only management list.
 *   └─────────────────────────────────┘
 *
 * Subscriptions were removed in favour of an explicit-add model: the rider
 * picks workouts from a repo and each one becomes a static local copy. The
 * "Browse community" section therefore just navigates; nothing is persisted
 * by tapping a repo card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onScanQr: () -> Unit,
    onOpenRepo: (manifestUrl: String) -> Unit,
    onOpenRoutes: (manifestUrl: String) -> Unit = {},
) {
    val container = LocalAppContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val registry by container.repositoryManager.registry.collectAsState()
    val entries by container.workoutLibrary.entries.collectAsState()
    val importedWorkouts = remember(entries) {
        entries.filter { it.source is app.indoorbike.library.WorkoutSource.Imported }
            .map { it.workout }
    }

    var refreshingRegistry by remember { mutableStateOf(false) }
    var showAddUrlSheet by remember { mutableStateOf(false) }
    var manageExpanded by remember { mutableStateOf(false) }

    // Kick off the first registry fetch when the screen opens. Subsequent
    // opens reuse the cache and the user can pull-to-refresh manually.
    LaunchedEffect(Unit) {
        if (registry is RegistryState.Initial) {
            refreshingRegistry = true
            container.repositoryManager.refreshRegistry()
            refreshingRegistry = false
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = container.workoutImporter.importFromUri(uri)
                handleImportResult(context, container, result)
            }
        }
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
            title = "Get workouts",
            eyebrow = "Add to your library",
            leading = {
                IconBtn(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack
                )
            },
            trailing = {
                IconBtn(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = "Refresh registry",
                    onClick = {
                        scope.launch {
                            refreshingRegistry = true
                            container.repositoryManager.refreshRegistry()
                            refreshingRegistry = false
                        }
                    }
                )
            }
        )

        LazyColumn(
            // Capped and left aligned, matching the screen title above it.
            modifier = Modifier.weight(1f).widthIn(max = ContentMaxWidth),
            contentPadding = PaddingValues(horizontal = Spacing.s5, vertical = Spacing.s4),
            verticalArrangement = Arrangement.spacedBy(Spacing.s4)
        ) {
            item {
                ScanHeroCard(onClick = onScanQr)
            }
            item {
                ActionRow(
                    onImportFile = {
                        // OpenDocument auto-mimes; we accept JSON, but accept
                        // any too because some file managers mis-type JSONs.
                        filePickerLauncher.launch(arrayOf("application/json", "*/*"))
                    },
                    onAddByUrl = { showAddUrlSheet = true }
                )
            }

            // ── Browse community registry ────────────────────────────────────
            item {
                Spacer(Modifier.height(Spacing.s2))
                SectionHeader(
                    title = "Browse community",
                    subtitle = registrySubtitle(registry, refreshingRegistry)
                )
            }
            registryItems(
                state = registry,
                refreshing = refreshingRegistry,
                onBrowse = { entry -> onOpenRepo(entry.manifestUrl) },
                onBrowseRoutes = { entry -> onOpenRoutes(entry.manifestUrl) }
            )

            // ── Imported workouts (manage) ───────────────────────────────────
            // Rare maintenance, so it stays out of the way: one collapsed row
            // that expands in place. Hidden entirely while nothing has been
            // imported — an empty management section is noise on a screen
            // whose job is acquisition.
            if (importedWorkouts.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(Spacing.s2))
                    ManageImportedRow(
                        count = importedWorkouts.size,
                        expanded = manageExpanded,
                        onToggle = { manageExpanded = !manageExpanded }
                    )
                }
                if (manageExpanded) {
                    items(importedWorkouts, key = { "imp-${it.id}" }) { workout ->
                        ImportedWorkoutRow(
                            name = workout.name,
                            onDelete = {
                                scope.launch {
                                    val ok = container.workoutImporter.deleteUserWorkout(workout.id)
                                    container.workoutLibrary.refreshImportsAwait()
                                    if (!ok) {
                                        Toast.makeText(
                                            context,
                                            "Couldn't delete \"${workout.name}\"",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAddUrlSheet) {
        AddByUrlSheet(
            onDismiss = { showAddUrlSheet = false },
            onSubmit = { url ->
                showAddUrlSheet = false
                scope.launch {
                    // Peek at the URL's payload to decide what to do. A
                    // manifest hand-off goes to the remote-repo browser
                    // (rider picks individual workouts there); a single
                    // workout / list runs through the regular importer.
                    val text = try {
                        app.indoorbike.library.HttpFetcher.getString(url)
                    } catch (t: Throwable) {
                        Toast.makeText(context, "Couldn't fetch: ${t.message}", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    when (ManifestParser.classify(text)) {
                        ImportKind.RepositoryManifest -> onOpenRepo(url)
                        else -> {
                            val result = container.workoutImporter.importJsonText(text)
                            handleImportResult(context, container, result)
                        }
                    }
                }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Acquisition actions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Hero slot: the web-editor handoff. Named and subtitled after the actual
 * workflow ("built it in the browser, ride it now") so the screen teaches
 * the path, not just offers a generic "Scan QR".
 */
@Composable
private fun ScanHeroCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radii.lg)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .clickable(onClick = onClick)
            .padding(Spacing.s5),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s4)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(CadenceColors.Bg2),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.QrCodeScanner,
                contentDescription = null,
                tint = CadenceColors.Fg1,
                modifier = Modifier.size(26.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Scan from web editor",
                style = CadenceType.bodyStrong,
                color = CadenceColors.Fg1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Point at the QR on indoorbike.app/editor",
                style = CadenceType.small,
                color = CadenceColors.Fg3
            )
        }
        Text(text = "›", style = CadenceType.h2, color = CadenceColors.Fg1)
    }
}

@Composable
private fun ActionRow(
    onImportFile: () -> Unit,
    onAddByUrl: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s3)) {
        ActionTile(
            icon = Icons.Outlined.FileOpen,
            label = "Import file",
            onClick = onImportFile,
            modifier = Modifier.weight(1f)
        )
        ActionTile(
            icon = Icons.Outlined.Link,
            label = "Paste URL",
            onClick = onAddByUrl,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Line2 border (not brand) so these read as secondary next to the
    // scan hero above them.
    val shape = RoundedCornerShape(Radii.lg)
    Column(
        modifier = modifier
            .clip(shape)
            .background(CadenceColors.Bg1)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s2)
    ) {
        Icon(icon, contentDescription = null, tint = CadenceColors.Fg1, modifier = Modifier.size(24.dp))
        Text(label, style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Sections
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String, subtitle: String?) {
    Column(modifier = Modifier.padding(top = Spacing.s4)) {
        Text(text = title, style = CadenceType.eyebrow, color = CadenceColors.Fg3)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(text = subtitle, style = CadenceType.small, color = CadenceColors.Fg3)
        }
    }
}

/**
 * Collapsed entry point for import management. Chevron rotates text-free
 * ("›" vs "⌄" glyphs) to keep the row dependency-light.
 */
@Composable
private fun ManageImportedRow(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val shape = RoundedCornerShape(Radii.lg)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .clickable(onClick = onToggle)
            .padding(horizontal = Spacing.s4, vertical = Spacing.s3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Manage imported workouts",
            style = CadenceType.bodyStrong,
            color = CadenceColors.Fg2,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$count",
            style = CadenceType.tick,
            color = CadenceColors.Fg3
        )
        Spacer(Modifier.size(Spacing.s2))
        Text(
            text = if (expanded) "⌄" else "›",
            style = CadenceType.h3,
            color = CadenceColors.Fg3
        )
    }
}

@Composable
private fun ImportedWorkoutRow(name: String, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(Radii.lg)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .padding(horizontal = Spacing.s4, vertical = Spacing.s3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = CadenceType.bodyStrong,
            color = CadenceColors.Fg1,
            modifier = Modifier.weight(1f)
        )
        IconBtn(
            icon = Icons.Outlined.DeleteOutline,
            contentDescription = "Delete",
            onClick = onDelete
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.registryItems(
    state: RegistryState,
    refreshing: Boolean,
    onBrowse: (RegistryEntry) -> Unit,
    onBrowseRoutes: (RegistryEntry) -> Unit = {}
) {
    when (state) {
        RegistryState.Initial -> if (refreshing) item { LoadingRow() } else item { RegistryEmpty() }
        is RegistryState.Loaded -> {
            val repos = state.index.repositories
            if (repos.isEmpty()) {
                item { RegistryEmpty() }
            } else {
                items(repos, key = { "reg-${it.id}" }) { entry ->
                    RegistryEntryCard(
                        entry = entry,
                        onBrowse = { onBrowse(entry) },
                        onBrowseRoutes = { onBrowseRoutes(entry) },
                    )
                }
            }
        }
        is RegistryState.Failed -> {
            item { RegistryUnreachable(state.error) }
            state.cachedIndex?.repositories?.let { repos ->
                items(repos, key = { "reg-cached-${it.id}" }) { entry ->
                    RegistryEntryCard(
                        entry = entry,
                        onBrowse = { onBrowse(entry) },
                        onBrowseRoutes = { onBrowseRoutes(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RegistryEntryCard(
    entry: RegistryEntry,
    onBrowse: () -> Unit,
    onBrowseRoutes: () -> Unit = {}
) {
    val shape = RoundedCornerShape(Radii.lg)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .clickable(onClick = onBrowse)
            .padding(Spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Spacing.s2)
    ) {
        entry.author?.let { author ->
            Text(text = author, style = CadenceType.eyebrow, color = CadenceColors.Fg3)
        }
        Text(text = entry.name, style = CadenceType.h3, color = CadenceColors.Fg1)
        entry.description?.takeIf { it.isNotBlank() }?.let { desc ->
            Text(text = desc, style = CadenceType.small, color = CadenceColors.Fg2)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = shortHost(entry.manifestUrl),
                style = CadenceType.tick,
                color = CadenceColors.Fg3
            )
            // Browse pill — visually a button so the card doesn't look
            // dead. Whole card is clickable too (target the chunk near a
            // user's thumb), with this acting as the explicit affordance.
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Shown only when the registry says this repo publishes
                // routes. Guessing would mean fetching every manifest just to
                // draw the list, which is the round-trip the registry avoids.
                if (entry.hasRoutes) {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(CadenceColors.Bg3)
                            .clickable(onClick = onBrowseRoutes)
                            .padding(horizontal = Spacing.s4, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Routes", style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
                    }
                }
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(CadenceColors.Fg1)
                        .clickable(onClick = onBrowse)
                        .padding(horizontal = Spacing.s4, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Browse",
                        style = CadenceType.small.copy(fontWeight = FontWeight.Bold),
                        color = CadenceColors.OnBrand
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.s4),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            color = CadenceColors.Fg1,
            strokeWidth = 2.dp,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.size(Spacing.s2))
        Text("Fetching registry…", style = CadenceType.tick, color = CadenceColors.Fg3)
    }
}

@Composable
private fun RegistryEmpty() {
    EmptyHint(
        title = "No community repositories yet.",
        body = "When the registry comes online you'll see browseable repos here. " +
            "In the meantime, use \"Scan QR\" or \"By URL\" to add specific repos directly."
    )
}

@Composable
private fun RegistryUnreachable(error: String) {
    EmptyHint(
        title = "Couldn't reach the registry.",
        body = "$error\n\nUse \"Scan QR\" or \"By URL\" to add repos directly while we wait.",
        accent = CadenceColors.Warning
    )
}

@Composable
private fun EmptyHint(title: String, body: String, accent: Color = CadenceColors.Fg3) {
    val shape = RoundedCornerShape(Radii.lg)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .padding(Spacing.s4)
    ) {
        Text(text = title, style = CadenceType.bodyStrong, color = accent)
        Spacer(Modifier.height(Spacing.s2))
        Text(text = body, style = CadenceType.small, color = CadenceColors.Fg3)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Add-by-URL sheet
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddByUrlSheet(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var input by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CadenceColors.Bg1,
        contentColor = CadenceColors.Fg1,
        shape = SheetShape,
        scrimColor = SheetScrim,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = Spacing.s5)
                .padding(bottom = Spacing.s5)
        ) {
            Text(text = "Add by URL", style = CadenceType.eyebrow, color = CadenceColors.Fg3)
            Spacer(Modifier.height(6.dp))
            Text(text = "Paste a repository or workout URL", style = CadenceType.h2, color = CadenceColors.Fg1)
            Spacer(Modifier.height(Spacing.s2))
            Text(
                text = "Must be HTTPS. Workouts import directly. Repository URLs open the browser so you can pick which workouts to add.",
                style = CadenceType.body,
                color = CadenceColors.Fg3
            )
            Spacer(Modifier.height(Spacing.s5))
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                textStyle = CadenceType.bodyStrong.copy(color = CadenceColors.Fg1),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = {
                    if (input.isNotBlank()) onSubmit(input.trim())
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.md))
                    .background(CadenceColors.Bg2)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                decorationBox = { inner ->
                    if (input.isEmpty()) {
                        Text("https://example.com/indoorbike.json", style = CadenceType.body, color = CadenceColors.Fg4)
                    }
                    inner()
                }
            )
            Spacer(Modifier.height(Spacing.s5))
            PrimaryBtn(
                text = "Add",
                leadingIcon = null,
                onClick = { if (input.isNotBlank()) onSubmit(input.trim()) },
                enabled = input.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun registrySubtitle(state: RegistryState, refreshing: Boolean): String? = when {
    refreshing -> "Fetching…"
    state is RegistryState.Loaded -> "${state.index.repositories.size} available" +
        if (state.fromCache) " · cached" else ""
    state is RegistryState.Failed -> state.cachedIndex?.let { "Showing cached copy. ${it.repositories.size} available." }
        ?: "Registry unavailable"
    else -> null
}

private fun shortHost(url: String): String = try {
    java.net.URI(url).host ?: url
} catch (_: Throwable) { url }

private suspend fun handleImportResult(
    context: android.content.Context,
    container: app.indoorbike.AppContainer,
    result: ImportResult
) {
    when (result) {
        is ImportResult.Workouts -> {
            container.workoutLibrary.refreshImports()
            val n = result.workouts.size
            Toast.makeText(
                context,
                if (n == 1) "Imported \"${result.workouts.first().name}\"" else "Imported $n workouts",
                Toast.LENGTH_SHORT
            ).show()
        }
        is ImportResult.Manifest -> {
            // Reachable from the file picker (somebody picked a manifest.json
            // on their device). We don't auto-add repos under the explicit-add
            // model; nudge the user to use the Browse flow instead.
            Toast.makeText(
                context,
                "\"${result.manifest.name}\" is a community repository. " +
                    "Tap a Browse card above to pick which workouts to add.",
                Toast.LENGTH_LONG
            ).show()
        }
        is ImportResult.Failure -> Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
    }
}

package app.indoorbike.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.indoorbike.LocalAppContainer
import app.indoorbike.library.ImportResult
import app.indoorbike.library.RegistryState
import app.indoorbike.library.SubscribeResult
import app.indoorbike.library.SubscribedRepository
import app.indoorbike.shared.repo.RegistryEntry
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * One-stop screen for managing where workouts come from.
 *
 *   ┌─────────────────────────────────┐
 *   │  Action row                     │  Import file · Scan QR · Add by URL
 *   ├─────────────────────────────────┤
 *   │  Subscribed repositories        │  list with refresh / unsubscribe
 *   │  Browse community               │  list driven by the registry
 *   │  Imported workouts              │  user files
 *   └─────────────────────────────────┘
 *
 * Browse degrades gracefully when the registry URL isn't live yet — empty
 * state with a hint to use the Add by URL / QR actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onScanQr: () -> Unit,
) {
    val container = LocalAppContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val subscribed by container.repositoryManager.subscribed.collectAsState()
    val registry by container.repositoryManager.registry.collectAsState()
    val entries by container.workoutLibrary.entries.collectAsState()
    val importedWorkouts = remember(entries) {
        entries.filter { it.source is app.indoorbike.library.WorkoutSource.Imported }
            .map { it.workout }
    }

    var refreshingRegistry by remember { mutableStateOf(false) }
    var showAddUrlSheet by remember { mutableStateOf(false) }

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

    Column(modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0)) {
        AppBar(
            title = "Library",
            eyebrow = "WORKOUT SOURCES",
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
                            container.repositoryManager.refreshAll()
                            refreshingRegistry = false
                        }
                    }
                )
            }
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = Spacing.s5, vertical = Spacing.s4),
            verticalArrangement = Arrangement.spacedBy(Spacing.s4)
        ) {
            item {
                ActionRow(
                    onImportFile = {
                        // OpenDocument auto-mimes; we accept JSON, but accept
                        // any too because some file managers mis-type JSONs.
                        filePickerLauncher.launch(arrayOf("application/json", "*/*"))
                    },
                    onScanQr = onScanQr,
                    onAddByUrl = { showAddUrlSheet = true }
                )
            }

            // ── Subscribed repositories ──────────────────────────────────────
            item {
                SectionHeader(
                    title = "Subscribed repositories",
                    subtitle = if (subscribed.isEmpty()) "None yet — browse below or add a URL above."
                    else "${subscribed.size} repository${if (subscribed.size == 1) "" else "s"}"
                )
            }
            items(subscribed, key = { "sub-${it.id}" }) { sub ->
                SubscribedRepositoryCard(
                    sub = sub,
                    onRefresh = {
                        val url = sub.manifestUrl ?: return@SubscribedRepositoryCard
                        scope.launch {
                            val result = container.repositoryManager.subscribeByUrl(url)
                            Toast.makeText(
                                context,
                                if (result is SubscribeResult.Ok) "Refreshed \"${sub.name}\""
                                else "Couldn't refresh \"${sub.name}\"",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onUnsubscribe = {
                        scope.launch {
                            container.repositoryManager.unsubscribe(sub.id)
                            Toast.makeText(context, "Removed \"${sub.name}\"", Toast.LENGTH_SHORT).show()
                        }
                    }
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
                isSubscribed = { id -> container.repositoryManager.isSubscribed(id) },
                onSubscribe = { entry ->
                    scope.launch {
                        val result = container.repositoryManager.subscribeByUrl(entry.manifestUrl)
                        Toast.makeText(
                            context,
                            when (result) {
                                is SubscribeResult.Ok -> "Subscribed to \"${entry.name}\""
                                is SubscribeResult.Failure -> result.message
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )

            // ── Imported workouts ────────────────────────────────────────────
            item {
                Spacer(Modifier.height(Spacing.s2))
                SectionHeader(
                    title = "Imported workouts",
                    subtitle = if (importedWorkouts.isEmpty()) "Single files you brought in by share, file pick, or QR."
                    else "${importedWorkouts.size} workout${if (importedWorkouts.size == 1) "" else "s"}"
                )
            }
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

    if (showAddUrlSheet) {
        AddByUrlSheet(
            onDismiss = { showAddUrlSheet = false },
            onSubmit = { url ->
                showAddUrlSheet = false
                scope.launch {
                    // Try as repo first, fall back to single workout via
                    // importer's auto-classification.
                    val text = try {
                        app.indoorbike.library.HttpFetcher.getString(url)
                    } catch (t: Throwable) {
                        Toast.makeText(context, "Couldn't fetch: ${t.message}", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    val result = container.workoutImporter.importJsonText(text)
                    when (result) {
                        is ImportResult.Manifest ->
                            container.repositoryManager.subscribeFromManifest(result.manifest, sourceUrl = url)
                        else -> Unit
                    }
                    handleImportResult(context, container, result)
                }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Action row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ActionRow(
    onImportFile: () -> Unit,
    onScanQr: () -> Unit,
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
            icon = Icons.Outlined.QrCodeScanner,
            label = "Scan QR",
            onClick = onScanQr,
            modifier = Modifier.weight(1f)
        )
        ActionTile(
            icon = Icons.Outlined.Link,
            label = "By URL",
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
    val shape = RoundedCornerShape(Radii.md)
    Column(
        modifier = modifier
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Brand500.copy(alpha = 0.4f), shape)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s2)
    ) {
        Icon(icon, contentDescription = null, tint = CadenceColors.Brand500, modifier = Modifier.size(24.dp))
        Text(label, style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Sections
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String, subtitle: String?) {
    Column(modifier = Modifier.padding(top = Spacing.s4)) {
        Text(text = title.uppercase(), style = CadenceType.eyebrow, color = CadenceColors.Fg3)
        Spacer(Modifier.height(2.dp))
        Text(text = title, style = CadenceType.h3, color = CadenceColors.Fg1)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(text = subtitle, style = CadenceType.small, color = CadenceColors.Fg3)
        }
    }
}

@Composable
private fun SubscribedRepositoryCard(
    sub: SubscribedRepository,
    onRefresh: () -> Unit,
    onUnsubscribe: () -> Unit
) {
    val shape = RoundedCornerShape(Radii.md)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, shape)
            .padding(Spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Spacing.s2)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = sub.name, style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
                if (!sub.description.isNullOrBlank()) {
                    Text(text = sub.description, style = CadenceType.small, color = CadenceColors.Fg3)
                }
                Text(
                    text = "${sub.workoutCount} workouts" +
                        if (sub.manifestUrl != null) " · ${shortHost(sub.manifestUrl)}" else "",
                    style = CadenceType.tick,
                    color = CadenceColors.Fg3
                )
            }
            if (sub.manifestUrl != null) {
                IconBtn(icon = Icons.Outlined.Refresh, contentDescription = "Refresh", onClick = onRefresh)
            }
            IconBtn(
                icon = Icons.Outlined.DeleteOutline,
                contentDescription = "Unsubscribe",
                onClick = onUnsubscribe
            )
        }
    }
}

@Composable
private fun ImportedWorkoutRow(name: String, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(Radii.md)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, shape)
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
    isSubscribed: (String) -> Boolean,
    onSubscribe: (RegistryEntry) -> Unit
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
                        subscribed = isSubscribed(entry.id),
                        onSubscribe = { onSubscribe(entry) }
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
                        subscribed = isSubscribed(entry.id),
                        onSubscribe = { onSubscribe(entry) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RegistryEntryCard(
    entry: RegistryEntry,
    subscribed: Boolean,
    onSubscribe: () -> Unit
) {
    val shape = RoundedCornerShape(Radii.md)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, shape)
            .padding(Spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Spacing.s2)
    ) {
        entry.author?.let { author ->
            Text(text = author.uppercase(), style = CadenceType.eyebrow, color = CadenceColors.Fg3)
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
            if (subscribed) {
                Text(text = "SUBSCRIBED", style = CadenceType.eyebrow, color = CadenceColors.Success)
            } else {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(CadenceColors.Brand500.copy(alpha = 0.18f))
                        .clickable(onClick = onSubscribe)
                        .padding(horizontal = Spacing.s4, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.CloudDownload,
                        contentDescription = null,
                        tint = CadenceColors.Brand500,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(text = "Subscribe", style = CadenceType.bodyStrong, color = CadenceColors.Brand500)
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
            color = CadenceColors.Brand500,
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
    val shape = RoundedCornerShape(Radii.md)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line1, shape)
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
        contentColor = CadenceColors.Fg1
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.s5)
                .padding(bottom = Spacing.s5)
        ) {
            Text(text = "ADD BY URL", style = CadenceType.eyebrow, color = CadenceColors.Brand500)
            Spacer(Modifier.height(6.dp))
            Text(text = "Paste a repository or workout URL", style = CadenceType.h2, color = CadenceColors.Fg1)
            Spacer(Modifier.height(Spacing.s2))
            Text(
                text = "Must be HTTPS. The app figures out whether it's a repository manifest or a single workout.",
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
                    .border(1.dp, CadenceColors.Line2, RoundedCornerShape(Radii.md))
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
    state is RegistryState.Failed -> state.cachedIndex?.let { "Showing cached copy — ${it.repositories.size} available" }
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
            // Caller may have already subscribed via subscribeFromManifest; if
            // not, do it now (covers the file-picker path).
            if (!container.repositoryManager.isSubscribed(result.manifest.id)) {
                container.repositoryManager.subscribeFromManifest(result.manifest, sourceUrl = null)
            }
            Toast.makeText(
                context,
                "Subscribed to \"${result.manifest.name}\" (${result.manifest.workouts.size} workouts)",
                Toast.LENGTH_SHORT
            ).show()
        }
        is ImportResult.Failure -> Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
    }
}

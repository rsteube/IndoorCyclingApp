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

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.LocalAppContainer
import app.indoorbike.library.WorkoutEntry
import app.indoorbike.library.WorkoutSource
import app.indoorbike.shared.Workout
import app.indoorbike.shared.settings.MAX_FTP
import app.indoorbike.shared.settings.MAX_WEIGHT_KG
import app.indoorbike.shared.settings.MIN_FTP
import app.indoorbike.shared.settings.MIN_WEIGHT_KG
import app.indoorbike.shared.settings.UNKNOWN_FTP_DEFAULT
import app.indoorbike.shared.workout.WorkoutSummary
import app.indoorbike.shared.workout.formatDurationPretty
import app.indoorbike.shared.workout.toSummary
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.FilterChip
import app.indoorbike.ui.components.HrmHeartIndicator
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.MetaPill
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.TrainerStatusStrip
import app.indoorbike.ui.components.UnfinishedRideBanner
import app.indoorbike.ui.components.ZoneBar
import app.indoorbike.ui.workout.zoneSegments
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing

private const val STARTER_WORKOUT_ID = "starter-15min"

private const val FILTER_ALL = "All"
// Star prefix doubles as the visual cue so the chip strip doesn't need a
// separate icon slot. Star (rather than heart) avoids reading as a heart-rate
// affordance, which is what the heart glyph means everywhere else in the app.
// Persisted nowhere; it's just a UI label.
private const val FILTER_FAVOURITES = "★ Favourites"

@Composable
fun WorkoutListScreen(
    entries: List<WorkoutEntry>,
    eyebrow: String,
    onPick: (Workout) -> Unit,
    onOpenLibrary: () -> Unit = {},
    activeTab: String = "rides",
    onTabSelected: (String) -> Unit = {}
) {
    val container = LocalAppContainer.current
    var selectedFilter by remember { mutableStateOf("All") }
    val ftp by container.userSettings.ftp.collectAsState()
    val favouriteIds by container.userSettings.favouriteWorkoutIds.collectAsState()
    val workouts = remember(entries) { entries.map { it.workout } }
    val sourceById = remember(entries) { entries.associate { it.workout.id to it.source } }
    val summaries = remember(workouts, ftp) { workouts.map { it.toSummary(ftp) } }
    // "Favourites" lives between "All" and the tag chips. Hidden until the
    // rider has actually starred something so the chip strip doesn't carry a
    // permanently-empty filter for first-time users.
    val availableTags = remember(summaries, favouriteIds) {
        val tagChips = summaries.map { it.tag }.distinct().sorted()
        buildList {
            add(FILTER_ALL)
            if (favouriteIds.isNotEmpty()) add(FILTER_FAVOURITES)
            addAll(tagChips)
        }
    }
    val visible = remember(summaries, selectedFilter, favouriteIds) {
        when (selectedFilter) {
            FILTER_ALL -> summaries
            FILTER_FAVOURITES -> summaries.filter { it.workout.id in favouriteIds }
            else -> summaries.filter { it.tag.equals(selectedFilter, ignoreCase = true) }
        }
    }
    // If the user starred everything and then unstars while the Favourites
    // filter is active, the chip vanishes and `selectedFilter` is stranded.
    // Snap back to "All" so the empty chip strip doesn't show no rows for an
    // option that no longer exists.
    if (selectedFilter == FILTER_FAVOURITES && favouriteIds.isEmpty()) {
        selectedFilter = FILTER_ALL
    }

    val hrmState by container.heartRateMonitor.connectionState.collectAsState()
    val hasCompletedOnboarding by container.userSettings.hasCompletedOnboarding.collectAsState()
    val sessions by container.sessions.collectAsState()
    val starter = remember(summaries) { summaries.firstOrNull { it.workout.id == STARTER_WORKOUT_ID } }
    val showStarterCard = hasCompletedOnboarding && sessions.isEmpty() && starter != null

    var showOnboardingSheet by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0)) {
        AppBar(
            title = "Pick a workout",
            eyebrow = eyebrow,
            leading = {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(Radii.sm))
                        .background(CadenceColors.Brand500.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.DirectionsBike,
                        contentDescription = null,
                        tint = CadenceColors.Brand500,
                        modifier = Modifier.size(18.dp)
                    )
                }
            },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HrmHeartIndicator(state = hrmState)
                    Spacer(Modifier.width(Spacing.s2))
                    IconBtn(
                        icon = Icons.AutoMirrored.Outlined.LibraryBooks,
                        contentDescription = "Workout library and repositories",
                        onClick = onOpenLibrary
                    )
                }
            }
        )

        UnfinishedRideBanner(
            modifier = Modifier.padding(horizontal = Spacing.s5, vertical = Spacing.s2)
        )

        val connectionState by container.trainerManager.connectionState.collectAsState()
        val pairedTrainerId by container.trainerManager.pairedTrainerId.collectAsState()
        val isMockTrainer by container.trainerManager.isMockTrainer.collectAsState()
        val connectedTrainerName by container.trainerManager.connectedTrainerName.collectAsState()

        when {
            !hasCompletedOnboarding -> WelcomeBanner(
                onGetStarted = { showOnboardingSheet = true },
                modifier = Modifier.padding(horizontal = Spacing.s5)
            )
            pairedTrainerId == null && !isMockTrainer -> AddTrainerNudge(
                onTap = { onTabSelected("settings") },
                modifier = Modifier.padding(horizontal = Spacing.s5)
            )
            else -> TrainerStatusStrip(
                deviceName = connectedTrainerName ?: "Unknown Trainer",
                statusLine = when (connectionState) {
                    app.indoorbike.shared.ble.TrainerConnectionState.Connected -> "Connected"
                    app.indoorbike.shared.ble.TrainerConnectionState.Connecting -> "Connecting..."
                    app.indoorbike.shared.ble.TrainerConnectionState.Scanning -> "Scanning..."
                    else -> "Not Connected"
                },
                icon = Icons.Outlined.Bluetooth,
                modifier = Modifier.padding(horizontal = Spacing.s5),
                connected = connectionState == app.indoorbike.shared.ble.TrainerConnectionState.Connected
            )
        }
        Spacer(Modifier.height(Spacing.s4))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.s5),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            availableTags.forEach { f ->
                FilterChip(label = f, selected = f == selectedFilter, onClick = { selectedFilter = f })
            }
        }
        Spacer(Modifier.height(Spacing.s3))

        if (workouts.isEmpty()) {
            // Skeleton placeholders while the assets parser is still loading.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.s5),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                repeat(3) { SkeletonCard() }
            }
        } else {
            // Hide the starter from the regular list while it's pinned at the top —
            // otherwise it would appear twice on the first session.
            val regularList = if (showStarterCard) {
                visible.filter { it.workout.id != STARTER_WORKOUT_ID }
            } else visible

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = Spacing.s5, end = Spacing.s5,
                    top = 0.dp, bottom = Spacing.s5
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (showStarterCard) {
                    val pin = starter!!
                    item(key = "starter-pinned") {
                        StarterCard(summary = pin, onClick = { onPick(pin.workout) })
                    }
                }
                items(regularList, key = { it.workout.id }) { summary ->
                    WorkoutCard(
                        summary = summary,
                        source = sourceById[summary.workout.id],
                        onClick = { onPick(summary.workout) }
                    )
                }
            }
        }
    }

    if (showOnboardingSheet) {
        OnboardingSheet(
            onProfileSaved = { newFtp, newWeight ->
                // Don't mark onboarding complete yet — the permission step still
                // has to run. Persist the profile so closing the sheet mid-flow
                // doesn't lose it.
                container.userSettings.setFtp(newFtp)
                container.userSettings.setWeightKg(newWeight)
            },
            onFinish = {
                container.userSettings.setHasCompletedOnboarding(true)
                showOnboardingSheet = false
            }
        )
    }
}

@Composable
private fun SkeletonCard() {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line1, shape)
            .padding(Spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Spacing.s3)
    ) {
        Box(
            modifier = Modifier
                .height(14.dp)
                .fillMaxWidth(0.4f)
                .clip(RoundedCornerShape(4.dp))
                .background(CadenceColors.Bg2)
        )
        Box(
            modifier = Modifier
                .height(20.dp)
                .fillMaxWidth(0.7f)
                .clip(RoundedCornerShape(4.dp))
                .background(CadenceColors.Bg2)
        )
        Box(
            modifier = Modifier
                .height(8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(CadenceColors.Bg2)
        )
    }
}

@Composable
private fun WelcomeBanner(onGetStarted: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Radii.lg)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Brand500.copy(alpha = 0.12f))
            .border(1.dp, CadenceColors.Brand500.copy(alpha = 0.6f), shape)
            .padding(Spacing.s5)
    ) {
        Text(
            text = "WELCOME",
            style = CadenceType.eyebrow,
            color = CadenceColors.Brand500
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Welcome to Indoor Bike",
            style = CadenceType.h2,
            color = CadenceColors.Fg1
        )
        Spacer(Modifier.height(Spacing.s2))
        Text(
            text = "Power-zone workouts for your smart trainer. Set your FTP and weight so the targets match you — takes about 30 seconds.",
            style = CadenceType.body,
            color = CadenceColors.Fg2
        )
        Spacer(Modifier.height(Spacing.s4))
        PrimaryBtn(
            text = "Get Started",
            leadingIcon = null,
            onClick = onGetStarted,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun AddTrainerNudge(onTap: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.md))
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Brand500.copy(alpha = 0.4f), RoundedCornerShape(Radii.md))
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CadenceColors.Brand500.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Bluetooth,
                contentDescription = null,
                tint = CadenceColors.Brand500,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Pair a trainer", style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
            Text(
                text = if (app.indoorbike.BuildConfig.DEBUG)
                    "Open Settings · Devices, or try the mock trainer"
                else
                    "Open Settings · Devices to pair over Bluetooth",
                style = CadenceType.tick,
                color = CadenceColors.Fg3
            )
        }
        Text(text = "›", style = CadenceType.h2, color = CadenceColors.Brand500)
    }
}

@Composable
private fun StarterCard(summary: WorkoutSummary, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(2.dp, CadenceColors.Brand500, shape)
            .clickable(onClick = onClick)
            .padding(Spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Spacing.s3)
    ) {
        Text(
            text = "START HERE · YOUR FIRST RIDE",
            style = CadenceType.eyebrow,
            color = CadenceColors.Brand500
        )
        Text(
            text = summary.workout.name,
            style = CadenceType.h2,
            color = CadenceColors.Fg1
        )
        summary.workout.description?.takeIf { it.isNotBlank() }?.let { desc ->
            Text(
                text = desc,
                style = CadenceType.small,
                color = CadenceColors.Fg3
            )
        }
        ZoneBar(segments = summary.zoneSegments(), height = 8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MetaPill(text = formatDurationPretty(summary.totalSeconds).uppercase())
            MetaPill(text = "TSS ${summary.tss}")
            MetaPill(text = "IF ${"%.2f".format(summary.intensityFactor)}")
        }
    }
}

private enum class OnboardingStep { Profile, Permissions }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingSheet(
    onProfileSaved: (Int, Float) -> Unit,
    onFinish: () -> Unit,
) {
    var step by remember { mutableStateOf(OnboardingStep.Profile) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        // Sheet dismiss (swipe-down, scrim tap, back) on either step counts as
        // finishing onboarding so the user isn't pestered next launch — the
        // RideScreen permission request is the safety net.
        onDismissRequest = onFinish,
        sheetState = sheetState,
        containerColor = CadenceColors.Bg1,
        contentColor = CadenceColors.Fg1,
    ) {
        when (step) {
            OnboardingStep.Profile -> ProfileStep(
                onSaved = { ftp, weight ->
                    onProfileSaved(ftp, weight)
                    step = OnboardingStep.Permissions
                }
            )
            OnboardingStep.Permissions -> PermissionsStep(onDone = onFinish)
        }
    }
}

@Composable
private fun ProfileStep(onSaved: (Int, Float) -> Unit) {
    val container = LocalAppContainer.current
    val currentFtp by container.userSettings.ftp.collectAsState()
    val currentWeight by container.userSettings.weightKg.collectAsState()

    var ftpInput by remember { mutableStateOf(currentFtp.toString()) }
    var weightInput by remember { mutableStateOf(formatWeightForInput(currentWeight)) }

    val parsedFtp = ftpInput.toIntOrNull()
    val parsedWeight = weightInput.replace(',', '.').toFloatOrNull()
    val ftpValid = parsedFtp != null && parsedFtp in MIN_FTP..MAX_FTP
    val weightValid = parsedWeight != null && parsedWeight in MIN_WEIGHT_KG..MAX_WEIGHT_KG
    val canSave = ftpValid && weightValid

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.s5)
            .padding(bottom = Spacing.s5)
    ) {
        StepHeader(eyebrow = "STEP 1 OF 2 · ABOUT YOU", title = "Set your FTP and weight")
        Spacer(Modifier.height(Spacing.s2))
        Text(
            text = "We use these to scale every workout to your fitness. You can change them anytime in Settings.",
            style = CadenceType.body,
            color = CadenceColors.Fg3
        )
        Spacer(Modifier.height(Spacing.s5))

        OnboardingNumberField(
            label = "Functional Threshold Power",
            sublabel = "Your sustainable hour-long power",
            value = ftpInput,
            onValueChange = { v -> ftpInput = v.filter { it.isDigit() }.take(4) },
            unit = "W",
            keyboardType = KeyboardType.Number,
        )
        Spacer(Modifier.height(Spacing.s2))
        Text(
            text = "Don't know your FTP? Start at ${UNKNOWN_FTP_DEFAULT}W",
            style = CadenceType.small,
            color = CadenceColors.Brand500,
            modifier = Modifier
                .clickable { ftpInput = UNKNOWN_FTP_DEFAULT.toString() }
                .padding(vertical = 4.dp)
        )

        Spacer(Modifier.height(Spacing.s4))

        OnboardingNumberField(
            label = "Body Weight",
            sublabel = "Used for W/kg and XP",
            value = weightInput,
            onValueChange = { v ->
                val filtered = buildString {
                    var seenDot = false
                    for (c in v.take(6)) {
                        if (c.isDigit()) append(c)
                        else if ((c == '.' || c == ',') && !seenDot) {
                            append('.'); seenDot = true
                        }
                    }
                }
                weightInput = filtered
            },
            unit = "kg",
            keyboardType = KeyboardType.Decimal,
        )

        Spacer(Modifier.height(Spacing.s5))

        PrimaryBtn(
            text = "Continue",
            leadingIcon = null,
            onClick = { if (canSave) onSaved(parsedFtp!!, parsedWeight!!) },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Pre-flight rationale for Bluetooth (and POST_NOTIFICATIONS on T+). Showing
 * this BEFORE the system dialog is Google's recommended pattern: it gives the
 * user the "why" so the system prompt isn't a blind yes/no. We launch the
 * system request the moment they tap "Allow access" — outcome doesn't matter
 * for finishing onboarding (the in-RideScreen request is the safety net).
 */
@Composable
private fun PermissionsStep(onDone: () -> Unit) {
    val needsNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val bluetoothPerms = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val allPerms = remember(needsNotifications) {
        if (needsNotifications) bluetoothPerms + Manifest.permission.POST_NOTIFICATIONS
        else bluetoothPerms
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Whether granted or not, we move on. RideScreen will re-prompt on
        // first ride if Bluetooth was denied here.
        onDone()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.s5)
            .padding(bottom = Spacing.s5)
    ) {
        StepHeader(eyebrow = "STEP 2 OF 2 · CONNECT", title = "Allow trainer access")
        Spacer(Modifier.height(Spacing.s2))
        Text(
            text = "Indoor Bike talks to your smart trainer over Bluetooth. No internet, no account, no data leaves your phone.",
            style = CadenceType.body,
            color = CadenceColors.Fg3
        )

        Spacer(Modifier.height(Spacing.s5))

        PermissionRow(
            icon = Icons.Outlined.Bluetooth,
            title = "Bluetooth",
            body = "Find and pair your smart trainer, heart-rate strap, and Zwift Click controller."
        )
        if (needsNotifications) {
            Spacer(Modifier.height(Spacing.s3))
            PermissionRow(
                icon = Icons.Outlined.Notifications,
                title = "Notifications",
                body = "Show the active-ride strip so Android keeps the app alive while you ride."
            )
        }

        Spacer(Modifier.height(Spacing.s5))

        PrimaryBtn(
            text = "Allow access",
            leadingIcon = null,
            onClick = { launcher.launch(allPerms) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Spacing.s2))
        Text(
            text = "Not now",
            style = CadenceType.bodyStrong,
            color = CadenceColors.Fg3,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onDone)
                .padding(vertical = 12.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StepHeader(eyebrow: String, title: String) {
    Text(text = eyebrow, style = CadenceType.eyebrow, color = CadenceColors.Brand500)
    Spacer(Modifier.height(6.dp))
    Text(text = title, style = CadenceType.h2, color = CadenceColors.Fg1)
}

@Composable
private fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.md))
            .background(CadenceColors.Bg2)
            .border(1.dp, CadenceColors.Line2, RoundedCornerShape(Radii.md))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CadenceColors.Brand500.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = CadenceColors.Brand500,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
            Spacer(Modifier.height(2.dp))
            Text(text = body, style = CadenceType.small, color = CadenceColors.Fg3)
        }
    }
}

@Composable
private fun OnboardingNumberField(
    label: String,
    sublabel: String,
    value: String,
    onValueChange: (String) -> Unit,
    unit: String,
    keyboardType: KeyboardType,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.md))
            .background(CadenceColors.Bg2)
            .border(1.dp, CadenceColors.Line2, RoundedCornerShape(Radii.md))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
                Spacer(Modifier.height(4.dp))
                Text(text = sublabel, style = CadenceType.small, color = CadenceColors.Fg3)
            }
            Spacer(Modifier.width(16.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = CadenceType.h2.copy(color = CadenceColors.Brand500, textAlign = TextAlign.End),
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(),
                singleLine = true,
                modifier = Modifier
                    .width(110.dp)
                    .onFocusChanged { },
                decorationBox = { inner ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radii.sm))
                            .background(CadenceColors.Bg0)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { inner() }
                        Spacer(Modifier.width(4.dp))
                        Text(unit, style = CadenceType.bodyStrong, color = CadenceColors.Fg3)
                    }
                }
            )
        }
    }
}

private fun formatWeightForInput(kg: Float): String {
    val rounded = kotlin.math.round(kg * 10f) / 10f
    return if (rounded == kotlin.math.floor(rounded)) rounded.toInt().toString() else rounded.toString()
}

@Composable
private fun WorkoutCard(
    summary: WorkoutSummary,
    source: WorkoutSource?,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, shape)
            .clickable(onClick = onClick)
            .padding(Spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Spacing.s3)
    ) {
        Column {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = summary.tag.uppercase(),
                    style = CadenceType.eyebrow,
                    color = CadenceColors.Fg3
                )
                if (source != null && source !is WorkoutSource.Bundled) {
                    Text(
                        text = "· ${source.attribution}",
                        style = CadenceType.eyebrow,
                        color = CadenceColors.Brand500
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = summary.workout.name,
                style = CadenceType.h3.copy(fontSize = 18.sp),
                color = CadenceColors.Fg1
            )
        }
        ZoneBar(segments = summary.zoneSegments(), height = 8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MetaPill(text = formatDurationPretty(summary.totalSeconds).uppercase())
            MetaPill(text = "TSS ${summary.tss}")
            MetaPill(text = "IF ${"%.2f".format(summary.intensityFactor)}")
        }
    }
}

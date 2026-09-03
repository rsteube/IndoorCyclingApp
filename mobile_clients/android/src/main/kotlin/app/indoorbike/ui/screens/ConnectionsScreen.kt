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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.indoorbike.LocalAppContainer
import app.indoorbike.sync.FitTrackeeUploader
import app.indoorbike.ui.ExpandableCard
import app.indoorbike.ui.IntervalsIcuCard
import app.indoorbike.ui.IntervalsTextField
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.SecondaryBtn
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Where a ride can be sent after it's finished.
 *
 * Split out of Settings because the list only grows: Intervals.icu, a rider's
 * own FitTrackee, and whatever comes next. Settings should stay a page you can
 * take in at a glance, and a service that needs a server address, credentials
 * and a sport mapping does not belong inline on it.
 *
 * Everything here is off until a rider fills it in. Nothing on this screen
 * causes a single network call by existing.
 */
@Composable
fun ConnectionsScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CadenceColors.Bg0)
    ) {
        AppBar(
            title = "Connections",
            eyebrow = "SEND RIDES TO",
            leading = {
                IconBtn(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack
                )
            }
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.s5)
        ) {
            Spacer(Modifier.height(Spacing.s4))
            Text(
                text = "Finished rides can be uploaded to a training platform. Each one is " +
                    "off until you set it up, and nothing is contacted before then.",
                style = CadenceType.body,
                color = CadenceColors.Fg3
            )
            Spacer(Modifier.height(Spacing.s5))

            IntervalsIcuCard()

            Spacer(Modifier.height(Spacing.s4))

            FitTrackeeCard()

            Spacer(Modifier.height(Spacing.s6))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

/**
 * FitTrackee is self-hosted, so unlike Intervals.icu there is no service to
 * talk to until the rider names their own server. Sign-in trades an email and
 * password for a token; only the token is kept.
 */
@Composable
private fun FitTrackeeCard() {
    val container = LocalAppContainer.current
    val settings = container.userSettings
    val uploader = container.fitTrackeeUploader
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val url by settings.fitTrackeeUrl.collectAsState()
    val token by settings.fitTrackeeToken.collectAsState()
    val sportId by settings.fitTrackeeSportId.collectAsState()
    val autoUpload by settings.fitTrackeeAutoUpload.collectAsState()
    val signedIn = !token.isNullOrBlank()

    var expanded by rememberSaveable { mutableStateOf(false) }
    var urlDraft by remember(url) { mutableStateOf(url.orEmpty()) }
    var email by remember { mutableStateOf("") }
    // Deliberately not remembered across recomposition boundaries any longer
    // than the card is open, and never written anywhere. It is used for one
    // request and then cleared.
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var sports by remember { mutableStateOf<List<FitTrackeeUploader.Sport>>(emptyList()) }

    // The sport list is per-instance, so it can only be fetched once there is
    // an instance and a token to ask with.
    LaunchedEffect(signedIn, url) {
        if (signedIn) sports = uploader.sports()
    }

    ExpandableCard(
        icon = Icons.Outlined.Dns,
        iconTint = CadenceColors.Brand500,
        title = "FitTrackee",
        statusText = when {
            signedIn && sportId > 0 && autoUpload -> "Connected · auto-upload on"
            signedIn && sportId > 0 -> "Connected · manual upload"
            signedIn -> "Connected · pick a sport"
            else -> "Not connected · optional"
        },
        statusColor = if (signedIn) CadenceColors.Success else CadenceColors.Fg3,
        expanded = expanded,
        onToggle = { expanded = !expanded }
    ) {
        Text(
            text = "Your own FitTrackee server. Rides upload as FIT files, so they arrive " +
                "with power, cadence, heart rate and distance. Your password is used once " +
                "to sign in and is never stored.",
            style = CadenceType.body,
            color = CadenceColors.Fg3
        )
        Spacer(Modifier.height(Spacing.s4))

        if (!signedIn) {
            IntervalsTextField(
                label = "SERVER ADDRESS",
                placeholder = "https://fittrackee.example.org",
                value = urlDraft,
                onValueChange = { urlDraft = it },
                keyboardType = KeyboardType.Uri,
                visualTransformation = VisualTransformation.None,
            )
            IntervalsTextField(
                label = "EMAIL",
                placeholder = "you@example.org",
                value = email,
                onValueChange = { email = it },
                keyboardType = KeyboardType.Email,
                visualTransformation = VisualTransformation.None,
            )
            IntervalsTextField(
                label = "PASSWORD",
                placeholder = "Used once, not saved",
                value = password,
                onValueChange = { password = it },
                keyboardType = KeyboardType.Password,
                visualTransformation = PasswordVisualTransformation(),
            )
            Spacer(Modifier.height(Spacing.s3))
            PrimaryBtn(
                text = if (busy) "Connecting…" else "Connect",
                leadingIcon = null,
                onClick = {
                    if (busy) return@PrimaryBtn
                    busy = true
                    scope.launch {
                        when (val r = uploader.signIn(urlDraft, email.trim(), password)) {
                            is FitTrackeeUploader.SignIn.Success -> {
                                settings.setFitTrackeeUrl(urlDraft)
                                settings.setFitTrackeeToken(r.token)
                                password = ""
                                Toast.makeText(context, "Connected to FitTrackee", Toast.LENGTH_SHORT).show()
                            }
                            is FitTrackeeUploader.SignIn.Failure ->
                                Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
                        }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                glow = false
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = CadenceColors.Success,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = url.orEmpty(),
                    style = CadenceType.tick,
                    color = CadenceColors.Success
                )
            }

            Spacer(Modifier.height(Spacing.s4))
            Text(text = "SPORT", style = CadenceType.eyebrow, color = CadenceColors.Fg3)
            Spacer(Modifier.height(Spacing.s2))
            if (sports.isEmpty()) {
                Text(
                    text = "Couldn't read the sport list from your server. Rides can't be " +
                        "filed without one.",
                    style = CadenceType.tick,
                    color = CadenceColors.Fg3
                )
            } else {
                // Sport ids differ per instance, so the rider picks from what
                // their own server actually offers rather than a guess.
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                    for (sport in sports) {
                        val selected = sport.id == sportId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radii.sm))
                                .background(if (selected) CadenceColors.Bg2 else CadenceColors.Bg1)
                                .border(
                                    1.dp,
                                    if (selected) CadenceColors.Brand500 else CadenceColors.Line2,
                                    RoundedCornerShape(Radii.sm)
                                )
                                .clickable { settings.setFitTrackeeSportId(sport.id) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = sport.label,
                                style = CadenceType.body,
                                color = if (selected) CadenceColors.Fg1 else CadenceColors.Fg2,
                                modifier = Modifier.weight(1f)
                            )
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = CadenceColors.Brand500,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.s4))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-upload finished rides",
                        style = CadenceType.bodyStrong,
                        color = CadenceColors.Fg1
                    )
                    Text(
                        text = if (sportId > 0) "Sent as soon as a ride is saved."
                        else "Pick a sport first.",
                        style = CadenceType.tick,
                        color = CadenceColors.Fg3
                    )
                }
                Switch(
                    // Always operable, like the Intervals one: a rider must be
                    // able to turn a thing off whatever else is or isn't set.
                    checked = autoUpload,
                    onCheckedChange = { settings.setFitTrackeeAutoUpload(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = CadenceColors.OnBrand,
                        checkedTrackColor = CadenceColors.Brand500,
                        uncheckedThumbColor = CadenceColors.Fg3,
                        uncheckedTrackColor = CadenceColors.Bg2,
                        uncheckedBorderColor = CadenceColors.Line2,
                    )
                )
            }

            Spacer(Modifier.height(Spacing.s3))
            SecondaryBtn(
                text = "Sign out",
                onClick = {
                    settings.setFitTrackeeToken(null)
                    settings.setFitTrackeeAutoUpload(false)
                    sports = emptyList()
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

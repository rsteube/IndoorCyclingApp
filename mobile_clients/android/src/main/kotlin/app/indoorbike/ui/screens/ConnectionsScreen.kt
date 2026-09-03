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
 * talk to until the rider names their own server.
 *
 * Sign-in happens in the browser, on the rider's own instance, and this app
 * never sees their password. What comes back is a token scoped to uploading
 * workouts, which they can revoke from FitTrackee without changing anything
 * else. The client id and secret are theirs, created on their own server,
 * because FitTrackee won't let a client register itself.
 */
@Composable
private fun FitTrackeeCard() {
    val container = LocalAppContainer.current
    val settings = container.userSettings
    val uploader = container.fitTrackeeUploader
    val context = LocalContext.current

    val url by settings.fitTrackeeUrl.collectAsState()
    val clientId by settings.fitTrackeeClientId.collectAsState()
    val clientSecret by settings.fitTrackeeClientSecret.collectAsState()
    val accessToken by settings.fitTrackeeAccessToken.collectAsState()
    val sportId by settings.fitTrackeeSportId.collectAsState()
    val autoUpload by settings.fitTrackeeAutoUpload.collectAsState()
    val connected = !accessToken.isNullOrBlank()

    var expanded by rememberSaveable { mutableStateOf(false) }
    var sports by remember { mutableStateOf<List<FitTrackeeUploader.Sport>>(emptyList()) }

    // Fetched from the rider's own instance once there's a token to ask with.
    // Re-run on returning from the browser, which is when a token first exists.
    LaunchedEffect(connected, url) {
        sports = if (connected) uploader.sports() else emptyList()
    }

    ExpandableCard(
        icon = Icons.Outlined.Dns,
        iconTint = CadenceColors.Brand500,
        title = "FitTrackee",
        statusText = when {
            connected && sportId > 0 && autoUpload -> "Connected · auto-upload on"
            connected && sportId > 0 -> "Connected · manual upload"
            connected -> "Connected · pick a sport"
            else -> "Not connected · optional"
        },
        statusColor = if (connected) CadenceColors.Success else CadenceColors.Fg3,
        expanded = expanded,
        onToggle = { expanded = !expanded }
    ) {
        Text(
            text = "Your own FitTrackee server. Worth knowing before you set it up: " +
                "FitTrackee is built for outdoor activities and only accepts files that " +
                "contain GPS coordinates, which an indoor ride has none of. Rides " +
                "therefore arrive as an entry with the date, duration and estimated " +
                "distance, and the power, cadence and heart rate figures written into " +
                "the notes. There is no power graph. Export a FIT file if you need one.",
            style = CadenceType.body,
            color = CadenceColors.Fg3
        )
        Spacer(Modifier.height(Spacing.s4))

        if (!connected) {
            Text(
                text = "You approve this in your browser, on your own server, so the app " +
                    "never sees your password. In FitTrackee, go to Profile → Apps → " +
                    "Create an app, allow it to write workouts, and use this as the " +
                    "redirect URI:",
                style = CadenceType.tick,
                color = CadenceColors.Fg3
            )
            Spacer(Modifier.height(Spacing.s2))
            // Shown so it can be typed into FitTrackee exactly. It has to match
            // what the app sends or the server refuses the request.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.sm))
                    .background(CadenceColors.Bg2)
                    .border(1.dp, CadenceColors.Line2, RoundedCornerShape(Radii.sm))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = FitTrackeeUploader.REDIRECT_URI,
                    style = CadenceType.bodyStrong,
                    color = CadenceColors.Brand300,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "COPY",
                    style = CadenceType.eyebrow,
                    color = CadenceColors.Fg3,
                    modifier = Modifier.clickable {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(
                            android.content.ClipData.newPlainText(
                                "redirect uri", FitTrackeeUploader.REDIRECT_URI
                            )
                        )
                        Toast.makeText(context, "Redirect URI copied", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            Spacer(Modifier.height(Spacing.s4))
            // Written through as typed rather than on a commit: opening the
            // browser can get this app killed, and a client id that only lived
            // in a text field would not survive to redeem the code.
            IntervalsTextField(
                label = "SERVER ADDRESS",
                placeholder = "https://fittrackee.example.org",
                value = url.orEmpty(),
                onValueChange = { settings.setFitTrackeeUrl(it) },
                keyboardType = KeyboardType.Uri,
                visualTransformation = VisualTransformation.None,
            )
            IntervalsTextField(
                label = "CLIENT ID",
                placeholder = "From your FitTrackee app",
                value = clientId.orEmpty(),
                onValueChange = { settings.setFitTrackeeClientId(it) },
                keyboardType = KeyboardType.Ascii,
                visualTransformation = VisualTransformation.None,
            )
            IntervalsTextField(
                label = "CLIENT SECRET",
                placeholder = "From your FitTrackee app",
                value = clientSecret.orEmpty(),
                onValueChange = { settings.setFitTrackeeClientSecret(it) },
                keyboardType = KeyboardType.Ascii,
                visualTransformation = PasswordVisualTransformation(),
            )
            Spacer(Modifier.height(Spacing.s3))
            PrimaryBtn(
                text = "Connect in browser",
                leadingIcon = null,
                onClick = {
                    val authorize = uploader.authorizeUrl()
                    if (authorize == null) {
                        Toast.makeText(
                            context,
                            "Enter your server address and client id first",
                            Toast.LENGTH_LONG
                        ).show()
                        return@PrimaryBtn
                    }
                    try {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(authorize)
                            )
                        )
                    } catch (t: Throwable) {
                        Toast.makeText(context, "No browser to open", Toast.LENGTH_LONG).show()
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
                Text(text = url.orEmpty(), style = CadenceType.tick, color = CadenceColors.Success)
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
                // Sport ids differ between instances, so the rider picks from
                // what their own server actually offers rather than a guess.
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
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                text = "Disconnect",
                onClick = {
                    // Tokens only. The client id and secret stay, so
                    // reconnecting is one tap rather than a re-setup.
                    uploader.signOut()
                    settings.setFitTrackeeAutoUpload(false)
                    sports = emptyList()
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

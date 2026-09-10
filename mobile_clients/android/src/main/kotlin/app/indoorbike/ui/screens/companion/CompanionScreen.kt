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
package app.indoorbike.ui.screens.companion

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.LocalAppContainer
import app.indoorbike.companion.CompanionPairing
import app.indoorbike.shared.ble.TrainerConnectionState
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.SecondaryBtn
import app.indoorbike.ui.components.SlateCard
import app.indoorbike.ui.components.listBottomPadding
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.DisplayFontFamily
import app.indoorbike.ui.theme.Spacing

/**
 * Pairing, and then out of the way.
 *
 * The screen exists for the thirty seconds between choosing the mode and the
 * companion taking over. It opens the socket, shows the rider everything the
 * other device might need to find it, and steps aside the moment a road
 * arrives: [onRideReady] fires on the *first world frame*, not merely on
 * connection, because a companion that has connected but sent nothing is not
 * yet driving anything and putting a rider on a HUD with no road on it would
 * be a lie.
 *
 * ## Two ways in, because networks are unreliable in different ways
 *
 * Discovery is the pleasant path and the one that fails silently on guest
 * Wi-Fi, mesh routers and most corporate networks. So the address, the port and
 * the pairing code are always on screen, set large, and in the order a
 * companion's connect form asks for them. A rider whose network eats broadcast
 * and multicast should lose the convenience, not the feature.
 *
 * ## Why there is no QR code
 *
 * There was one, carrying the whole connection string. It assumed the thing
 * being paired had a camera. A companion runs on whatever drives the screen the
 * rider is watching, which is usually a desktop or a laptop on a trainer desk
 * with no camera pointed anywhere useful, so the scan was a large block of
 * screen that nothing in the room could perform, pushing the one fact a
 * companion cannot start without below the fold. Everything the code carried is
 * on the screen as text instead.
 */
@Composable
fun CompanionScreen(
    onBack: () -> Unit,
    onRideReady: () -> Unit,
) {
    val container = LocalAppContainer.current
    val host = container.companionHost
    val status by host.status.collectAsState()

    val trainerState by container.trainerManager.connectionState.collectAsState()
    val isMockTrainer by container.trainerManager.isMockTrainer.collectAsState()
    val trainerLive = trainerState == TrainerConnectionState.Connected || isMockTrainer

    // The socket lives exactly as long as the rider is trying to pair or ride.
    // Handing off to the ride screen leaves it running on purpose - that screen
    // owns it from there and closes it when the ride is done. Tracked as a flag
    // rather than inferred from `receivingRoad`, because the debug entry below
    // hands off without a road and would otherwise close the socket on its way
    // to the screen that exists to drive it.
    var handedOff by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        host.start()
        onDispose { if (!handedOff) host.stop() }
    }

    LaunchedEffect(status.receivingRoad) {
        if (status.receivingRoad) {
            handedOff = true
            onRideReady()
        }
    }

    // A companion asks to be remembered right after it connects, which is
    // usually while this screen is still up.
    val pendingPairing by host.pendingPairing.collectAsState()
    pendingPairing?.let {
        CompanionPairingDialog(
            pending = it,
            onAccept = host::acceptPairing,
            onDecline = host::declinePairing,
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0),
    ) {
        AppBar(
            title = "Companion",
            eyebrow = "Ride mode · Alpha",
            leading = {
                IconBtn(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                )
            },
        )

        LazyColumn(
            modifier = Modifier.widthIn(max = 640.dp),
            contentPadding = PaddingValues(
                start = Spacing.s5,
                end = Spacing.s5,
                top = Spacing.s4,
                bottom = listBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.s3),
        ) {
            item(key = "what-this-is") {
                WhatThisIsCard()
            }

            item(key = "status") {
                ConnectionStatusCard(
                    controllerName = status.controllerName,
                    running = status.running,
                    error = status.error,
                )
            }

            if (!trainerLive) {
                item(key = "trainer") {
                    NoticeCard(
                        title = "No trainer paired",
                        body = "A companion can describe the road, but something has to " +
                            "apply it. Pair a trainer before you start.",
                    )
                }
            }

            if (status.running) {
                // Address first, then the code. That is the order a companion's
                // connect form asks for them, and the address is the half that
                // cannot be guessed or retried.
                item(key = "address") {
                    AddressCard(
                        addresses = status.addresses,
                        port = status.port,
                        advertising = status.advertising,
                    )
                }
                item(key = "code") {
                    PairingCodeCard(code = status.token)
                }
            }

            // Debug builds only: skip the wait and go straight to the HUD,
            // where the manual-trainer harness lives. Someone whose companion
            // is the thing being debugged should not be stranded here because
            // it connected and then stopped sending.
            if (app.indoorbike.BuildConfig.DEBUG) {
                item(key = "debug-enter") {
                    SecondaryBtn(
                        text = "Debug: open the HUD without a companion",
                        onClick = {
                            handedOff = true
                            host.prepareRideWithoutCompanion()
                            onRideReady()
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.s3),
                    )
                }
            }

            item(key = "cancel") {
                SecondaryBtn(
                    text = "Cancel",
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.s3),
                )
            }
        }
    }
}

@Composable
private fun ConnectionStatusCard(
    controllerName: String?,
    running: Boolean,
    error: String?,
) {
    val (headline, detail) = when {
        error != null -> "Could not start" to error
        !running -> "Opening the socket" to "One moment."
        controllerName == null ->
            "Waiting for a companion" to
                "Start your companion app and point it at this phone."
        else ->
            (controllerName + " connected") to
                "Waiting for it to start describing the road. The ride begins on its first update."
    }

    SlateCard {
        Text(text = headline, style = CadenceType.h3, color = CadenceColors.Fg1)
        Text(
            text = detail,
            style = CadenceType.body,
            color = if (error != null) CadenceColors.Warning else CadenceColors.Fg2,
            modifier = Modifier.padding(top = Spacing.s2),
        )
    }
}

/**
 * What this screen is, for somebody who tapped "Companion" to find out.
 *
 * It is first on the screen and it is not dismissible, because everything
 * below it - an address, a port, a six-character code - is meaningless to a
 * rider who does not already have somewhere to type it. Without this they meet
 * a pairing screen for a thing they have never heard of and conclude the app
 * is broken.
 *
 * Says three things, in the order they are needed: this does nothing by
 * itself, here is what it needs, and here is what to ride instead if you do
 * not have one.
 */
@Composable
private fun WhatThisIsCard() {
    val context = LocalContext.current
    SlateCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Needs a second app",
                style = CadenceType.h3,
                color = CadenceColors.Fg1,
            )
            Spacer(Modifier.width(Spacing.s2))
            Text(
                text = "Alpha",
                style = CadenceType.micro,
                color = CadenceColors.Fg3,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(CadenceColors.Bg2)
                    .padding(horizontal = Spacing.s2, vertical = 2.dp),
            )
        }
        Text(
            text = "Companion mode does nothing on its own. It hands your trainer " +
                "over to a separate program - a game, a route player, a training " +
                "world - running on another device on the same network. That program " +
                "decides the road; this app turns it into resistance and records the " +
                "ride.",
            style = CadenceType.body,
            color = CadenceColors.Fg2,
            modifier = Modifier.padding(top = Spacing.s2),
        )
        Text(
            text = "If you do not have one running, there is nothing here to connect " +
                "to. To ride a real road with nothing but this phone, use Route mode " +
                "instead.",
            style = CadenceType.body,
            color = CadenceColors.Fg2,
            modifier = Modifier.padding(top = Spacing.s2),
        )
        Text(
            text = "Alpha: the protocol is still moving, and a companion built " +
                "against it today may need updating.",
            style = CadenceType.small,
            color = CadenceColors.Fg3,
            modifier = Modifier.padding(top = Spacing.s2),
        )
        SecondaryBtn(
            text = "Find or build a companion",
            onClick = {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(COMPANION_GUIDE_URL))
                    )
                } catch (t: Throwable) {
                    Toast.makeText(context, "No browser to open", Toast.LENGTH_LONG).show()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.s3),
        )
    }
}

/** The public explanation, which is where anyone wanting one should start. */
private const val COMPANION_GUIDE_URL = "https://indoorbike.app/companion/"

@Composable
private fun NoticeCard(title: String, body: String) {
    SlateCard {
        Text(text = title, style = CadenceType.h3, color = CadenceColors.Warning)
        Text(
            text = body,
            style = CadenceType.body,
            color = CadenceColors.Fg2,
            modifier = Modifier.padding(top = Spacing.s2),
        )
    }
}

/**
 * The size the address and the port are set at.
 *
 * Deliberately not [CadenceType.displayM], which the rest of the app uses for a
 * number worth staring at. An IPv4 address runs to fifteen characters where a
 * cadence is three, and forty points of extra-bold walks off the side of a
 * phone. Thirty, with a heading's tighter tracking, fits the longest address
 * this can produce inside the narrowest card the app draws. Tabular figures
 * stop the digits shuffling sideways as the last octet changes.
 */
private val AddressValue = CadenceType.h1.copy(
    fontSize = 30.sp,
    lineHeight = 36.sp,
    fontFeatureSettings = "tnum",
)

/**
 * Where to point the companion.
 *
 * The first card and the largest type on the screen, because it is the one
 * thing a companion cannot start without, and the thing the rider retypes on
 * another machine. It used to sit underneath the pairing code and a QR block,
 * which put it below the fold on a phone: the rider was scrolling past two
 * cards to reach the only one that is not optional.
 *
 * Host and port are separate values rather than one `ws://` URL, because that
 * is how a companion asks for them. A connect form has two fields. The port is
 * stated once even when there are several addresses, since it is the same
 * socket on all of them.
 */
@Composable
private fun AddressCard(
    addresses: List<String>,
    port: Int,
    advertising: Boolean,
) {
    SlateCard {
        if (addresses.isEmpty()) {
            Text(text = "Address", style = CadenceType.eyebrow, color = CadenceColors.Fg3)
            Text(
                text = "This phone has no network address. Join the same Wi-Fi as the " +
                    "device running the companion.",
                style = CadenceType.body,
                color = CadenceColors.Warning,
                modifier = Modifier.padding(top = Spacing.s2),
            )
        } else {
            Text(
                text = if (addresses.size > 1) "Addresses" else "Address",
                style = CadenceType.eyebrow,
                color = CadenceColors.Fg3,
            )
            // Every address, not a guess at the right one. A phone on Wi-Fi
            // while tethering has more than one, and the app cannot know which
            // one the other machine can see.
            addresses.forEach { address ->
                Text(
                    text = address,
                    style = AddressValue,
                    color = CadenceColors.Fg1,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.s1),
                )
            }

            Text(
                text = "Port",
                style = CadenceType.eyebrow,
                color = CadenceColors.Fg3,
                modifier = Modifier.padding(top = Spacing.s4),
            )
            Text(
                text = port.toString(),
                style = AddressValue,
                color = CadenceColors.Fg1,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.s1),
            )
            Text(
                // Worth saying on the screen that shows it. The socket is opened
                // fresh for every ride, so a companion still holding the number
                // from last time is pointing at something that has closed, and
                // that failure looks exactly like a network problem.
                text = "New for every ride. A companion that remembered the last one is " +
                    "pointing at a socket that has closed.",
                style = CadenceType.small,
                color = CadenceColors.Fg3,
                modifier = Modifier.padding(top = Spacing.s2),
            )
        }

        Text(
            text = if (advertising)
                "Discoverable on this network, so a companion that looks for phones " +
                    "should find all of this by itself."
            else "Not discoverable on this network. Type the address and port into the " +
                "companion by hand.",
            style = CadenceType.small,
            color = if (advertising) CadenceColors.Success else CadenceColors.Fg3,
            modifier = Modifier.padding(top = Spacing.s4),
        )
    }
}

/**
 * The pairing code, set large and spaced out.
 *
 * A rider types this from a phone propped on the handlebars, so it is sized to
 * be read at arm's length and the characters people confuse are not in the
 * alphabet at all (see [CompanionPairing]).
 */
@Composable
private fun PairingCodeCard(code: String) {
    SlateCard {
        Text(text = "Pairing code", style = CadenceType.eyebrow, color = CadenceColors.Fg3)
        Text(
            text = code,
            fontFamily = DisplayFontFamily,
            fontSize = 44.sp,
            lineHeight = 52.sp,
            color = CadenceColors.Fg1,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.s2),
            textAlign = TextAlign.Center,
        )
        Text(
            // Debug builds pin the code, so the release copy would be a lie
            // there - and a developer who read it and then wondered why the
            // same code kept working would be right to distrust the rest.
            text = if (app.indoorbike.BuildConfig.DEBUG)
                "Fixed in debug builds so you can stop reading it. Release builds " +
                    "use a fresh code for every ride."
            else "New for every ride. It is never stored and never reused.",
            style = CadenceType.small,
            color = CadenceColors.Fg3,
            modifier = Modifier.padding(top = Spacing.s2),
        )
    }
}

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
package app.indoorbike.ui.screens.companion

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
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
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

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
 * ## Three ways in, because networks are unreliable in different ways
 *
 * Discovery over mDNS is the pleasant path and the one that fails silently on
 * guest Wi-Fi, mesh routers and most corporate networks. So the address and
 * port are always on screen, the QR carries the whole connection string
 * including the pairing code, and the code itself can be typed. A rider whose
 * network eats multicast should lose a scan, not the feature.
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

    Column(
        modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0),
    ) {
        AppBar(
            title = "Companion",
            eyebrow = "Ride mode",
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
                item(key = "code") {
                    PairingCodeCard(code = status.token)
                }
                item(key = "qr") {
                    status.qrPayload?.let { QrCard(payload = it) }
                }
                item(key = "manual") {
                    ManualConnectCard(
                        addresses = status.addresses,
                        port = status.port,
                        advertising = status.advertising,
                    )
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
            text = "New for every ride. It is never stored and never reused.",
            style = CadenceType.small,
            color = CadenceColors.Fg3,
            modifier = Modifier.padding(top = Spacing.s2),
        )
    }
}

@Composable
private fun QrCard(payload: String) {
    val bitmap = remember(payload) { encodeQr(payload) }
    SlateCard {
        Text(text = "Or scan", style = CadenceType.eyebrow, color = CadenceColors.Fg3)
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.s3),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Pairing QR code",
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(Radii.md))
                        .background(CadenceColors.Fg1)
                        .padding(Spacing.s3)
                        // Nearest-neighbour rather than the default smoothing:
                        // a blurred QR is a QR that takes three attempts to scan.
                        .graphicsLayer { },
                )
            } else {
                Text(
                    text = "Could not draw the code. Use the address below.",
                    style = CadenceType.small,
                    color = CadenceColors.Fg3,
                )
            }
        }
        Text(
            text = "Carries the address, port and pairing code together, so a scan " +
                "is all the companion needs.",
            style = CadenceType.small,
            color = CadenceColors.Fg3,
            modifier = Modifier.padding(top = Spacing.s3),
        )
    }
}

@Composable
private fun ManualConnectCard(
    addresses: List<String>,
    port: Int,
    advertising: Boolean,
) {
    SlateCard {
        Text(text = "Connect by hand", style = CadenceType.eyebrow, color = CadenceColors.Fg3)
        if (addresses.isEmpty()) {
            Text(
                text = "This phone has no network address. Join the same Wi-Fi as the " +
                    "device running the companion.",
                style = CadenceType.body,
                color = CadenceColors.Warning,
                modifier = Modifier.padding(top = Spacing.s2),
            )
        } else {
            // Every address, not a guess at the right one. A phone on Wi-Fi
            // while tethering has more than one and the app cannot know which
            // the other machine can see.
            addresses.forEach { address ->
                Text(
                    text = CompanionPairing.websocketUrl(address, port),
                    style = CadenceType.body,
                    color = CadenceColors.Fg1,
                    modifier = Modifier.padding(top = Spacing.s2),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.s3),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
        ) {
            Text(
                text = if (advertising) "Discoverable on this network"
                else "Not discoverable - use the QR or the address",
                style = CadenceType.small,
                color = if (advertising) CadenceColors.Success else CadenceColors.Fg3,
            )
        }
    }
}

/**
 * Render the pairing payload as a QR bitmap.
 *
 * High error correction because the thing scanning it is a phone camera
 * pointing at a phone screen across a garage, often at an angle and usually
 * with a fingerprint on the lens.
 */
private fun encodeQr(payload: String, sizePx: Int = 512): ImageBitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val offset = y * width
        for (x in 0 until width) {
            pixels[offset + x] =
                if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        .apply { setPixels(pixels, 0, width, 0, 0, width, height) }
        .asImageBitmap()
}.getOrNull()

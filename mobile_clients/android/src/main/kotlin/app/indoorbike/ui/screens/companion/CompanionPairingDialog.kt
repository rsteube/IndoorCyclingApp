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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.indoorbike.companion.CompanionHost
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.SecondaryBtn
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing

/**
 * The one moment permanent pairing asks for a person.
 *
 * Everything else about a paired companion happens with nobody watching: it
 * finds the phone, proves who it is, and the ride begins. That is the point of
 * it. Which is exactly why the moment the trust is *granted* has to be a
 * deliberate act on this screen, by the rider, with the other device's name in
 * front of them.
 *
 * ## Why the wording is careful
 *
 * The rider is being asked to hand something a permanent key to their trainer,
 * and "Pair with Alpine Climb?" does not convey that. So the dialog says what
 * changes (no code next time), what it grants (control of the trainer whenever
 * both are on the same network), and that it can be taken back. A rider who
 * would say no if they understood the question should be able to understand the
 * question.
 *
 * Not dismissible by tapping outside. A stray tap on a busy screen is not
 * consent, and the companion is waiting for an answer either way.
 */
@Composable
fun CompanionPairingDialog(
    pending: CompanionHost.PendingPairing,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDecline,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(Radii.lg))
                .background(CadenceColors.Bg1)
                .padding(Spacing.s6),
            verticalArrangement = Arrangement.spacedBy(Spacing.s4),
        ) {
            Text(
                text = "Remember this companion?",
                style = CadenceType.h2,
                color = CadenceColors.Fg1,
            )
            Text(
                text = "\"" + pending.name + "\" wants to pair with this phone permanently. " +
                    "After this it can connect on its own whenever the two are on the same " +
                    "network, with no pairing code, and drive your trainer.",
                style = CadenceType.body,
                color = CadenceColors.Fg2,
            )
            Text(
                text = "Only agree if this is a program you set up yourself. You can undo it " +
                    "later from the companion screen.",
                style = CadenceType.small,
                color = CadenceColors.Fg3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s3)) {
                SecondaryBtn(
                    text = "Not now",
                    onClick = onDecline,
                    modifier = Modifier.weight(1f),
                )
                PrimaryBtn(
                    text = "Remember it",
                    onClick = onAccept,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

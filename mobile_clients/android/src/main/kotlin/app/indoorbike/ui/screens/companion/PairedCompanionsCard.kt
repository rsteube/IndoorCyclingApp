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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.indoorbike.shared.companion.PairedCompanion
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.SlateCard
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Spacing

/**
 * The companions the rider has trusted, and the way to stop trusting them.
 *
 * This card is the other half of the pairing dialog. That one says the trust
 * can be undone later from this screen, and a promise made while asking for a
 * permanent key is one that has to be kept somewhere the rider can find it.
 *
 * Hidden entirely when nothing is paired. A rider who has never paired anything
 * does not need to be told about a list that would be empty, and the pairing
 * screen already has three cards on it.
 *
 * Forgetting takes effect at once and without a confirmation step. That is a
 * deliberate asymmetry: granting the trust is the guarded action, because it is
 * the one that hands something a key. Taking it back costs a mis-tapping rider
 * one pairing code next time, which is cheaper than a dialog on every row.
 */
@Composable
fun PairedCompanionsCard(
    companions: List<PairedCompanion>,
    onForget: (String) -> Unit,
) {
    if (companions.isEmpty()) return

    SlateCard {
        Text(
            text = "Paired companions",
            style = CadenceType.h3,
            color = CadenceColors.Fg1,
        )
        Text(
            text = "These connect on their own, with no pairing code, whenever they find this " +
                "phone on the same network. Forget one and it needs the code again.",
            style = CadenceType.body,
            color = CadenceColors.Fg2,
            modifier = Modifier.padding(top = Spacing.s2),
        )
        Spacer(Modifier.height(Spacing.s3))
        companions.forEachIndexed { index, companion ->
            if (index > 0) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(CadenceColors.Bg2)
                        .padding(vertical = Spacing.s2)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.s2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = companion.name,
                        style = CadenceType.body,
                        color = CadenceColors.Fg1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = lastSeen(companion),
                        style = CadenceType.small,
                        color = CadenceColors.Fg3,
                    )
                }
                IconBtn(
                    icon = Icons.Outlined.DeleteOutline,
                    contentDescription = "Forget " + companion.name,
                    onClick = { onForget(companion.deviceId) },
                )
            }
        }
    }
}

/**
 * Coarse on purpose, the same way the home screen's is: the useful distinction
 * is "this is the one I use" against "what even is that".
 */
private fun lastSeen(companion: PairedCompanion): String {
    val epoch = companion.lastSeenEpochMs.takeIf { it > 0 } ?: companion.pairedAtEpochMs
    if (epoch <= 0) return "Paired"
    val minutes = (System.currentTimeMillis() - epoch) / 60_000
    return when {
        minutes < 1 -> "Connected just now"
        minutes < 60 -> "Last connected $minutes min ago"
        minutes < 60 * 24 -> "Last connected ${minutes / 60} h ago"
        else -> "Last connected ${minutes / (60 * 24)} d ago"
    }
}

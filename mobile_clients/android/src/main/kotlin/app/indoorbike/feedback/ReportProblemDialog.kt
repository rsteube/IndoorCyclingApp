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
package app.indoorbike.feedback

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Confirmation shown before a bug report is drafted. It spells out — before the
 * user commits — exactly what will happen and what's collected: an email draft
 * opens (nothing is sent automatically), it includes device info and recent
 * diagnostic logs, and no personal data is gathered.
 *
 * Render conditionally; [onDismiss] is called after the draft opens or on cancel.
 * [errorContext] is the specific error being reported, if any.
 */
@Composable
fun ReportProblemDialog(
    errorContext: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CadenceColors.Bg1,
        titleContentColor = CadenceColors.Fg1,
        textContentColor = CadenceColors.Fg2,
        shape = RoundedCornerShape(Radii.md),
        title = { Text(text = "Report a problem", style = CadenceType.h3) },
        text = {
            Text(
                style = CadenceType.small,
                color = CadenceColors.Fg2,
                text = "This opens your email app with a draft to the developer. " +
                    "Nothing is sent automatically: you review it and decide whether to send.\n\n" +
                    "The draft includes your device model, Android version, app version, and recent " +
                    "diagnostic logs (technical app state only). No personal information (no name, " +
                    "email, body metrics, or location) is collected."
            )
        },
        confirmButton = {
            Text(
                text = "Open email draft",
                style = CadenceType.bodyStrong,
                color = CadenceColors.Brand500,
                modifier = Modifier
                    .clickable {
                        BugReport.launchEmailDraft(context, errorContext)
                        onDismiss()
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        },
        dismissButton = {
            Text(
                text = "Cancel",
                style = CadenceType.bodyStrong,
                color = CadenceColors.Fg3,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    )
}

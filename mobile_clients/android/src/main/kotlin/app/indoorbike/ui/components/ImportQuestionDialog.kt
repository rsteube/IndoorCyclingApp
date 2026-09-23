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
package app.indoorbike.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.indoorbike.shared.library.ImportQuestions
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Asks whether an import may replace workouts the rider already has.
 *
 * Shown over whatever screen is up, because an import can arrive from
 * anywhere: a link tapped in a chat, a file shared from another app, a QR
 * code. Keep both leads: it loses nothing, and it is the right answer to a
 * link the rider did not expect. Dismissing is Cancel.
 */
@Composable
fun ImportQuestionDialog(questions: ImportQuestions) {
    val question by questions.pending.collectAsStateWithLifecycle()
    val q = question ?: return
    val answer = { a: ImportQuestions.Answer -> questions.answer(q, a) }

    Dialog(
        onDismissRequest = { answer(ImportQuestions.Answer.CANCEL) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
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
            val one = q.names.size == 1
            Text(
                if (one) "Replace \"${q.names.single()}\"?" else "Replace ${q.names.size} workouts?",
                style = CadenceType.h2,
                color = CadenceColors.Fg1,
            )
            Text(
                if (one) {
                    "You already have a workout with this one's id, and they differ. " +
                        "Replacing it changes it on your other devices too when they next sync."
                } else {
                    "You already have workouts with these ids, and they differ: " +
                        q.names.take(3).joinToString(", ") +
                        (if (q.names.size > 3) " and ${q.names.size - 3} more" else "") +
                        ". Replacing them changes them on your other devices too when they next sync."
                },
                style = CadenceType.body,
                color = CadenceColors.Fg2,
            )
            PrimaryBtn(
                text = "Keep both",
                leadingIcon = null,
                onClick = { answer(ImportQuestions.Answer.KEEP_BOTH) },
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryBtn(
                text = if (one) "Replace it" else "Replace them",
                onClick = { answer(ImportQuestions.Answer.REPLACE) },
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryBtn(
                text = "Cancel",
                onClick = { answer(ImportQuestions.Answer.CANCEL) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

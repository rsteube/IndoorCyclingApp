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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.indoorbike.shared.companion.CompanionHost
import app.indoorbike.shared.companion.CompanionRoute
import app.indoorbike.shared.companion.RouteJunction
import app.indoorbike.shared.companion.RouteNavigation
import app.indoorbike.shared.companion.RouteOption
import app.indoorbike.shared.companion.RoutePlace
import app.indoorbike.shared.route.formatDistance
import app.indoorbike.shared.workout.formatDurationPretty
import app.indoorbike.ui.components.SheetHandle
import app.indoorbike.ui.components.SheetScrim
import app.indoorbike.ui.components.SheetShape
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.SlateMotion
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.ui.theme.pressScale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The next junction of a free ride, and a button for each way on.
 *
 * ## One row, arrows at their bearings
 *
 * The options sit in a row in the order the game sends them, left to right as
 * the rider sees the road, so every one is a thumb-sized target wherever it
 * points. Inside each the arrow is drawn at the option's own bearing, which a
 * fixed left/straight/right row cannot do: two roads both "slightly left" look
 * different here, the way they do through the windscreen.
 *
 * ## The selection is the game's
 *
 * A tap sends the pick and changes nothing on screen. The filled button is
 * always what the ride is going to do, as the game last said, so a tap after
 * the commit point never shows a turn the ride will not take. The game answers
 * an open junction at once, which is fast enough that nobody waits for it.
 */
@Composable
fun CompanionJunctionCard(
    junction: RouteJunction,
    onChoose: (junctionId: String, optionId: String) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    onAccent: Color? = null,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radii.lg))
            .background(CadenceColors.Bg1)
            .padding(Spacing.s3),
        verticalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Junction in " + formatDistance(roundTo10(junction.distanceM)),
                style = CadenceType.bodyStrong,
                color = CadenceColors.Fg1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = when {
                    !junction.isOpen -> "Set"
                    junction.commitInM > 0 -> "Choose within " + formatDistance(roundTo10(junction.commitInM))
                    else -> "Choose now"
                },
                style = CadenceType.small,
                color = CadenceColors.Fg3,
            )
        }
        BoxWithConstraints {
            val gaps = Spacing.s2 * (junction.options.size - 1)
            val nameWidth = (maxWidth - gaps) / junction.options.size - OPTION_PADDING * 2
            val nameStyle = roadNameStyle(junction.options.map(::nameOf), nameWidth)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                junction.options.forEach { option ->
                    OptionButton(
                        option = option,
                        nameStyle = nameStyle,
                        selected = option.optionId == junction.selectedOptionId,
                        open = junction.isOpen,
                        onClick = { onChoose(junction.junctionId, option.optionId) },
                        accent = accent,
                        onAccent = onAccent,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        why(junction)?.let { Text(text = it, style = CadenceType.micro, color = CadenceColors.Fg3) }
    }
}

/** Why the filled option is filled, in a few words, or nothing for the default. */
private fun why(junction: RouteJunction): String? = when (junction.selectedBy) {
    RouteJunction.SelectedBy.RIDER -> if (junction.isOpen) "Your choice. Tap another to change it." else "Your choice"
    RouteJunction.SelectedBy.NAVIGATION -> "On the way to your destination"
    RouteJunction.SelectedBy.DEFAULT -> if (junction.isOpen) "Tap a road to take it" else null
}

private fun nameOf(option: RouteOption): String = option.roadName ?: option.spokenLabel

/** Inside an option button, either side of its name. */
private val OPTION_PADDING = Spacing.s2

@Composable
private fun OptionButton(
    option: RouteOption,
    nameStyle: TextStyle,
    selected: Boolean,
    open: Boolean,
    onClick: () -> Unit,
    accent: Color?,
    onAccent: Color?,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val fill = accent ?: CadenceColors.Fg1
    val onFill = onAccent ?: CadenceColors.Bg0
    val background by animateColorAsState(
        targetValue = if (selected) fill else CadenceColors.Bg2,
        animationSpec = tween(SlateMotion.Fast, easing = SlateMotion.Easing),
        label = "junctionOption",
    )
    val ink = if (selected) onFill else if (open) CadenceColors.Fg1 else CadenceColors.Fg4
    val quiet = if (selected) onFill.copy(alpha = 0.8f) else CadenceColors.Fg3

    Column(
        modifier = modifier
            .heightIn(min = 112.dp)
            .pressScale(interaction)
            .clip(RoundedCornerShape(Radii.md))
            .background(background)
            .then(
                if (option.isNavigation && !selected) {
                    Modifier.border(2.dp, fill, RoundedCornerShape(Radii.md))
                } else {
                    Modifier
                }
            )
            .clickable(
                enabled = open,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = option.spokenLabel +
                    (option.leadsTo?.let { ", towards $it" } ?: "") +
                    (if (option.isNavigation) ", on the way to your destination" else "")
            }
            .padding(horizontal = OPTION_PADDING, vertical = Spacing.s2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.ArrowUpward,
            contentDescription = null,
            tint = ink,
            modifier = Modifier
                .size(34.dp)
                .rotate(option.bearingDeg.toFloat()),
        )
        Text(
            text = nameOf(option),
            style = nameStyle,
            color = ink,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        option.leadsTo?.let {
            Text(
                text = "to $it",
                style = CadenceType.micro,
                color = quiet,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        roadLine(option)?.let {
            Text(
                text = it,
                style = CadenceType.micro,
                color = quiet,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (option.isNavigation) {
            Icon(
                imageVector = Icons.Outlined.Flag,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * The size the options' names are drawn at: the largest of a few where no
 * word of any name has to break, the same for every option.
 *
 * Street names are long single words in much of the world, and a third of a
 * phone's width split "Reichenbachstraße" after "Reichenbachstr". A smaller
 * name read whole beats a larger one read in pieces, and one size across the
 * row keeps the names from looking ranked. Only a word too long for even the
 * smallest size is hyphenated.
 */
@Composable
private fun roadNameStyle(names: List<String>, width: Dp): TextStyle {
    val measurer = rememberTextMeasurer()
    val widthPx = with(LocalDensity.current) { width.roundToPx() }
    return remember(names, widthPx) {
        val words = names.flatMap { name -> name.split(' ').filter { it.isNotEmpty() } }
        val sizes = listOf(
            CadenceType.small,
            CadenceType.micro,
            CadenceType.micro.copy(fontSize = CadenceType.micro.fontSize * 0.85f),
        )
        sizes.firstOrNull { style ->
            words.all { word -> measurer.measure(word, style, maxLines = 1, softWrap = false).size.width <= widthPx }
        } ?: sizes.last().copy(hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph)
    }
}

/** "+4 % · gravel": what the legs will meet, which a map does not show. */
private fun roadLine(option: RouteOption): String? {
    val parts = mutableListOf<String>()
    option.gradientAheadPct?.let { pct ->
        if (abs(pct) >= 0.5) parts += (if (pct > 0) "+" else "−") + abs(pct).roundToInt() + " %"
    }
    option.surface?.takeIf { it != "asphalt" && it != "paved" }?.let { parts += it }
    option.trailGrade?.let { parts += "S$it" }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun roundTo10(metres: Double): Double = (metres / 10.0).roundToInt() * 10.0

/**
 * Where the ride is taking the rider, or a way to say where to go.
 *
 * With a destination: its name, how far and how long, and the next turn. With
 * none: one button, so a free ride shows the search exists without taking the
 * space a destination would.
 */
@Composable
fun CompanionNavigationStrip(
    navigation: RouteNavigation?,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (navigation == null) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(Radii.pill))
                .background(CadenceColors.Bg1)
                .clickable(onClick = onSearch)
                .padding(horizontal = Spacing.s4, vertical = Spacing.s2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = CadenceColors.Fg2, modifier = Modifier.size(18.dp))
            Text(text = "Go to a place", style = CadenceType.small, color = CadenceColors.Fg1)
        }
        return
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radii.lg))
            .background(CadenceColors.Bg1)
            .padding(start = Spacing.s3, top = Spacing.s2, bottom = Spacing.s2, end = Spacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Flag, contentDescription = null, tint = CadenceColors.Fg2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.s2))
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClickLabel = "Change destination", onClick = onSearch),
        ) {
            Text(
                text = navigation.name,
                style = CadenceType.bodyStrong,
                color = CadenceColors.Fg1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val numbers = listOfNotNull(
                navigation.distanceM?.let { formatDistance(roundTo10(it)) },
                navigation.etaS?.let { formatDurationPretty(it.roundToInt()) },
            )
            val next = navigation.next?.let { next ->
                (next.kind?.label ?: "Turn") + (next.roadName?.let { " onto $it" } ?: "") +
                    " in " + formatDistance(roundTo10(next.distanceM))
            }
            (numbers + listOfNotNull(next)).takeIf { it.isNotEmpty() }?.let {
                Text(
                    text = it.joinToString(" · "),
                    style = CadenceType.small,
                    color = CadenceColors.Fg3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(Radii.pill))
                .clickable(onClickLabel = "Stop navigating", onClick = onClear),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "Stop navigating", tint = CadenceColors.Fg2)
        }
    }
}

/** "You have arrived", for the few seconds the host keeps it. */
@Composable
fun CompanionArrivalBanner(name: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radii.lg))
            .background(CadenceColors.Success)
            .padding(horizontal = Spacing.s4, vertical = Spacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) {
        Icon(Icons.Outlined.Flag, contentDescription = null, tint = CadenceColors.Bg0)
        Text(text = "Arrived at $name", style = CadenceType.bodyStrong, color = CadenceColors.Bg0)
    }
}

/**
 * Search the game's area for somewhere to go.
 *
 * The phone holds nothing of the area: every list here is the game's answer to
 * what was typed, ranked by how far each place is to ride from where the rider
 * is now. Opened empty, it asks for the places worth going to nearby.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionPlacesSheet(
    search: CompanionHost.PlacesSearch?,
    onQuery: (String) -> Unit,
    onChoose: (RoutePlace) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var input by rememberSaveable { mutableStateOf("") }
    // Opens on the game's suggestions with the keyboard down. A place nearby is
    // one tap for a rider with a hand on the bars, and focusing the field while
    // the sheet was still opening left the sheet behind the keyboard.
    LaunchedEffect(Unit) { onQuery("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CadenceColors.Bg1,
        contentColor = CadenceColors.Fg1,
        shape = SheetShape,
        scrimColor = SheetScrim,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = Spacing.s5)
                .padding(bottom = Spacing.s5),
        ) {
            Text(text = "Go to", style = CadenceType.eyebrow, color = CadenceColors.Fg3)
            Spacer(Modifier.height(6.dp))
            BasicTextField(
                value = input,
                onValueChange = {
                    val next = it.take(CompanionRoute.MAX_TEXT_CHARS)
                    input = next
                    onQuery(next)
                },
                singleLine = true,
                textStyle = CadenceType.bodyStrong.copy(color = CadenceColors.Fg1),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onQuery(input) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.md))
                    .background(CadenceColors.Bg2)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                decorationBox = { inner ->
                    if (input.isEmpty()) {
                        Text("A square, a park, a café", style = CadenceType.body, color = CadenceColors.Fg4)
                    }
                    inner()
                },
            )
            Spacer(Modifier.height(Spacing.s3))
            val places = search?.places
            when {
                places == null -> Text(text = "Asking the game", style = CadenceType.small, color = CadenceColors.Fg3)
                places.isEmpty() -> Text(
                    text = if (input.isBlank()) "The game suggested nowhere nearby." else "Nothing called that here.",
                    style = CadenceType.small,
                    color = CadenceColors.Fg3,
                )
                else -> LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(places, key = { it.placeId }) { place ->
                        PlaceRow(place = place, onClick = { onChoose(place) })
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(place: RoutePlace, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(Radii.md))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.s2, vertical = Spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
    ) {
        Icon(Icons.Outlined.Place, contentDescription = null, tint = CadenceColors.Fg2)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = place.name, style = CadenceType.bodyStrong, color = CadenceColors.Fg1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            place.kind?.let { kind ->
                // OpenStreetMap's words, "marketplace" or "bicycle_parking", as a label.
                val label = kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
                Text(text = label, style = CadenceType.small, color = CadenceColors.Fg3, maxLines = 1)
            }
        }
        place.distanceM?.let {
            Text(text = formatDistance(roundTo10(it)), style = CadenceType.small, color = CadenceColors.Fg2)
        }
        place.bearingDeg?.let {
            Icon(
                imageVector = Icons.Outlined.ArrowUpward,
                contentDescription = null,
                tint = CadenceColors.Fg3,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(it.toFloat()),
            )
        }
    }
}

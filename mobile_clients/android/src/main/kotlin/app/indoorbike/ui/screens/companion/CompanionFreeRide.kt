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

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.indoorbike.shared.companion.CompanionHost
import app.indoorbike.shared.companion.PlaceCategory
import app.indoorbike.shared.companion.RouteJunction
import app.indoorbike.shared.companion.RouteNavigation
import app.indoorbike.shared.companion.RouteOption
import app.indoorbike.shared.companion.RoutePlace
import app.indoorbike.shared.companion.RoutePoint
import app.indoorbike.shared.route.formatDistance
import app.indoorbike.shared.workout.formatDurationPretty
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
            val commitInS = junction.commitInS
            Text(
                text = when {
                    !junction.isOpen -> "Set"
                    commitInS != null && commitInS >= 1.0 -> "Choose within " + commitInS.roundToInt() + " s"
                    commitInS == null && junction.commitInM > 0 ->
                        "Choose within " + formatDistance(roundTo10(junction.commitInM))
                    else -> "Choose now"
                },
                style = CadenceType.small,
                color = CadenceColors.Fg3,
            )
        }
        if (junction.hasDiagram) {
            JunctionDiagram(
                junction = junction,
                accent = accent ?: CadenceColors.Fg1,
                modifier = Modifier.fillMaxWidth().height(DIAGRAM_HEIGHT),
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

private val DIAGRAM_HEIGHT = 132.dp

/** How far out from the junction the diagram reaches, in metres: the 40 m the game sends, and a margin. */
private const val DIAGRAM_REACH_M = 44.0

/**
 * The junction as the game draws it (0.13): the road in from below, each way
 * out as it leaves, the selected one bold in the accent and the others faint.
 *
 * The same picture as the game's own tile and the markers over the road, so
 * the card and the place on the big screen read as one thing. The buttons
 * underneath are still what is tapped; this is for seeing which road is which.
 */
@Composable
private fun JunctionDiagram(junction: RouteJunction, accent: Color, modifier: Modifier = Modifier) {
    val road = CadenceColors.Bg3
    val faint = CadenceColors.Fg4
    // Clipped: the road in runs off the bottom, as it does on the game's tile,
    // rather than on down behind the buttons.
    Canvas(modifier = modifier.clipToBounds()) {
        // Square metres, so a right angle looks like one. The junction sits
        // two thirds of the way down, where the road in has room to arrive.
        val scale = minOf(size.width / 2f, size.height * 0.62f) / DIAGRAM_REACH_M.toFloat()
        val origin = Offset(size.width / 2f, size.height * 0.64f)
        fun at(p: RoutePoint) = Offset(origin.x + p.x.toFloat() * scale, origin.y - p.y.toFloat() * scale)
        val thin = 7.dp.toPx()
        val bold = 11.dp.toPx()

        val approach = junction.approach.ifEmpty { listOf(RoutePoint(0.0, -DIAGRAM_REACH_M), RoutePoint(0.0, 0.0)) }
        drawArm(approach.map(::at), road, thin)
        junction.options.filter { it.optionId != junction.selectedOptionId }.forEach { option ->
            drawArm(option.points.map(::at), if (option.isNavigation) road else faint, thin)
        }
        // The selected arm last, so it is drawn over the others where they meet.
        junction.selected?.let { drawArm(it.points.map(::at), accent, bold) }
        drawCircle(color = road, radius = thin * 0.9f, center = origin)
    }
}

private fun DrawScope.drawArm(points: List<Offset>, color: Color, width: Float) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
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
 * Where the ride is taking the rider, or the way to choose somewhere.
 *
 * The same full-width bar either way, at the top of the screen, so the rider
 * always reaches for the same place. With no destination it is one big button;
 * with one, the name, how far and how long, and the next turn, with **Change**
 * and a stop button at the end. Nothing in it needs a gesture: a pull-up sheet
 * was hard to open with a hand on the bars, and fought the list inside it.
 */
@Composable
fun CompanionNavigationStrip(
    navigation: RouteNavigation?,
    onChoose: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (navigation == null) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = STRIP_HEIGHT)
                .clip(RoundedCornerShape(Radii.lg))
                .background(CadenceColors.Bg1)
                .clickable(onClickLabel = "Choose a destination", onClick = onChoose)
                .padding(horizontal = Spacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
        ) {
            Icon(Icons.Outlined.Flag, contentDescription = null, tint = CadenceColors.Fg2)
            Text(
                text = "Choose a destination",
                style = CadenceType.bodyStrong,
                color = CadenceColors.Fg1,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = CadenceColors.Fg3)
        }
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = STRIP_HEIGHT)
            .clip(RoundedCornerShape(Radii.lg))
            .background(CadenceColors.Bg1)
            .padding(start = Spacing.s4, end = Spacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Flag, contentDescription = null, tint = CadenceColors.Fg2)
        Spacer(Modifier.width(Spacing.s3))
        Column(modifier = Modifier.weight(1f).padding(vertical = Spacing.s2)) {
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
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(Radii.pill))
                .clickable(onClickLabel = "Change destination", onClick = onChoose)
                .padding(horizontal = Spacing.s3),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "Change", style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(Radii.pill))
                .clickable(onClickLabel = "Stop navigating", onClick = onClear),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "Stop navigating", tint = CadenceColors.Fg2)
        }
    }
}

/** As tall as a paddle is wide on a phone: found by a thumb without looking. */
private val STRIP_HEIGHT = 64.dp

/** "You have arrived", for the few seconds the host keeps it. */
@Composable
fun CompanionArrivalBanner(name: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = STRIP_HEIGHT)
            .clip(RoundedCornerShape(Radii.lg))
            .background(CadenceColors.Success)
            .padding(horizontal = Spacing.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
    ) {
        Icon(Icons.Outlined.Flag, contentDescription = null, tint = CadenceColors.Bg0)
        Text(text = "Arrived at $name", style = CadenceType.bodyStrong, color = CadenceColors.Bg0)
    }
}

/**
 * Places to go, nearest first, to browse rather than search.
 *
 * A panel over the ride, not a sheet: it opens on one tap, closes on one tap or
 * Back, and nothing in it is a drag, so scrolling the list never moves the
 * panel. There is no text field and so no keyboard; a rider with a hand on the
 * bars picks from what is near, narrowed by category when the game sorts its
 * places. Tapping the chosen category again shows every kind.
 *
 * The phone holds nothing of the area: every list is the game's answer, ranked
 * by how far each place is to ride from where the rider is now.
 */
@Composable
fun CompanionDestinations(
    browse: CompanionHost.PlacesSearch?,
    categories: List<PlaceCategory>,
    onBrowse: (PlaceCategory?) -> Unit,
    onChoose: (RoutePlace) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onBrowse(null) }
    BackHandler(onBack = onClose)
    val selected = browse?.category

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CadenceColors.Bg0)
            // The ride underneath must not take a tap meant for this panel.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(horizontal = Spacing.s4, vertical = Spacing.s3),
        verticalArrangement = Arrangement.spacedBy(Spacing.s3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Go to", style = CadenceType.h2, color = CadenceColors.Fg1)
                Text(text = "Nearest first", style = CadenceType.small, color = CadenceColors.Fg3)
            }
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(Radii.pill))
                    .background(CadenceColors.Bg2)
                    .clickable(onClickLabel = "Close", onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "Close", tint = CadenceColors.Fg1)
            }
        }

        if (categories.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                categories.forEach { category ->
                    CategoryChip(
                        category = category,
                        selected = category == selected,
                        onClick = { onBrowse(if (category == selected) null else category) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        val places = browse?.places
        when {
            places == null -> Text(text = "Asking the game", style = CadenceType.body, color = CadenceColors.Fg3)
            places.isEmpty() -> Text(
                text = if (selected == null) {
                    "The game has nowhere to suggest nearby."
                } else {
                    "No " + selected.label.lowercase() + " nearby. Tap the category again to see everything."
                },
                style = CadenceType.body,
                color = CadenceColors.Fg3,
            )
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.s2),
            ) {
                items(places, key = { it.placeId }) { place ->
                    PlaceRow(place = place, onClick = { onChoose(place) })
                }
            }
        }
    }
}

@Composable
private fun CategoryChip(
    category: PlaceCategory,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background by animateColorAsState(
        targetValue = if (selected) CadenceColors.Fg1 else CadenceColors.Bg2,
        animationSpec = tween(SlateMotion.Fast, easing = SlateMotion.Easing),
        label = "categoryChip",
    )
    val ink = if (selected) CadenceColors.Bg0 else CadenceColors.Fg1
    Column(
        modifier = modifier
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(Radii.md))
            .background(background)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Tab
                this.selected = selected
            }
            .padding(horizontal = Spacing.s1, vertical = Spacing.s2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Icon(iconFor(category), contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
        Text(
            text = category.label,
            style = CadenceType.small,
            color = ink,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun iconFor(category: PlaceCategory?): ImageVector = when (category) {
    PlaceCategory.SQUARES -> Icons.Outlined.Storefront
    PlaceCategory.MONUMENTS -> Icons.Outlined.AccountBalance
    PlaceCategory.FOOD -> Icons.Outlined.Restaurant
    PlaceCategory.NATURE -> Icons.Outlined.Park
    null -> Icons.Outlined.Place
}

@Composable
private fun PlaceRow(place: RoutePlace, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(Radii.md))
            .background(CadenceColors.Bg1)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.s4, vertical = Spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
    ) {
        Icon(iconFor(place.category), contentDescription = null, tint = CadenceColors.Fg2)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = place.name, style = CadenceType.bodyStrong, color = CadenceColors.Fg1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            place.kind?.let { kind ->
                // OpenStreetMap's words, "marketplace" or "bicycle_parking", as a label.
                val label = kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
                Text(text = label, style = CadenceType.small, color = CadenceColors.Fg3, maxLines = 1)
            }
        }
        place.distanceM?.let {
            Text(text = formatDistance(roundTo10(it)), style = CadenceType.body, color = CadenceColors.Fg2)
        }
        place.bearingDeg?.let {
            Icon(
                imageVector = Icons.Outlined.ArrowUpward,
                contentDescription = null,
                tint = CadenceColors.Fg3,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(it.toFloat()),
            )
        }
    }
}

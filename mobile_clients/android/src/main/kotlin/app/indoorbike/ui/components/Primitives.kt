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
package app.indoorbike.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.ui.zones.HeartRateZone
import app.indoorbike.ui.zones.PowerZone

/**
 * The Slate component set.
 *
 * Two rules run through all of it. Surfaces step up from the canvas rather than
 * drawing a border: a card is Bg1 on Bg0 and nothing else, no outline and no
 * shadow. And the only fill that reads as an action is Fg1, the same cool white
 * as the primary text, so a screen has one obvious button and everything else
 * stays quiet.
 */

// ─── Screen chrome ────────────────────────────────────────────────────────────

/**
 * The title block that opens every screen: an optional row of circular icon
 * buttons, then a sentence-case label over a large title.
 *
 * A leading button takes a row of its own with the trailing one opposite it,
 * the way the back-and-star pair sits above a workout name. With no leading
 * button the trailing one moves up beside the title instead, which is where the
 * home screen wants its QR button.
 *
 * Still called AppBar because eighteen screens call it that. It has not been a
 * bar since the redesign.
 */
@Composable
fun AppBar(
    title: String,
    eyebrow: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /**
     * Pass one, and drive it from the screen's scroll container with
     * `Modifier.nestedScroll(header.nestedScrollConnection)`, to make the title
     * block collapse away as the rider scrolls. Without one the block is fixed,
     * which is right for a screen that does not scroll.
     */
    header: CollapsingHeaderState? = null,
) {
    val fraction = header?.fraction ?: 0f
    val hasLeading = leading != null

    Column(
        modifier = Modifier
            .background(CadenceColors.Bg0)
            .fillMaxWidth()
            // windowInsetsPadding rather than a plain padding of the same
            // values: it consumes what it applies, so a screen that pads for
            // the same inset further down does not do it twice. The cutout is
            // in here because in landscape it is beside the title, not above it.
            .windowInsetsPadding(
                WindowInsets.statusBars
                    .union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            )
    ) {
        // The pinned row. It carries the buttons at any scroll position, and
        // once the block below has collapsed it carries the title too, so the
        // screen never loses its name - it just stops shouting it.
        //
        // A screen with no buttons has no pinned row when the header is open,
        // which keeps the expanded layout exactly as designed; the row grows in
        // as the title arrives.
        // The 8 dp under the row appears as the title arrives, so a collapsed
        // header never has a list row pressed right up against its baseline.
        val pinnedGap = Spacing.s2 * fraction
        val pinnedHeight =
            if (hasLeading) PinnedRowHeight + pinnedGap else (PinnedRowHeight + Spacing.s2) * fraction
        if (hasLeading || fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(pinnedHeight)
                    .clipToBounds()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PinnedRowHeight)
                        .align(Alignment.TopStart)
                        .padding(start = Spacing.s4, end = Spacing.s4, top = Spacing.s2),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (leading != null) {
                        leading()
                        Spacer(Modifier.width(Spacing.s3))
                    }
                    Text(
                        text = title,
                        style = CadenceType.h3,
                        color = CadenceColors.Fg1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .graphicsLayer { alpha = fraction }
                    )
                    if (trailing != null) {
                        // Beside a back button the trailing action is part of
                        // the design and always on; on a screen without one it
                        // lives in the title block and only appears up here as
                        // that block fades out, so the two never both show.
                        Box(
                            modifier = Modifier.graphicsLayer {
                                alpha = if (hasLeading) 1f else fraction
                            }
                        ) {
                            trailing()
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (header == null) Modifier
                    else Modifier
                        .clipToBounds()
                        .graphicsLayer { alpha = 1f - fraction }
                        // Measure the block at its natural height, then hand
                        // back a shorter slot and slide the content up inside
                        // it. Measuring after the height was applied - which is
                        // what onSizeChanged would have reported - reads back
                        // the collapsed height as the collapsible one, and the
                        // two chase each other to zero.
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(
                                constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
                            )
                            header.onMeasured(placeable.height.toFloat())
                            val visible = (placeable.height + header.offset)
                                .toInt()
                                .coerceIn(0, placeable.height)
                            layout(placeable.width, visible) {
                                placeable.place(0, visible - placeable.height)
                            }
                        }
                )
                .padding(
                    start = Spacing.s6,
                    end = Spacing.s6,
                    top = if (hasLeading) Spacing.s4 else Spacing.s5,
                    bottom = Spacing.s2
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (eyebrow != null) {
                    Text(
                        text = eyebrow,
                        style = CadenceType.eyebrow,
                        color = CadenceColors.Fg3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Text(text = title, style = CadenceType.h1, color = CadenceColors.Fg1)
            }
            if (!hasLeading && trailing != null) {
                Spacer(Modifier.width(Spacing.s3))
                trailing()
            }
        }
    }
}

/** 8 dp of top padding over a 44 dp circular button. */
private val PinnedRowHeight = 52.dp

/**
 * A sentence-case label over a card or a group of them. Inset 4 dp so it hangs
 * just outside the card's own 20 dp padding; vertical space is the caller's,
 * because it depends on what the label is separating.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, hint: String? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.s1),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = CadenceType.eyebrow,
            color = CadenceColors.Fg3,
            modifier = Modifier.weight(1f)
        )
        if (hint != null) {
            Text(text = hint, style = CadenceType.tick, color = CadenceColors.Fg3)
        }
    }
}

/**
 * How tall the floating tab bar is, published by whoever draws it.
 *
 * A tab root's list runs the full height of the screen and passes underneath
 * the bar - that is what makes the bar read as floating rather than as a wall
 * at the bottom - so the list needs to know how much of its own tail is
 * covered. Zero on a layout with no tab bar, such as the tablet rail.
 */
val LocalTabBarHeight = compositionLocalOf { 0.dp }

/**
 * Bottom padding for a scrolling list that runs to the screen edge: the design's
 * own gap plus whatever the system reserves under it.
 *
 * It belongs in a list's `contentPadding` and never as padding on the list's
 * parent - padding the parent stops the content scrolling behind the navigation
 * bar, which is the whole point of drawing edge to edge.
 */
@Composable
fun listBottomPadding(extra: Dp = Spacing.s5): Dp =
    extra + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * The same, for a list on a tab root: the last row has to clear the floating
 * tab bar, not just the system's own inset. The bar already carries that inset
 * inside its own height, so this takes whichever is taller rather than adding
 * them.
 */
@Composable
fun tabRootBottomPadding(extra: Dp = Spacing.s5): Dp {
    val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return extra + maxOf(LocalTabBarHeight.current, systemBottom)
}

// ─── Surfaces ─────────────────────────────────────────────────────────────────

/** Bg1, 20 dp, no border, no shadow. Every card in the app. */
@Composable
fun SlateCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(Spacing.s5),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.lg))
            .background(CadenceColors.Bg1)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content
    )
}

/**
 * A card that holds a list of rows: the same Bg1 surface with 6 dp of vertical
 * breathing room, so the first and last rows keep clear of the corner radius.
 * Rows separate with [CardDivider], never with a gap.
 */
@Composable
fun ListCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.lg))
            .background(CadenceColors.Bg1)
            .padding(vertical = 6.dp),
        content = content
    )
}

/** 1 dp of Line1, inset 20 dp from both card edges. */
@Composable
fun CardDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.s5)
            .height(1.dp)
            .background(CadenceColors.Line1)
    )
}

// ─── Bottom sheets ────────────────────────────────────────────────────────────

/** A sheet is a card that arrived from the bottom: same Bg1, corners on top only. */
val SheetShape = RoundedCornerShape(topStart = Radii.xl, topEnd = Radii.xl)

/** rgba(21, 27, 36, .55) - a touch darker and cooler than the canvas. */
val SheetScrim = Color(0x8C151B24)

/** The 36 × 4 dp grab bar every sheet in the app opens with. */
@Composable
fun SheetHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.s3, bottom = Spacing.s2),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(CadenceColors.Bg4)
        )
    }
}

/** Locked or empty: no fill, a dashed Line2 outline straight on the canvas. */
@Composable
fun DashedCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(Spacing.s5),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.lg))
            .dashedOutline()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content
    )
}

/** The dashed outline on its own, for callers that lay out their own box. */
fun Modifier.dashedOutline(
    radius: Dp = Radii.lg,
    color: Color = CadenceColors.Line2
): Modifier = this.drawBehind {
    val stroke = 1.dp.toPx()
    val r = radius.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
        )
    )
}

// ─── Buttons ──────────────────────────────────────────────────────────────────

/**
 * The one action on a screen: 56 dp, full width, pill, Fg1 fill. There is never
 * a second one on the same screen, and it always sits at the bottom.
 */
@Composable
fun PrimaryBtn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = Icons.Filled.PlayArrow,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = when {
        !enabled -> CadenceColors.Bg2
        pressed -> CadenceColors.Fg2
        else -> CadenceColors.Fg1
    }
    val fg = if (enabled) CadenceColors.OnBrand else CadenceColors.Fg4
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2, Alignment.CenterHorizontally)
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = text,
            style = CadenceType.bodyStrong.copy(fontSize = 16.sp),
            color = fg,
            maxLines = 1
        )
    }
}

/** Same shape as the primary, one surface step instead of the white fill. */
@Composable
fun SecondaryBtn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(CircleShape)
            .background(if (pressed) CadenceColors.Bg3 else CadenceColors.Bg2)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2, Alignment.CenterHorizontally)
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = if (enabled) CadenceColors.Fg1 else CadenceColors.Fg4,
                modifier = Modifier.size(18.dp)
            )
        }
        // Never wrap: these sit in crowded single-line rows (the ride header
        // most of all), where a wrapped label doesn't shrink the button, it
        // stretches it into a one-word-per-line column and shoves the row's
        // other content off screen.
        Text(
            text = text,
            style = CadenceType.bodyStrong.copy(fontSize = 16.sp),
            color = if (enabled) CadenceColors.Fg1 else CadenceColors.Fg4,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** A primary button that deletes something. The only red fill in the app. */
@Composable
fun DestructiveBtn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null
) {
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(CircleShape)
            .background(CadenceColors.Danger)
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2, Alignment.CenterHorizontally)
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = CadenceColors.Fg1,
                modifier = Modifier.size(18.dp)
            )
        }
        Text(
            text = text,
            style = CadenceType.bodyStrong.copy(fontSize = 16.sp),
            color = CadenceColors.Fg1,
            maxLines = 1
        )
    }
}

/**
 * A 44 dp circle. Bg1 on the canvas, Bg2 when it sits on a card, so it always
 * stays one step up from whatever is behind it.
 */
@Composable
fun IconBtn(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit = {},
    background: Color = CadenceColors.Bg1,
    tint: Color = CadenceColors.Fg1,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (pressed) CadenceColors.Bg3 else background)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else CadenceColors.Fg4,
            modifier = Modifier.size(22.dp)
        )
    }
}

// ─── List rows ────────────────────────────────────────────────────────────────

/**
 * A row inside a [ListCard]: an optional plain Fg2 glyph, a title over an
 * optional sub-line, and a chevron. No icon wells, no dividers of its own -
 * the card puts a [CardDivider] between rows.
 */
@Composable
fun NavRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick)
                else Modifier
            )
            .padding(horizontal = Spacing.s5, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) CadenceColors.Fg2 else CadenceColors.Fg4,
                modifier = Modifier.size(22.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = CadenceType.bodyStrong,
                color = if (enabled) CadenceColors.Fg1 else CadenceColors.Fg4,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = CadenceType.small,
                    color = CadenceColors.Fg3
                )
            }
        }
        if (trailing != null) trailing()
        if (showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = CadenceColors.Fg3,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

// ─── Pills, chips, badges ─────────────────────────────────────────────────────

@Composable
fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) CadenceColors.Fg1 else CadenceColors.Bg2)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.s4, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = CadenceType.small.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
            ),
            color = if (selected) CadenceColors.OnBrand else CadenceColors.Fg2,
            maxLines = 1
        )
    }
}

/**
 * The zone, in the zone's colour on a 20 % wash of it. Outside a chart this is
 * the only saturated colour anywhere in the app.
 */
@Composable
fun ZoneBadge(zone: PowerZone, includeLabel: Boolean = false) {
    ZoneBadge(zone.number, zone.label, zone.color, zone.bgColor, includeLabel)
}

/** The heart-rate zones use the same badge; they are a parallel enum, not a subtype. */
@Composable
fun ZoneBadge(zone: HeartRateZone, includeLabel: Boolean = false) {
    ZoneBadge(zone.number, zone.label, zone.color, zone.bgColor, includeLabel)
}

@Composable
private fun ZoneBadge(
    number: Int,
    label: String,
    color: Color,
    bgColor: Color,
    includeLabel: Boolean,
) {
    Text(
        text = "Z$number" + if (includeLabel) " · $label" else "",
        style = CadenceType.tick.copy(fontWeight = FontWeight.Bold),
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

/** A quiet Bg2 pill for a piece of metadata or a small repeat action. */
@Composable
fun MetaPill(
    text: String,
    color: Color = CadenceColors.Fg1,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(CadenceColors.Bg2)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = Spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = text,
            style = CadenceType.small.copy(fontWeight = FontWeight.Bold),
            color = color,
            maxLines = 1
        )
    }
}

/** "2 new". An Fg1 fill, so a count is the loudest thing in its row. */
@Composable
fun CountPill(text: String) {
    Text(
        text = text,
        style = CadenceType.tick.copy(fontWeight = FontWeight.Bold),
        color = CadenceColors.OnBrand,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(CadenceColors.Fg1)
            .padding(horizontal = Spacing.s3, vertical = 6.dp)
    )
}

// ─── Stats ────────────────────────────────────────────────────────────────────

/** A 12 sp label over a 22 sp value, with the unit trailing the value in Fg3. */
@Composable
fun Stat(
    label: String,
    value: String,
    unit: String? = null,
    modifier: Modifier = Modifier,
    valueColor: Color = CadenceColors.Fg1
) {
    Column(modifier = modifier) {
        Text(text = label, style = CadenceType.tick, color = CadenceColors.Fg3, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = value, style = CadenceType.h2, color = valueColor, maxLines = 1)
            if (unit != null) {
                Text(
                    text = " $unit",
                    style = CadenceType.tick,
                    color = CadenceColors.Fg3,
                    maxLines = 1
                )
            }
        }
    }
}

// ─── Charts ───────────────────────────────────────────────────────────────────

data class ZoneSegment(val zone: PowerZone, val weight: Float)

/**
 * The workout's shape as a strip of zone colour. No track behind it: where a
 * step ends the next one starts, and the 2 dp gaps do the separating.
 */
@Composable
fun ZoneBar(
    segments: List<ZoneSegment>,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    progress: Float? = null
) {
    if (segments.isEmpty()) return
    val total = segments.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(0.0001f)
    // The rounding is on the strip, not on each step. A workout with thirty
    // sprints has thirty segments narrower than the radius, and rounding each
    // one turns the bar into a row of dots.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        var cum = 0f
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(height / 2)),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            segments.forEach { seg ->
                val before = cum
                cum += seg.weight
                val opacity = when {
                    progress == null -> 1f
                    (before + seg.weight) / total <= progress -> 1f
                    else -> 0.45f
                }
                Box(
                    modifier = Modifier
                        .weight(seg.weight)
                        .height(height)
                        .background(seg.zone.color.copy(alpha = opacity))
                )
            }
        }
        if (progress != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(height)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(2.dp)
                        .height(height + 4.dp)
                        .background(CadenceColors.Fg1)
                )
            }
        }
    }
}

// ─── Tab bar ──────────────────────────────────────────────────────────────────

data class TabItem(val id: String, val icon: ImageVector, val label: String)

/**
 * A floating Bg1 pill above the canvas, with the active tab as an Fg1 pill
 * inside it. It stops short of the screen edges so the canvas reads as one
 * continuous surface behind it.
 *
 * The 20 dp the design asks for below the pill is a gap to the screen edge, and
 * the system's own bottom inset is already such a gap - so the two are taken as
 * one rather than stacked. Adding them put the bar 47 dp up on a gesture-nav
 * phone, floating in the middle of nowhere. Whichever is larger wins, so the
 * bar never sits under the gesture handle and never drifts away from the edge
 * on a device that reserves nothing.
 */

/**
 * Two or three mutually exclusive views of one screen.
 *
 * Deliberately the bottom [TabBar]'s language - Bg1 track, the active segment
 * filled Fg1 - because it is the same kind of choice: which view of one thing
 * you are looking at. That is also why it is not a row of [FilterChip]s, which
 * sit directly underneath it on the repository browsers and mean something
 * quite different: a filter narrows a list, this changes which list.
 */
@Composable
fun SegmentedSwitch(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(CadenceColors.Bg1)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { option ->
            val active = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (active) CadenceColors.Fg1 else Color.Transparent)
                    // selectable rather than clickable: it tells a screen
                    // reader which segment is chosen, which "button" does not.
                    .selectable(
                        selected = active,
                        role = Role.Tab,
                        onClick = { if (!active) onSelect(option) }
                    )
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option,
                    style = CadenceType.small.copy(
                        fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold
                    ),
                    color = if (active) CadenceColors.OnBrand else CadenceColors.Fg2,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun TabBar(
    active: String,
    tabs: List<TabItem>,
    onTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        modifier = modifier
            .fillMaxWidth()
            // The horizontal inset is the one that has to be added: in landscape
            // a three-button bar takes a side, and the pill has to clear it.
            .windowInsetsPadding(
                WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)
            )
    ) {
        // A wash of canvas above the bar, then solid canvas behind it. The pill
        // is Bg1 and the list running underneath it is full of Bg1 cards, so
        // without this the bar dissolves into whichever card happens to be
        // behind it and its labels read as text lying on someone else's card.
        // The content fades out into the canvas before it reaches the bar.
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(TabBarFadeHeight)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, CadenceColors.Bg0)
                    )
                )
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CadenceColors.Bg0)
                .padding(
                    start = Spacing.s4,
                    end = Spacing.s4,
                    top = Spacing.s3,
                    bottom = maxOf(Spacing.s5, systemBottom)
                )
                .clip(CircleShape)
                .background(CadenceColors.Bg1)
                .padding(6.dp)
        ) {
            tabs.forEach { tab ->
                val activeNow = tab.id == active
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(if (activeNow) CadenceColors.Fg1 else Color.Transparent)
                        .clickable { onTabSelected(tab.id) }
                        .padding(vertical = Spacing.s2, horizontal = Spacing.s1),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = if (activeNow) CadenceColors.OnBrand else CadenceColors.Fg2,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = tab.label,
                        style = CadenceType.micro.copy(
                            fontWeight = if (activeNow) FontWeight.Bold else FontWeight.SemiBold
                        ),
                        color = if (activeNow) CadenceColors.OnBrand else CadenceColors.Fg2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** How far above the bar the content dissolves into the canvas. */
private val TabBarFadeHeight = 28.dp

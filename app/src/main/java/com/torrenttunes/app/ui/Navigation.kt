package com.torrenttunes.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrenttunes.app.ui.theme.PineAccent
import com.torrenttunes.app.ui.theme.PineSurface
import com.torrenttunes.app.ui.theme.PineTextSecondary

/** The three places this app lives: browse, search, and what you kept. */
enum class AppTab(val label: String) {
    DISCOVERY("Discovery"),
    SEARCH("Search"),
    LIBRARY("Library"),
}

/**
 * Bottom navigation, sitting directly under the mini-player so the two read as
 * one pinned unit.
 *
 * The selected tab gets a small accent pill behind its icon as well as the
 * tint. Tint alone is what Spotify and Apple Music rely on, but on a dark
 * ground the pill is the difference between "obviously selected" and "squint
 * at it" — and it gives the switch something to animate, so a tap reads as
 * the app moving rather than as a colour swapping.
 */
@Composable
fun AppNavBar(current: AppTab, onSelect: (AppTab) -> Unit) {
    NavigationBar(
        containerColor = PineSurface,
        tonalElevation = 0.dp,
    ) {
        AppTab.entries.forEach { tab ->
            val selected = tab == current
            val tint by animateColorAsState(
                targetValue = if (selected) PineAccent else PineTextSecondary,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
                label = "tab-tint",
            )
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab) },
                icon = {
                    Box(
                        modifier = Modifier
                            .size(width = 56.dp, height = 30.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(if (selected) PineAccent.copy(alpha = 0.14f) else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        TabGlyph(tab, tint, Modifier.size(24.dp))
                    }
                },
                label = {
                    Text(
                        tab.label,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = tint,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = PineAccent,
                    selectedTextColor = PineAccent,
                    unselectedIconColor = PineTextSecondary,
                    unselectedTextColor = PineTextSecondary,
                    indicatorColor = Color.Transparent,
                ),
                modifier = Modifier.semantics {
                    contentDescription = tab.label
                },
            )
        }
    }
}

/**
 * Line-art glyphs, matched to the weight of the transport controls above.
 *
 * Drawn rather than loaded: no icon dependency, no font coverage question,
 * and they stay crisp at any size.
 */
@Composable
private fun TabGlyph(tab: AppTab, color: Color, modifier: Modifier) = Canvas(modifier) {
    when (tab) {
        AppTab.DISCOVERY -> {
            // Compass. The needle is a real diamond — two vertices along the
            // axis, two across it — which is what makes it read as a compass
            // rather than as two slashes.
            val r = size.minDimension / 2f
            val c = center
            drawCircle(color, radius = r, style = Stroke(width = r * 0.15f))

            val k = 0.7071f // sin/cos 45°, so the needle points north-east
            val along = r * 0.62f
            val across = r * 0.26f
            val tip = Offset(c.x + k * along, c.y - k * along)
            val tail = Offset(c.x - k * along, c.y + k * along)
            val right = Offset(c.x + k * across, c.y + k * across)
            val left = Offset(c.x - k * across, c.y - k * across)

            val needle = androidx.compose.ui.graphics.Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(right.x, right.y)
                lineTo(tail.x, tail.y)
                lineTo(left.x, left.y)
                close()
            }
            drawPath(needle, color)
        }

        AppTab.SEARCH -> {
            // Magnifier, sized so the handle clears the bounding circle at 24 dp.
            val s = size.minDimension
            val stroke = s * 0.13f
            val r = s * 0.29f
            val c = Offset(size.width * 0.42f, size.height * 0.42f)
            drawCircle(color, radius = r, style = Stroke(width = stroke))
            val k = 0.7071f
            val from = r + stroke / 2f
            val to = s * 0.86f - stroke / 2f
            drawLine(
                color = color,
                start = Offset(c.x + k * from, c.y + k * from),
                end = Offset(c.x + k * to, c.y + k * to),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }

        AppTab.LIBRARY -> {
            // Apple Music's library glyph: two upright records with one leaning
            // between them. Three horizontal bars would read as a hamburger menu.
            val h = size.height
            val w = size.width
            val bar = h * 0.15f
            val rad = CornerRadius(bar / 2f, bar / 2f)
            val top = h * 0.17f
            val bottom = h * 0.83f
            drawRoundRect(color, Offset(w * 0.13f, top), Size(bar, bottom - top), rad)
            drawRoundRect(color, Offset(w * 0.87f - bar, top), Size(bar, bottom - top), rad)
            rotate(degrees = 20f, pivot = Offset(w * 0.5f, h * 0.5f)) {
                drawRoundRect(color, Offset(w * 0.5f - bar / 2f, top), Size(bar, bottom - top), rad)
            }
        }
    }
}

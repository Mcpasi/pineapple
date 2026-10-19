package com.torrenttunes.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrenttunes.app.ui.theme.ScInk
import com.torrenttunes.app.ui.theme.ScOrange
import com.torrenttunes.app.ui.theme.ScInkSoft

/** The three places this app lives: browse, search, and what you kept. */
enum class AppTab(val label: String) {
    DISCOVERY("Discovery"),
    SEARCH("Search"),
    LIBRARY("Library"),
}

/**
 * Bottom navigation, drawn in the same light SoundCloud palette as the rest of
 * the app. Sits under the mini-player so the two read as one unit, the way
 * Spotify stacks them.
 */
@Composable
fun AppNavBar(current: AppTab, onSelect: (AppTab) -> Unit) {
    NavigationBar(
        containerColor = Color.White,
        tonalElevation = 0.dp,
    ) {
        AppTab.entries.forEach { tab ->
            val selected = tab == current
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab) },
                icon = {
                    TabGlyph(tab, if (selected) ScOrange else ScInkSoft, Modifier.size(22.dp))
                },
                label = {
                    Text(
                        tab.label,
                        fontSize = 11.sp,
                        fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold
                        else androidx.compose.ui.text.font.FontWeight.Medium,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = ScOrange,
                    selectedTextColor = ScOrange,
                    unselectedIconColor = ScInkSoft,
                    unselectedTextColor = ScInkSoft,
                    indicatorColor = Color.White,
                ),
            )
        }
    }
}

/** Line-art glyphs, matched to the weight of the transport controls above. */
@Composable
private fun TabGlyph(tab: AppTab, color: Color, modifier: Modifier) = Canvas(modifier) {
    when (tab) {
        AppTab.DISCOVERY -> {
            val r = size.minDimension / 2f
            drawCircle(color = color, radius = r, style = Stroke(width = r * 0.16f))
            // Compass needle: a slim diamond pointing upper-right.
            val c = center
            val tip = Offset(c.x + r * 0.42f, c.y - r * 0.42f)
            val tail = Offset(c.x - r * 0.42f, c.y + r * 0.42f)
            val w = r * 0.20f
            drawLine(color, tip, Offset(c.x + w, c.y + w), strokeWidth = r * 0.14f, cap = StrokeCap.Round)
            drawLine(color, tail, Offset(c.x - w, c.y - w), strokeWidth = r * 0.14f, cap = StrokeCap.Round)
        }
        AppTab.SEARCH -> {
            // Magnifier drawn by hand — SearchGlyph is a @Composable, and a
            // composable cannot be called from inside a draw scope.
            val s = size.minDimension
            val stroke = s * 0.14f
            val r = s * 0.30f
            val c = Offset(size.width * 0.42f, size.height * 0.42f)
            drawCircle(color = color, radius = r, style = Stroke(width = stroke))
            val from = r + stroke / 2f
            val to = s * 0.47f
            val k = 0.7071f
            drawLine(
                color = color,
                start = Offset(c.x + k * from, c.y + k * from),
                end = Offset(c.x + k * to, c.y + k * to),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }

        AppTab.LIBRARY -> {
            // Three stacked shelves — reads as "collection" at 22 dp.
            val h = size.height
            val w = size.width
            val stroke = h * 0.13f
            val gap = h * 0.16f
            listOf(0f, 1f, 2f).forEach { i ->
                val y = stroke / 2f + i * (stroke + gap)
                drawLine(
                    color = color,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

package com.torrenttunes.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrenttunes.app.R
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
 */
@Composable
private fun TabGlyph(tab: AppTab, color: Color, modifier: Modifier) {
    // One family for all three, so the bar reads as a set rather than as three
    // separately-drawn approximations.
    val res = when (tab) {
        AppTab.DISCOVERY -> R.drawable.ic_discovery
        AppTab.SEARCH -> R.drawable.ic_search
        AppTab.LIBRARY -> R.drawable.ic_library
    }
    Icon(
        painter = painterResource(res),
        contentDescription = null,
        tint = color,
        modifier = modifier,
    )
}

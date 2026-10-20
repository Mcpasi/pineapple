package com.torrenttunes.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------- palette
//
// Spotify's and Apple Music's dark ground, with SoundCloud's orange kept as
// the one loud accent. These are **roles, not literals**: `ScText` is whatever
// the body colour is on the current ground, `ScSurface` whatever a filled
// control sits on. That is what lets the whole app go dark by editing this
// file alone.
//
// The ground is #121212 — dark enough to disappear behind album art, light
// enough that hairlines and pressed states are still visible. Pure black is
// reserved for the full-screen player, where it makes the artwork float.

/** The brand orange. The one thing carried over from the light design. */
val ScOrange = Color(0xFFFF5500)

/** Body type. Near-white rather than #FFF, which glares on a dark ground. */
val ScText = Color(0xFFEDEDED)

/** Secondary type: timestamps, source names, sub-labels. */
val ScTextDim = Color(0xFF9B9B9B)

/** Hairline dividers between list rows. */
val ScDivider = Color(0xFF262626)

/** Filled control backgrounds — the grey pill behind the search field. */
val ScSurface = Color(0xFF1E1E1E)

/** One step up from the ground: cards, sheets, the mini-player. */
val ScCard = Color(0xFF191919)

/** Waveform bars that are behind the playhead. */
val ScWaveRest = Color(0xFF3A3A3A)

/**
 * The full-screen player's ground. It is tinted per track (see
 * `playerGround`), so these are only the untinted fallbacks.
 */
val ScPlayerTop = Color(0xFF1B1D21)
val ScPlayerBottom = Color(0xFF08090B)

private val PineappleColors = darkColorScheme(
    primary = ScOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A1600),
    onPrimaryContainer = Color(0xFFFFD2BC),
    secondary = ScText,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondaryContainer = ScText,
    tertiary = Color(0xFF2FB79B),
    onTertiary = Color.Black,
    background = Color(0xFF121212),
    onBackground = ScText,
    surface = Color(0xFF121212),
    onSurface = ScText,
    surfaceVariant = ScSurface,
    onSurfaceVariant = ScTextDim,
    outline = Color(0xFF333333),
    outlineVariant = ScDivider,
    error = Color(0xFFFF6B61),
    onError = Color.Black,
    errorContainer = Color(0xFF3A1512),
    onErrorContainer = Color(0xFFFFD2CE),
    surfaceContainer = ScCard,
    surfaceContainerHigh = Color(0xFF1F1F1F),
)

// ------------------------------------------------------------- typography
//
// No new typeface — the app ships no font resource and adding one would mean
// a new dependency. The modern feel comes from weight and tracking instead:
// heavy titles pulled slightly tight, quiet metadata opened slightly up.

private val PineappleTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        letterSpacing = (-0.6).sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, letterSpacing = 0.1.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 13.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.3.sp),
)

/** Dark-only: the light SoundCloud palette this replaced is gone. */
@Composable
fun TorrentTunesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PineappleColors,
        typography = PineappleTypography,
        content = content,
    )
}

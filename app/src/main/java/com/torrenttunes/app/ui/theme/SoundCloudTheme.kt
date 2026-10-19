package com.torrenttunes.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------- palette
//
// SoundCloud's look in four words: white ground, black type, one loud orange,
// hairline dividers. Everything else (the artwork tiles, the waveform) is
// graded against these, so they live here rather than at each call site.

/** The brand orange. The single most recognisable thing about SoundCloud. */
val ScOrange = Color(0xFFFF5500)

/** Near-black body type — pure #000 is harsh against white. */
val ScInk = Color(0xFF1A1A1A)

/** Secondary type: timestamps, source names, sub-labels. */
val ScInkSoft = Color(0xFF737373)

/** Hairline dividers between list rows. */
val ScLine = Color(0xFFEAEAEA)

/** Filled control backgrounds — the grey pill behind the search field. */
val ScFill = Color(0xFFF2F2F2)

/** Waveform bars that are behind the playhead. */
val ScWaveRest = Color(0xFFD8D8D8)

private val SoundCloudColors = lightColorScheme(
    primary = ScOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE7DA),
    onPrimaryContainer = Color(0xFF7A2C00),
    secondary = ScInk,
    onSecondary = Color.White,
    secondaryContainer = ScFill,
    onSecondaryContainer = ScInk,
    tertiary = Color(0xFF12967B),
    onTertiary = Color.White,
    background = Color.White,
    onBackground = ScInk,
    surface = Color.White,
    onSurface = ScInk,
    surfaceVariant = ScFill,
    onSurfaceVariant = ScInkSoft,
    outline = Color(0xFFDDDDDD),
    outlineVariant = ScLine,
    error = Color(0xFFD8342B),
    onError = Color.White,
    errorContainer = Color(0xFFFDECEA),
    onErrorContainer = Color(0xFF8E211B),
)

// ------------------------------------------------------------- typography
//
// SoundCloud leans on a heavy weight for titles and a small, quiet grey for
// metadata — that contrast is most of what makes a row read instantly.

private val SoundCloudTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.Black, fontSize = 26.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 21.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 13.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

/**
 * Light-only on purpose: this app is styled after SoundCloud's classic
 * white-and-orange look, and a dark palette would flatten the artwork tiles
 * and waveform that carry the resemblance.
 */
@Composable
fun TorrentTunesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SoundCloudColors,
        typography = SoundCloudTypography,
        content = content,
    )
}

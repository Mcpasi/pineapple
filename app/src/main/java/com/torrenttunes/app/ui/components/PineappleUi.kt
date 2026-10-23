package com.torrenttunes.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrenttunes.app.R
import com.torrenttunes.app.search.SearchResult
import com.torrenttunes.app.ui.theme.PineAccent
import com.torrenttunes.app.ui.theme.PineDivider
import com.torrenttunes.app.ui.theme.PineGutter
import com.torrenttunes.app.ui.theme.PineRadius
import com.torrenttunes.app.ui.theme.PineSpace
import com.torrenttunes.app.ui.theme.PineSurfaceHigh
import com.torrenttunes.app.ui.theme.PineTextPrimary
import com.torrenttunes.app.ui.theme.PineTextSecondary
import com.torrenttunes.app.ui.theme.PineTouchTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sin

// ------------------------------------------------------------ adaptive width
//
// Material publishes WindowSizeClass as its own artifact. The breakpoints are
// public and trivial, so they are reproduced here from the configuration
// instead — one fewer dependency for a decision that is three lines long.

/** Mirrors Material's width classes: <600 dp, <840 dp, else Expanded. */
enum class PineWindowWidth { Compact, Medium, Expanded }

/**
 * The current window's width class.
 *
 * Drives list-versus-grid and how far a card row stretches, rather than
 * guessing a device's dimensions.
 */
@Composable
fun pineWindowWidth(): PineWindowWidth {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        widthDp < 600 -> PineWindowWidth.Compact
        widthDp < 840 -> PineWindowWidth.Medium
        else -> PineWindowWidth.Expanded
    }
}

// ---------------------------------------------------------------- artwork
//
// Deterministic gradients keyed to the title stand in for cover art, so a row
// with no thumbnail still looks like part of a record shelf. Two rows from the
// same artist differ, and the same track always looks the same — which is what
// makes a list scannable.

private val ArtworkPalettes = listOf(
    listOf(Color(0xFFFF9E2C), Color(0xFFF04E00)),
    listOf(Color(0xFF7B5CFF), Color(0xFF3D2BFF)),
    listOf(Color(0xFF00C2A8), Color(0xFF0077B6)),
    listOf(Color(0xFFFF5F6D), Color(0xFFFFC371)),
    listOf(Color(0xFF9B5CFF), Color(0xFF4A00E0)),
    listOf(Color(0xFF18A97B), Color(0xFF38EF7D)),
    listOf(Color(0xFFFC466B), Color(0xFF3F5EFB)),
    listOf(Color(0xFFED213A), Color(0xFF93291E)),
    listOf(Color(0xFF22C1E0), Color(0xFF1B6CA8)),
    listOf(Color(0xFFF7971E), Color(0xFFFFD200)),
)

private fun artworkPalette(seed: String): List<Color> {
    // `and MAX_VALUE` rather than abs(): abs(Int.MIN_VALUE) is still negative.
    val index = (seed.hashCode() and Int.MAX_VALUE) % ArtworkPalettes.size
    return ArtworkPalettes[index]
}

/** Scales toward black without touching alpha, so the colour stays saturated. */
private fun Color.darkened(factor: Float): Color =
    Color(red = red * factor, green = green * factor, blue = blue * factor)

/**
 * The full-screen player's ground — a deep, dark tint of whatever [seed] would
 * have coloured its artwork.
 *
 * This is what separates a modern player from a form on a grey card: the
 * background is per-track, so the screen visibly belongs to the song. Pulled
 * back hard enough that it reads as tinted black rather than as a colour
 * field, and anchored on the same near-black at the bottom so text always has
 * something dark beneath it.
 *
 * Derived from the title rather than the bitmap: extracting a palette from the
 * decoded album art would mean an AndroidX Palette dependency, and this app
 * ships none.
 */
fun playerGroundColors(seed: String): List<Color> {
    val palette = artworkPalette(seed)
    return listOf(
        palette[0].darkened(0.38f),
        palette[1].darkened(0.24f),
        Color(0xFF08090B),
    )
}

/** A rounded gradient tile standing in for album art. */
@Composable
fun Artwork(
    seed: String,
    size: Dp,
    corner: Dp = PineRadius.Sm,
    modifier: Modifier = Modifier,
) {
    val palette = remember(seed) { artworkPalette(seed) }
    val letter = remember(seed) {
        seed.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "♪"
    }
    val letterSize = with(LocalDensity.current) { (size * 0.38f).toSp() }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(palette)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter,
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = letterSize,
        )
    }
}

// --------------------------------------------------------------- thumbnail

/**
 * Real album art, with the synthesised gradient as its fallback.
 *
 * YouTube serves `i.ytimg.com/vi/{id}/mqdefault.jpg` (a ~10 KB JPEG), so a
 * list of results reads like an actual record shelf rather than a wall of
 * tiles. Decoded bitmaps are memoised by URL — a scroll re-uses them instead of
 * re-downloading, which matters because a LazyColumn reuses nothing here.
 */
@Composable
fun Thumbnail(
    url: String,
    seed: String,
    size: Dp,
    corner: Dp = PineRadius.Sm,
    modifier: Modifier = Modifier,
) {
    if (url.isEmpty()) {
        Artwork(seed, size, corner, modifier)
        return
    }

    val bitmap by produceState<Bitmap?>(initialValue = ThumbnailCache.get(url), url) {
        if (value != null) return@produceState

        // produceState's producer runs on the MAIN thread. Fetching there
        // throws NetworkOnMainThreadException — and because runCatching used to
        // swallow it, every tile silently fell back to a gradient instead of
        // failing visibly. Hop off the UI thread, and stop hiding the error.
        val downloaded = withContext(Dispatchers.IO) {
            runCatching { downloadBitmap(url) }.getOrNull()
        }
        if (downloaded == null) {
            Log.w("Thumbnail", "could not load $url")
            return@produceState
        }
        ThumbnailCache.put(url, downloaded)
        value = downloaded
    }

    // Captured: `bitmap` is a delegated property, so it won't smart-cast.
    val loaded = bitmap
    if (loaded == null) {
        Artwork(seed, size, corner, modifier)
    } else {
        Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(corner)),
        )
    }
}

/** Artwork that fills its box rather than being sized — the player's hero art. */
@Composable
fun ThumbnailFill(
    url: String,
    seed: String,
    corner: Dp,
    modifier: Modifier = Modifier,
) {
    val palette = remember(seed) { artworkPalette(seed) }
    if (url.isEmpty()) {
        Box(modifier.fillMaxSize().clip(RoundedCornerShape(corner)).background(Brush.linearGradient(palette)))
        return
    }

    val bitmap by produceState<Bitmap?>(initialValue = ThumbnailCache.get(url), url) {
        if (value != null) return@produceState
        val downloaded = withContext(Dispatchers.IO) {
            runCatching { downloadBitmap(url) }.getOrNull()
        }
        if (downloaded != null) {
            ThumbnailCache.put(url, downloaded)
            value = downloaded
        }
    }

    val loaded = bitmap
    if (loaded == null) {
        Box(modifier.fillMaxSize().clip(RoundedCornerShape(corner)).background(Brush.linearGradient(palette)))
    } else {
        Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize().clip(RoundedCornerShape(corner)),
        )
    }
}

/** ~8 MB of decoded thumbnails; sized in KB so LruCache can evict proportionally. */
private val ThumbnailCache = object : LruCache<String, Bitmap>(8 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
}

private fun downloadBitmap(url: String): Bitmap? {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 8_000
    conn.readTimeout = 8_000
    conn.instanceFollowRedirects = true
    return try {
        conn.inputStream.use { BitmapFactory.decodeStream(it) }
    } finally {
        conn.disconnect()
    }
}

// ---------------------------------------------------------------- waveform

/** Deterministic 0..1 from (seed, index). */
private fun hashUnit(seed: Int, index: Int): Float {
    // Standard integer-mixing constants (Knuth/murmur3). The hex literals exceed
    // Int.MAX_VALUE so they land as Long and need the explicit narrowing cast.
    var h = seed xor (index * 0x9E3779B9.toInt())
    h = h * 0x85EBCA6B.toInt() + (h ushr 13)
    h = h * 0xC2B2AE35.toInt() + (h ushr 16)
    return (h and 0x7FFFFFFF) / Int.MAX_VALUE.toFloat()
}

/**
 * A dense field of thin vertical bars, accent up to the playhead and quiet
 * beyond it. Bar heights are a pure function of (seed, index) so nothing is
 * allocated per frame, and an envelope quiets both ends so it reads as music
 * rather than as static.
 */
@Composable
fun Waveform(
    seed: String,
    progress: Float,
    modifier: Modifier = Modifier,
    playedColor: Color = PineAccent,
    unplayedColor: Color = PineSurfaceHigh,
    barWidth: Dp = 2.dp,
    gap: Dp = 1.5.dp,
) {
    val hash = remember(seed) { seed.hashCode() }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val barPx = barWidth.toPx().coerceAtLeast(1f)
        val gapPx = gap.toPx().coerceAtLeast(0.5f)
        val step = barPx + gapPx
        val count = ((w + gapPx) / step).toInt().coerceAtLeast(1)
        val mid = h / 2f
        val playedX = progress.coerceIn(0f, 1f) * w

        for (i in 0 until count) {
            val x = i * step + barPx / 2f
            val t = i.toFloat() / (count - 1).coerceAtLeast(1)
            val envelope = 0.45f + 0.55f * sin(Math.PI * t).toFloat()
            val bar = (0.28f + 0.72f * hashUnit(hash, i)) * envelope * h
            drawLine(
                color = if (x <= playedX) playedColor else unplayedColor,
                start = Offset(x, mid - bar / 2f),
                end = Offset(x, mid + bar / 2f),
                strokeWidth = barPx,
                cap = StrokeCap.Round,
            )
        }
    }
}

// ------------------------------------------------------------------ glyphs
//
// These are Material Symbols Rounded (Apache-2.0), converted from the
// upstream SVG into Android vector drawables -- see drawable/ic_*.xml.
//
// Vectors rather than the hand-drawn Canvas glyphs they replace: a drawn
// approximation of a compass or a library mark reads as amateur next to a
// real icon set, and vector XML costs about 1 KB each so the APK barely
// notices. This is bundled artwork, not a Gradle dependency, which is what
// keeps it inside the project's "no new dependencies" rule.
//
// Every one is tinted at the call site, so a single asset covers a control's
// active, inactive and accent states.

/** Renders a bundled vector, tinted. The one place icons are loaded. */
@Composable
fun PineIcon(res: Int, glyphSize: Dp, color: Color, modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(res),
        contentDescription = null,
        tint = color,
        modifier = modifier.size(glyphSize),
    )
}

@Composable
fun PlayGlyph(glyphSize: Dp, color: Color) = PineIcon(R.drawable.ic_play, glyphSize, color)

@Composable
fun PauseGlyph(glyphSize: Dp, color: Color) = PineIcon(R.drawable.ic_pause, glyphSize, color)

@Composable
fun SearchGlyph(glyphSize: Dp, color: Color, strokeWidth: Dp = 2.dp) =
    PineIcon(R.drawable.ic_search, glyphSize, color)

@Composable
fun NextGlyph(glyphSize: Dp, color: Color) = PineIcon(R.drawable.ic_next, glyphSize, color)

@Composable
fun PreviousGlyph(glyphSize: Dp, color: Color) =
    PineIcon(R.drawable.ic_previous, glyphSize, color)

@Composable
fun ChevronDownGlyph(glyphSize: Dp, color: Color, strokeWidth: Dp = 2.2.dp) =
    PineIcon(R.drawable.ic_chevron_down, glyphSize, color)

@Composable
fun MoreGlyph(glyphSize: Dp, color: Color) = PineIcon(R.drawable.ic_more, glyphSize, color)

@Composable
fun ShuffleGlyph(glyphSize: Dp, color: Color, strokeWidth: Dp = 1.8.dp) =
    PineIcon(R.drawable.ic_shuffle, glyphSize, color)

@Composable
fun RepeatGlyph(glyphSize: Dp, color: Color, once: Boolean = false, strokeWidth: Dp = 1.8.dp) =
    PineIcon(R.drawable.ic_repeat, glyphSize, color)

@Composable
fun QueueGlyph(glyphSize: Dp, color: Color, strokeWidth: Dp = 1.9.dp) =
    PineIcon(R.drawable.ic_queue, glyphSize, color)

@Composable
fun PinGlyph(glyphSize: Dp, color: Color) = PineIcon(R.drawable.ic_pin, glyphSize, color)

@Composable
fun PlusGlyph(glyphSize: Dp, color: Color, strokeWidth: Dp = 2.dp) =
    PineIcon(R.drawable.ic_add, glyphSize, color)

@Composable
fun CloseGlyph(glyphSize: Dp, color: Color, strokeWidth: Dp = 2.dp) =
    PineIcon(R.drawable.ic_close, glyphSize, color)

@Composable
fun RetryGlyph(glyphSize: Dp, color: Color, strokeWidth: Dp = 1.9.dp) =
    PineIcon(R.drawable.ic_retry, glyphSize, color)

/** Back chevron for the playlist header. */
@Composable
fun BackGlyph(glyphSize: Dp, color: Color) = PineIcon(R.drawable.ic_back, glyphSize, color)

/** Heart, filled or outlined — the same silhouette everywhere it appears. */
@Composable
fun HeartGlyph(glyphSize: Dp, color: Color, filled: Boolean) =
    PineIcon(
        if (filled) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline,
        glyphSize,
        color,
    )

// ------------------------------------------------------- reusable controls

/**
 * A round icon button with a real touch target.
 *
 * Every interactive glyph goes through this, so no control ends up below
 * Android's 48 dp accessibility minimum — the previous design had several
 * 34 dp transport buttons.
 */
@Composable
fun PineIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    background: Color = Color.Transparent,
    targetSize: Dp = PineTouchTarget,
    glyph: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(targetSize)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { glyph() }
}

// -------------------------------------------------------------- track row

/**
 * The standard track row: artwork, title, artist, duration, overflow.
 *
 * Shared by Search, Discovery's recents, the Library lists and the queue, so
 * all four read as the same object. The whole row is tappable; [onLongClick]
 * opens the actions sheet.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: SearchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onOverflow: (() -> Unit)? = null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    isLoading: Boolean = false,
    artworkSize: Dp = 52.dp,
    leading: (@Composable () -> Unit)? = null,
    showArtwork: Boolean = true,
    divider: Boolean = true,
) {
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    enabled = !isLoading,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(horizontal = PineGutter, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(PineSpace.Md))
            }

            if (showArtwork) {
                Box {
                    Thumbnail(track.thumbUrl, track.title, size = artworkSize, corner = PineRadius.Sm)
                    if (isLoading) {
                        Box(
                            Modifier
                                .size(artworkSize)
                                .clip(RoundedCornerShape(PineRadius.Sm))
                                .background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                Modifier.size(artworkSize * 0.42f),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(PineSpace.Md))
            }

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isCurrent) PineAccent else PineTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isPlaying) {
                        Spacer(Modifier.width(PineSpace.Sm))
                        NowPlayingBars(color = PineAccent)
                    }
                }

                Spacer(Modifier.height(PineSpace.Xxs))

                val meta = buildString {
                    if (track.channel.isNotEmpty()) append(track.channel)
                    if (track.durationLabel.isNotEmpty()) {
                        if (isNotEmpty()) append("  ·  ")
                        append(track.durationLabel)
                    } else if (track.viewsLabel.isNotEmpty()) {
                        if (isNotEmpty()) append("  ·  ")
                        append(track.viewsLabel)
                    }
                }
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelMedium,
                        color = PineTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (onOverflow != null) {
                Spacer(Modifier.width(PineSpace.Xs))
                PineIconButton(
                    onClick = onOverflow,
                    targetSize = 44.dp,
                    glyph = { MoreGlyph(20.dp, PineTextSecondary) },
                )
            }
        }

        if (divider) {
            RowDivider()
        }
    }
}

/** A divider indented to sit under the row's text rather than its artwork. */
@Composable
fun RowDivider(startIndent: Dp = 84.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(1.dp)
            .background(PineDivider),
    )
}

/** Three animated bars marking the row that is actually playing. */
@Composable
fun NowPlayingBars(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "bars")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(620),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bars-phase",
    )
    Canvas(modifier.size(13.dp)) {
        val w = size.width
        val h = size.height
        val barW = w * 0.2f
        val gap = w * 0.1f
        val heights = listOf(0.55f + 0.35f * phase, 1f, 0.45f + 0.4f * (1f - phase))
        heights.forEachIndexed { i, frac ->
            val barH = (h * frac).coerceAtLeast(barW)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barW + gap), (h - barH) / 2f),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

// ---------------------------------------------------------------- headers

/** A section heading with an optional trailing action. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = PineGutter, end = PineGutter, top = PineSpace.Xl, bottom = PineSpace.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = PineTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

// --------------------------------------------------------------- skeleton

/**
 * A shimmering placeholder block.
 *
 * The pulse is a single alpha animation on a flat fill — no blur, no gradient
 * sweep — because an infinite animation is exactly where a cheap phone starts
 * to drop frames.
 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    corner: Dp = PineRadius.Sm,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(780),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton-alpha",
    )
    Box(
        modifier
            .alpha(alpha)
            .clip(RoundedCornerShape(corner))
            .background(PineSurfaceHigh),
    )
}

/** A row skeleton matching [TrackRow]'s footprint, so nothing jumps when data lands. */
@Composable
fun TrackRowSkeleton(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = PineGutter, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBlock(Modifier.size(52.dp))
        Spacer(Modifier.width(PineSpace.Md))
        Column(Modifier.weight(1f)) {
            SkeletonBlock(Modifier.fillMaxWidth(0.62f).height(13.dp))
            Spacer(Modifier.height(PineSpace.Sm))
            SkeletonBlock(Modifier.fillMaxWidth(0.38f).height(11.dp))
        }
    }
}

// ------------------------------------------------------------ empty state

/** The filled accent pill used for primary actions. Dark type on orange. */
@Composable
fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    background: Color = PineAccent,
    contentColor: Color = Color(0xFF140A00),
) {
    Box(
        modifier
            .clip(RoundedCornerShape(PineTouchTarget))
            .background(if (enabled) background else PineSurfaceHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = PineSpace.Xxl, vertical = PineSpace.Md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) contentColor else PineTextSecondary,
            maxLines = 1,
        )
    }
}

/**
 * The shared empty/error state: a glyph, a headline, a line of guidance and an
 * optional action.
 */
@Composable
fun StateMessage(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    glyph: (@Composable (Dp, Color) -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = PineSpace.Xxxl, vertical = PineSpace.Xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (glyph != null) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(PineSurfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                glyph(32.dp, PineAccent)
            }
            Spacer(Modifier.height(PineSpace.Lg))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = PineTextPrimary,
            textAlign = TextAlign.Center,
        )
        if (body != null) {
            Spacer(Modifier.height(PineSpace.Sm))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = PineTextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(PineSpace.Lg))
            PillButton(label = actionLabel, onClick = onAction)
        }
    }
}

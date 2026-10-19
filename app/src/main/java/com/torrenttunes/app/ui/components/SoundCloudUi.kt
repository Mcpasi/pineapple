package com.torrenttunes.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrenttunes.app.ui.theme.ScOrange
import com.torrenttunes.app.ui.theme.ScWaveRest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sin

// Torrents carry no cover art, so artwork is synthesised: a gradient picked
// deterministically from the title plus its initial. Two rows from the same
// artist still differ, and the same torrent always looks the same — which is
// what makes a list scannable the way an album-art grid is.

private val ArtworkPalettes = listOf(
    listOf(Color(0xFFFF8A00), Color(0xFFF44000)),
    listOf(Color(0xFF7B5CFF), Color(0xFF3D2BFF)),
    listOf(Color(0xFF00B8A9), Color(0xFF0077B6)),
    listOf(Color(0xFFFF5F6D), Color(0xFFFFC371)),
    listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0)),
    listOf(Color(0xFF11998E), Color(0xFF38EF7D)),
    listOf(Color(0xFFFC466B), Color(0xFF3F5EFB)),
    listOf(Color(0xFFED213A), Color(0xFF93291E)),
)

private fun artworkPalette(seed: String): List<Color> {
    // `and MAX_VALUE` rather than abs(): abs(Int.MIN_VALUE) is still negative.
    val index = (seed.hashCode() and Int.MAX_VALUE) % ArtworkPalettes.size
    return ArtworkPalettes[index]
}

/** Rounded gradient tile standing in for album art. */
@Composable
fun Artwork(
    seed: String,
    size: Dp,
    corner: Dp = 6.dp,
    modifier: Modifier = Modifier,
) {
    val palette = remember(seed) { artworkPalette(seed) }
    val letter = remember(seed) {
        seed.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "♪"
    }
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
            fontSize = (size.value * 0.38f).sp,
        )
    }
}

// ------------------------------------------------------------------ waveform
//
// SoundCloud's signature element: a dense field of thin vertical bars, orange
// up to the playhead and grey beyond it. Bar heights are a pure function of
// (seed, index) so nothing is allocated per frame, and an envelope quiets both
// ends of the track so it reads as music rather than as static.

/** Deterministic 0..1 from (seed, index). */
private fun hashUnit(seed: Int, index: Int): Float {
    // Standard integer-mixing constants (Knuth/murmur3). The hex literals exceed
    // Int.MAX_VALUE so they land as Long and need the explicit narrowing cast.
    var h = seed xor (index * 0x9E3779B9.toInt())
    h = h * 0x85EBCA6B.toInt() + (h ushr 13)
    h = h * 0xC2B2AE35.toInt() + (h ushr 16)
    return (h and 0x7FFFFFFF) / Int.MAX_VALUE.toFloat()
}

@Composable
fun Waveform(
    seed: String,
    progress: Float,
    modifier: Modifier = Modifier,
    playedColor: Color = ScOrange,
    unplayedColor: Color = ScWaveRest,
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

// --------------------------------------------------------------------- glyphs
//
// Drawn rather than loaded from a resource: no icon dependency, no font
// coverage question, and they stay crisp at any size.

@Composable
fun PlayGlyph(glyphSize: Dp, color: Color) {
    Canvas(Modifier.size(glyphSize)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.34f, h * 0.24f)
            lineTo(w * 0.34f, h * 0.76f)
            lineTo(w * 0.78f, h * 0.50f)
            close()
        }
        drawPath(path, color = color)
    }
}

@Composable
fun PauseGlyph(glyphSize: Dp, color: Color) {
    Canvas(Modifier.size(glyphSize)) {
        val w = size.width
        val h = size.height
        val barW = w * 0.17f
        val top = h * 0.24f
        val bottom = h * 0.76f
        val radius = CornerRadius(barW / 2f, barW / 2f)
        drawRoundRect(color, Offset(w * 0.27f, top), Size(barW, bottom - top), radius)
        drawRoundRect(color, Offset(w * 0.56f, top), Size(barW, bottom - top), radius)
    }
}

@Composable
fun SearchGlyph(glyphSize: Dp, color: Color, strokeWidth: Dp = 2.dp) {
    Canvas(Modifier.size(glyphSize)) {
        val w = size.width
        val h = size.height
        val stroke = strokeWidth.toPx()
        val radius = w * 0.27f
        val cx = w * 0.43f
        val cy = h * 0.43f
        drawCircle(color, radius, Offset(cx, cy), style = Stroke(stroke))
        val d = radius * 0.7071f
        drawLine(
            color,
            Offset(cx + d, cy + d),
            Offset(w * 0.87f, h * 0.87f),
            stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun NextGlyph(glyphSize: Dp, color: Color) {
    Canvas(Modifier.size(glyphSize)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.26f, h * 0.24f)
            lineTo(w * 0.26f, h * 0.76f)
            lineTo(w * 0.72f, h * 0.50f)
            close()
        }
        drawPath(path, color = color)
        // The trailing bar is what separates "next" from "play".
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.78f, h * 0.24f),
            size = Size(w * 0.12f, h * 0.52f),
            cornerRadius = CornerRadius(w * 0.06f, w * 0.06f),
        )
    }
}

@Composable
fun PreviousGlyph(glyphSize: Dp, color: Color) {
    Canvas(Modifier.size(glyphSize)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.74f, h * 0.24f)
            lineTo(w * 0.74f, h * 0.76f)
            lineTo(w * 0.28f, h * 0.50f)
            close()
        }
        drawPath(path, color = color)
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.10f, h * 0.24f),
            size = Size(w * 0.12f, h * 0.52f),
            cornerRadius = CornerRadius(w * 0.06f, w * 0.06f),
        )
    }
}

/** Small outlined pill, used for section labels. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Black,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.2.sp,
        modifier = modifier,
    )
}

// ----------------------------------------------------------------- thumbnail

/**
 * Real album art, with the synthesised gradient as its fallback.
 *
 * YouTube serves `i.ytimg.com/vi/{id}/mqdefault.jpg` (a ~10 KB JPEG), so a list
 * of results reads like an actual record shelf rather than a wall of tiles.
 * Decoded bitmaps are memoised by URL — a scroll re-uses them instead of
 * re-downloading, which matters because a LazyColumn reuses nothing here.
 */
@Composable
fun Thumbnail(
    url: String,
    seed: String,
    size: Dp,
    corner: Dp = 6.dp,
    modifier: Modifier = Modifier,
) {
    if (url.isEmpty()) {
        Artwork(seed, size, corner, modifier)
        return
    }

    val bitmap by produceState<Bitmap?>(initialValue = ThumbnailCache.get(url), url) {
        if (value != null) return@produceState

        // produceState's producer runs in the composition's coroutine context,
        // which is the MAIN thread. Fetching here throws
        // NetworkOnMainThreadException — and because runCatching used to swallow
        // it, every tile silently fell back to a gradient instead of failing
        // visibly. Hop off the UI thread, and stop hiding the error.
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
        // Shown for the first frame while the JPEG arrives.
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

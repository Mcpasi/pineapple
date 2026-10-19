package com.torrenttunes.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrenttunes.app.library.LibraryViewModel
import com.torrenttunes.app.search.SearchResult
import com.torrenttunes.app.ui.components.NextGlyph
import com.torrenttunes.app.ui.components.PauseGlyph
import com.torrenttunes.app.ui.components.PlayGlyph
import com.torrenttunes.app.ui.components.PreviousGlyph
import com.torrenttunes.app.ui.components.Thumbnail
import com.torrenttunes.app.ui.theme.ScFill
import com.torrenttunes.app.ui.theme.ScInk
import com.torrenttunes.app.ui.theme.ScOrange
import com.torrenttunes.app.ui.theme.ScInkSoft
import java.util.Locale

/**
 * Full-screen now-playing, laid out the way Spotify's is: art first, then
 * identity, then the seek bar, then transport — with the like toggle reachable
 * by either thumb.
 *
 * It reads straight off the same flows the mini-player uses, so both stay in
 * agreement without any second source of truth to keep in sync.
 */
@Composable
fun NowPlayingScreen(
    vm: MainViewModel,
    library: LibraryViewModel,
    currentTrack: SearchResult?,
    onClose: () -> Unit,
) {
    val nowPlaying by vm.nowPlaying.collectAsState()
    val artist by vm.artist.collectAsState()
    val artwork by vm.artworkUrl.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val positionMs by vm.positionMs.collectAsState()
    val durationMs by vm.durationMs.collectAsState()
    val uiState by vm.state.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding(),
    ) {
        // ---------------------------------------------------------------- bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(ScFill)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                ChevronDown(Modifier.size(20.dp))
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "PLAYING FROM",
                    fontSize = 9.sp,
                    letterSpacing = 1.4.sp,
                    fontWeight = FontWeight.Black,
                    color = ScInkSoft,
                )
                Text(
                    "Search results",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = ScInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(40.dp))
        }

        // ---------------------------------------------------------------- art
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp)),
        ) {
            Thumbnail(
                artwork,
                nowPlaying.ifEmpty { "Pineapple" },
                size = 340.dp,
                corner = 14.dp,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x14000000)))),
            )
        }

        // ------------------------------------------------------------ identity
        Spacer(Modifier.height(26.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    nowPlaying.ifEmpty { "Nothing playing" },
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = ScInk,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    artist.ifEmpty { "Search for a song to start" },
                    fontSize = 15.sp,
                    color = ScInkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            LikeToggle(
                liked = currentTrack?.let { library.isLiked(it.videoId) } ?: false,
                onToggle = { currentTrack?.let { library.toggleLike(it) } },
            )
        }

        // ----------------------------------------------------------- seek bar
        Spacer(Modifier.height(22.dp))
        Column(Modifier.padding(horizontal = 24.dp)) {
            val progress =
                if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            Slider(
                value = progress,
                onValueChange = { frac ->
                    if (durationMs > 0) vm.seekTo((frac * durationMs).toLong())
                },
                colors = SliderDefaults.colors(
                    thumbColor = ScOrange,
                    activeTrackColor = ScOrange,
                    inactiveTrackColor = ScFill,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmtMs(positionMs), fontSize = 12.sp, color = ScInkSoft)
                Text(fmtMs(durationMs), fontSize = 12.sp, color = ScInkSoft)
            }
        }

        // ------------------------------------------------------------ transport
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .clickable { vm.previous() },
                contentAlignment = Alignment.Center,
            ) {
                PreviousGlyph(30.dp, ScInk)
            }
            Spacer(Modifier.width(30.dp))
            Box(
                Modifier
                    .size(74.dp)
                    .clip(CircleShape)
                    .background(ScOrange)
                    .clickable { vm.togglePlayPause() },
                contentAlignment = Alignment.Center,
            ) {
                if (isPlaying) PauseGlyph(30.dp, Color.White) else PlayGlyph(30.dp, Color.White)
            }
            Spacer(Modifier.width(30.dp))
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .clickable { vm.next() },
                contentAlignment = Alignment.Center,
            ) {
                NextGlyph(30.dp, ScInk)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            if (uiState.queueSize > 1) {
                "${uiState.queueSize} tracks in this mix"
            } else {
                "Continuous mix builds as you listen"
            },
            fontSize = 12.sp,
            color = ScInkSoft,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(Modifier.height(24.dp))
    }
}

// ------------------------------------------------------------------ helpers

@Composable
private fun LikeToggle(liked: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(ScFill)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        HeartGlyph(Modifier.size(22.dp), if (liked) ScOrange else ScInkSoft)
    }
}

@Composable
private fun ChevronDown(modifier: Modifier) = Canvas(modifier) {
    val s = size.minDimension
    val stroke = s * 0.12f
    drawLine(
        ScInk,
        Offset(s * 0.24f, s * 0.38f),
        Offset(s * 0.50f, s * 0.62f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        ScInk,
        Offset(s * 0.50f, s * 0.62f),
        Offset(s * 0.76f, s * 0.38f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}

@Composable
private fun HeartGlyph(modifier: Modifier, color: Color) = Canvas(modifier) {
    val w = size.width
    val h = size.height
    val k = 0.09f
    val r = w * 0.29f
    drawCircle(color, r, center = Offset(w * (0.5f - k), h * 0.36f))
    drawCircle(color, r, center = Offset(w * (0.5f + k), h * 0.36f))
    val path = Path().apply {
        moveTo(w * (0.5f - r), h * 0.42f)
        lineTo(w * 0.5f, h * 0.86f)
        lineTo(w * (0.5f + r), h * 0.42f)
        close()
    }
    drawPath(path, color)
}

/** 0:00 style readout — shared with the mini-player via [fmtMs]. */

package com.torrenttunes.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrenttunes.app.library.LibraryViewModel
import com.torrenttunes.app.search.SearchResult
import com.torrenttunes.app.ui.components.NextGlyph
import com.torrenttunes.app.ui.components.PauseGlyph
import com.torrenttunes.app.ui.components.PlayGlyph
import com.torrenttunes.app.ui.components.PreviousGlyph
import com.torrenttunes.app.ui.components.Thumbnail
import com.torrenttunes.app.ui.components.heartPath
import com.torrenttunes.app.ui.components.playerGroundColors
import com.torrenttunes.app.ui.theme.ScOrange
import com.torrenttunes.app.ui.theme.ScSurface
import com.torrenttunes.app.ui.theme.ScText
import com.torrenttunes.app.ui.theme.ScTextDim
import java.util.Locale

/**
 * Full-screen now-playing, laid out the way Spotify's is: art first, then
 * identity, then the seek bar, then transport — with the like toggle reachable
 * by either thumb.
 *
 * The ground is a per-track gradient rather than a flat colour (see
 * [playerGroundColors]), which is most of what separates a modern player from
 * a form on a grey card.
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

    // The same key the artwork tile uses, so background and cover agree.
    val seed = nowPlaying.ifEmpty { "Pineapple" }
    val ground = remember(seed) { playerGroundColors(seed) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(ground))
            .statusBarsPadding()
            .navigationBarsPadding(),
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
                    .background(Color.White.copy(alpha = 0.08f))
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
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.55f),
                )
                Text(
                    "Search results",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ScText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(40.dp))
        }

        // ---------------------------------------------------------------- art
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp)),
        ) {
            Thumbnail(
                artwork,
                seed,
                size = 340.dp,
                corner = 16.dp,
            )
            // A soft scrim along the bottom edge so the art sits *in* the
            // background instead of floating on top of it.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            1f to ground.last().copy(alpha = 0.55f),
                        ),
                    ),
            )
        }

        // ------------------------------------------------------------ identity
        Spacer(Modifier.height(26.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    nowPlaying.ifEmpty { "Nothing playing" },
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                    color = ScText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    artist.ifEmpty { "Search for a song to start" },
                    fontSize = 15.sp,
                    color = ScTextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(14.dp))
            LikeToggle(
                liked = currentTrack?.let { library.isLiked(it.videoId) } ?: false,
                onToggle = { currentTrack?.let { library.toggleLike(it) } },
            )
        }

        // ----------------------------------------------------------- seek bar
        Spacer(Modifier.height(20.dp))
        Column(Modifier.padding(horizontal = 26.dp)) {
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
                    inactiveTrackColor = Color.White.copy(alpha = 0.18f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmtMs(positionMs), fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f))
                Text(fmtMs(durationMs), fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f))
            }
        }

        // ------------------------------------------------------------ transport
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .clickable { vm.previous() },
                contentAlignment = Alignment.Center,
            ) {
                PreviousGlyph(30.dp, ScText)
            }
            Spacer(Modifier.width(28.dp))
            Box(
                Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(ScOrange, Color(0xFFE03F00))))
                    .clickable { vm.togglePlayPause() },
                contentAlignment = Alignment.Center,
            ) {
                if (isPlaying) PauseGlyph(31.dp, Color.White) else PlayGlyph(31.dp, Color.White)
            }
            Spacer(Modifier.width(28.dp))
            Box(
                Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .clickable { vm.next() },
                contentAlignment = Alignment.Center,
            ) {
                NextGlyph(30.dp, ScText)
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            if (uiState.queueSize > 1) {
                "${uiState.queueSize} tracks in this mix"
            } else {
                "Continuous mix builds as you listen"
            },
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(Modifier.height(18.dp))
    }
}

// ------------------------------------------------------------------ helpers

/**
 * The like toggle.
 *
 * The heart fills and springs on tap rather than just recolouring: a change
 * you can feel is what makes the button read as *saved* rather than as a
 * colour that happened to switch.
 */
@Composable
private fun LikeToggle(liked: Boolean, onToggle: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (liked) 1.12f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "like",
    )
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(23.dp).graphicsLayer { scaleX = scale; scaleY = scale }) {
            val path = heartPath(this.size.width, this.size.height)
            if (liked) {
                drawPath(path, ScOrange)
            } else {
                // Outlined when not liked, so the control is legible before
                // the first tap rather than after it.
                drawPath(path, color = ScTextDim, style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.width * 0.085f))
            }
        }
    }
}

@Composable
private fun ChevronDown(modifier: Modifier) = Canvas(modifier) {
    val s = size.minDimension
    val stroke = s * 0.12f
    drawLine(
        ScText,
        Offset(s * 0.24f, s * 0.38f),
        Offset(s * 0.50f, s * 0.62f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        ScText,
        Offset(s * 0.50f, s * 0.62f),
        Offset(s * 0.76f, s * 0.38f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}

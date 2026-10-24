package com.torrenttunes.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrenttunes.app.library.LibraryViewModel
import com.torrenttunes.app.search.SearchResult
import com.torrenttunes.app.ui.components.PineWindowWidth
import com.torrenttunes.app.ui.components.PineIconButton
import com.torrenttunes.app.ui.components.RetryGlyph
import com.torrenttunes.app.ui.components.SectionHeader
import com.torrenttunes.app.ui.components.SkeletonBlock
import com.torrenttunes.app.ui.components.StateMessage
import com.torrenttunes.app.ui.components.Thumbnail
import com.torrenttunes.app.ui.components.pineWindowWidth
import com.torrenttunes.app.ui.theme.PineAccent
import com.torrenttunes.app.ui.theme.PineGutter
import com.torrenttunes.app.ui.theme.PineRadius
import com.torrenttunes.app.ui.theme.PineSpace
import com.torrenttunes.app.ui.theme.PineSurfaceHigh
import com.torrenttunes.app.ui.theme.PineTextPrimary
import com.torrenttunes.app.ui.theme.PineTextSecondary
import java.util.Calendar

/**
 * Discovery: what is hot right now, plus a way back into what you played.
 *
 * Two sections because they answer two different questions — "where was I"
 * and "what is new". Trending comes from YouTube Music's chart; the rest is
 * local, so the tab still has something on it with no network at all.
 *
 * The chart is fetched once, on first arrival ([MainViewModel.ensureTrending]),
 * not in a ViewModel init: most sessions open on Search, and paying a browse
 * request for a tab nobody looked at is a wasted round trip.
 */
@Composable
fun DiscoveryScreen(
    vm: MainViewModel,
    library: LibraryViewModel,
    onPlay: (SearchResult) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenPlayer: () -> Unit,
    contentPadding: PaddingValues,
) {
    val trending by vm.trending.collectAsState()
    val loading by vm.trendingLoading.collectAsState()
    val error by vm.trendingError.collectAsState()
    val offline by vm.trendingOffline.collectAsState()
    val connection by vm.connection.collectAsState()
    val libraryState by library.state.collectAsState()
    val currentTrack by vm.currentTrack.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()

    // Runs once when the tab first composes.
    LaunchedEffect(Unit) { vm.ensureTrending() }

    val recent = libraryState.recent

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        // ----------------------------------------------------------- header
        item(key = "head") {
            DiscoveryHeader(
                recentCount = recent.size,
                onSearch = onOpenSearch,
            )
        }

        // ----------------------------------------------------------- recents
        if (recent.isNotEmpty()) {
            item(key = "recent-head") {
                SectionHeader("Recently played")
            }
            item(key = "recent-row") {
                RecentCarousel(
                    tracks = recent.take(12),
                    currentId = currentTrack?.videoId,
                    isPlaying = isPlaying,
                    onPlay = onPlay,
                    onOpenPlayer = onOpenPlayer,
                )
            }
        } else {
            item(key = "recent-empty") {
                EmptyRecents(onSearch = onOpenSearch)
            }
        }

        // ---------------------------------------------------------- trending
        when {
            trending.isNotEmpty() -> {
                item(key = "trend-head") {
                    SectionHeader("Trending now")
                }
                item(key = "trend-row") {
                    TrendingCarousel(tracks = trending.take(12), onPlay = onPlay)
                }
            }

            loading -> {
                item(key = "trend-head-loading") {
                    SectionHeader("Trending now")
                }
                item(key = "trend-row-loading") {
                    TrendingSkeletonRow()
                }
            }

            error != null -> {
                item(key = "trend-error") {
                    TrendingError(
                        message = if (offline && connection.online) "Try again when you're ready." else error ?: "",
                        title = when {
                            !offline -> "Trending is unavailable"
                            connection.online -> "Connection restored"
                            else -> "You're offline"
                        },
                        onRetry = { vm.retryTrending() },
                    )
                }
            }
        }

        item(key = "bottom") { Spacer(Modifier.height(PineSpace.Xxl)) }
    }
}

// ------------------------------------------------------------------ header

/**
 * A greeting that earns its space.
 *
 * Time-of-day is derived from the clock, not fetched, and the sub-line carries
 * a real count rather than a slogan — so the block is informative even before
 * anything has been searched.
 */
@Composable
private fun DiscoveryHeader(recentCount: Int, onSearch: () -> Unit) {
    val greeting = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        in 18..22 -> "Good evening"
        else -> "Still up"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = PineGutter, end = PineGutter, top = PineSpace.Lg, bottom = PineSpace.Sm),
    ) {
        Text(
            greeting,
            style = MaterialTheme.typography.labelMedium,
            color = PineTextSecondary,
        )
        Spacer(Modifier.height(PineSpace.Xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Discovery",
                style = MaterialTheme.typography.headlineMedium,
                color = PineTextPrimary,
                modifier = Modifier.weight(1f),
            )
            PineIconButton(
                onClick = onSearch,
                background = PineSurfaceHigh,
                targetSize = 42.dp,
                glyph = {
                    com.torrenttunes.app.ui.components.SearchGlyph(19.dp, PineTextSecondary)
                },
            )
        }
        if (recentCount > 0) {
            Spacer(Modifier.height(PineSpace.Xs))
            Text(
                "$recentCount track${if (recentCount == 1) "" else "s"} in your history",
                style = MaterialTheme.typography.bodySmall,
                color = PineTextSecondary,
            )
        }
    }
}

// -------------------------------------------------------------- carousels

@Composable
private fun RecentCarousel(
    tracks: List<SearchResult>,
    currentId: String?,
    isPlaying: Boolean,
    onPlay: (SearchResult) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = PineGutter),
        horizontalArrangement = Arrangement.spacedBy(PineSpace.Md),
    ) {
        items(tracks, key = { "recent-${it.videoId}" }) { track ->
            RecentCard(
                track = track,
                isCurrent = track.videoId == currentId,
                isPlaying = isPlaying,
                onClick = {
                    if (track.videoId == currentId) onOpenPlayer() else onPlay(track)
                },
            )
        }
    }
}

/** A recently-played card: square art, title and artist beneath. */
@Composable
private fun RecentCard(
    track: SearchResult,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(112.dp)
            .clip(RoundedCornerShape(PineRadius.Md))
            .clickable(onClick = onClick),
    ) {
        Box {
            Thumbnail(
                track.thumbUrl,
                track.title,
                size = 112.dp,
                corner = PineRadius.Md,
            )
            if (isCurrent) {
                // Tints the art so the card reads as "this is what's playing"
                // without adding a badge over the artwork.
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(PineRadius.Md))
                        .background(PineAccent.copy(alpha = 0.28f)),
                )
            }
        }
        Spacer(Modifier.height(PineSpace.Sm))
        Text(
            track.title,
            style = MaterialTheme.typography.titleSmall,
            color = if (isCurrent) PineAccent else PineTextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 17.sp,
        )
        if (track.channel.isNotEmpty()) {
            Spacer(Modifier.height(PineSpace.Xxs))
            Text(
                track.channel,
                style = MaterialTheme.typography.labelMedium,
                color = PineTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TrendingCarousel(tracks: List<SearchResult>, onPlay: (SearchResult) -> Unit) {
    // Wider cards on a wider window: the point of a carousel is to show art,
    // so the card may as well use the room it is given.
    val cardWidth: Dp = when (pineWindowWidth()) {
        PineWindowWidth.Compact -> 150.dp
        PineWindowWidth.Medium -> 168.dp
        PineWindowWidth.Expanded -> 186.dp
    }

    LazyRow(
        contentPadding = PaddingValues(horizontal = PineGutter),
        horizontalArrangement = Arrangement.spacedBy(PineSpace.Md),
    ) {
        items(tracks, key = { it.videoId }) { track ->
            TrendingCard(track = track, width = cardWidth) { onPlay(track) }
        }
    }
}

/** A trending entry as a card — larger artwork than a list row, because on a
 *  discovery surface the art is the thing being browsed. */
@Composable
private fun TrendingCard(track: SearchResult, width: Dp, onClick: () -> Unit) {
    Column(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(PineRadius.Md))
            .clickable(onClick = onClick),
    ) {
        Thumbnail(track.thumbUrl, track.title, size = width, corner = PineRadius.Md)
        Spacer(Modifier.height(PineSpace.Sm))
        Text(
            track.title,
            style = MaterialTheme.typography.titleSmall,
            color = PineTextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 17.sp,
        )
        if (track.channel.isNotEmpty()) {
            Spacer(Modifier.height(PineSpace.Xxs))
            Text(
                track.channel,
                style = MaterialTheme.typography.labelMedium,
                color = PineTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Skeletons matching [TrendingCard]'s exact footprint, so nothing jumps. */
@Composable
private fun TrendingSkeletonRow() {
    LazyRow(
        contentPadding = PaddingValues(horizontal = PineGutter),
        horizontalArrangement = Arrangement.spacedBy(PineSpace.Md),
    ) {
        items(6) {
            Column(Modifier.width(150.dp)) {
                SkeletonBlock(Modifier.size(150.dp), corner = PineRadius.Md)
                Spacer(Modifier.height(PineSpace.Sm))
                SkeletonBlock(Modifier.fillMaxWidth(0.78f).height(12.dp), corner = PineRadius.Sm)
                Spacer(Modifier.height(PineSpace.Xs))
                SkeletonBlock(Modifier.fillMaxWidth(0.5f).height(11.dp), corner = PineRadius.Sm)
            }
        }
    }
}

// --------------------------------------------------------- empty / error

@Composable
private fun EmptyRecents(onSearch: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PineGutter, vertical = PineSpace.Lg),
    ) {
        Text(
            "Nothing played yet",
            style = MaterialTheme.typography.titleMedium,
            color = PineTextPrimary,
        )
        Spacer(Modifier.height(PineSpace.Xs))
        Text(
            "Songs you play are kept here so you can get back to them.",
            style = MaterialTheme.typography.bodyMedium,
            color = PineTextSecondary,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(PineSpace.Md))
        TextButton(
            onClick = onSearch,
            contentPadding = PaddingValues(horizontal = 0.dp),
        ) {
            Text(
                "Find something to play",
                color = PineAccent,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun TrendingError(message: String, title: String, onRetry: () -> Unit) {
    StateMessage(
        title = title,
        body = message,
        actionLabel = "Retry",
        onAction = onRetry,
        glyph = { size, color -> RetryGlyph(size, color) },
        modifier = Modifier.padding(horizontal = PineSpace.Lg),
    )
}

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrenttunes.app.library.LibraryViewModel
import com.torrenttunes.app.search.SearchResult
import com.torrenttunes.app.ui.components.Thumbnail
import com.torrenttunes.app.ui.theme.ScDivider
import com.torrenttunes.app.ui.theme.ScOrange
import com.torrenttunes.app.ui.theme.ScSurface
import com.torrenttunes.app.ui.theme.ScText
import com.torrenttunes.app.ui.theme.ScTextDim

/**
 * Discovery: what is hot right now, plus a way back into what you played.
 *
 * Two sections because they answer two different questions — "what is new"
 * and "where was I". Trending comes from YouTube Music's chart; the rest is
 * local, so the tab still has something on it with no network at all.
 *
 * The chart is fetched once, on first arrival ([MainViewModel.ensureTrending]),
 * not in a ViewModel init: most sessions open on Search.
 */
@Composable
fun DiscoveryScreen(
    vm: MainViewModel,
    library: LibraryViewModel,
    onPlay: (SearchResult) -> Unit,
    onOpenSearch: () -> Unit,
    contentPadding: PaddingValues,
) {
    val trending by vm.trending.collectAsState()
    val loading by vm.trendingLoading.collectAsState()
    val error by vm.trendingError.collectAsState()
    val libraryState by library.state.collectAsState()

    // Runs once when the tab first composes.
    LaunchedEffect(Unit) { vm.ensureTrending() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            Text(
                "Discovery",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp,
                color = ScText,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp),
            )
        }

        // ------------------------------------------------------------- trending
        if (trending.isNotEmpty()) {
            item { SectionTitle("Trending now") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(trending.take(12), key = { it.videoId }) { track ->
                        TrendingCard(track) { onPlay(track) }
                    }
                }
            }
        } else if (loading) {
            item { SectionTitle("Trending now") }
            item {
                // Static blocks rather than a spinner: the shape of what is
                // coming is more informative than a rotating circle, and it
                // does not jump when the real rows land.
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(6) { TrendingCardSkeleton() }
                }
            }
        } else if (error != null) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Trending is unavailable",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ScText,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            error ?: "",
                            fontSize = 13.sp,
                            color = ScTextDim,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { vm.retryTrending() }) {
                        Text("Retry", color = ScOrange, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // -------------------------------------------------------------- recents
        val recent = libraryState.recent
        if (recent.isNotEmpty()) {
            item { SectionTitle("Jump back in") }
            items(recent.take(8), key = { "recent-${it.videoId}" }) { track ->
                RecentRow(track) { onPlay(track) }
                Box(
                    Modifier
                        .padding(start = 76.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(ScDivider),
                )
            }
        } else {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                ) {
                    Text(
                        "Nothing played yet",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ScText,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Songs you play show up here so you can get back to them.",
                        fontSize = 13.sp,
                        color = ScTextDim,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(
                        onClick = onOpenSearch,
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) {
                        Text("Find something to play", color = ScOrange, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ------------------------------------------------------------------ pieces

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 19.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp,
        color = ScText,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp),
    )
}

/** A trending entry as a card, the way Spotify lays its charts out. */
@Composable
private fun TrendingCard(track: SearchResult, onClick: () -> Unit) {
    Column(
        Modifier
            .width(148.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Thumbnail(track.thumbUrl, track.title, size = 148.dp, corner = 10.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            track.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = ScText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 18.sp,
        )
        if (track.channel.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                track.channel,
                fontSize = 13.sp,
                color = ScTextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Same footprint as [TrendingCard], so the row does not shift when data lands. */
@Composable
private fun TrendingCardSkeleton() {
    Column(Modifier.width(148.dp)) {
        Box(
            Modifier
                .size(148.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ScSurface),
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .width(118.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(ScSurface),
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .width(74.dp)
                .height(11.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(ScSurface),
        )
    }
}

/** A recently played track, matching the row rhythm of the other tabs. */
@Composable
private fun RecentRow(track: SearchResult, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(track.thumbUrl, track.title, size = 48.dp, corner = 6.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = ScText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (track.channel.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    track.channel,
                    fontSize = 13.sp,
                    color = ScTextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (track.durationSec > 0) {
            Spacer(Modifier.width(10.dp))
            Text(
                track.durationLabel,
                fontSize = 13.sp,
                color = ScTextDim,
            )
        }
    }
}

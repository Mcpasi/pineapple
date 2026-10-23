package com.torrenttunes.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torrenttunes.app.library.LibraryViewModel
import com.torrenttunes.app.search.SearchResult
import com.torrenttunes.app.ui.components.BackGlyph
import com.torrenttunes.app.ui.components.PineIconButton
import com.torrenttunes.app.ui.components.SearchGlyph
import com.torrenttunes.app.ui.components.SectionHeader
import com.torrenttunes.app.ui.components.StateMessage
import com.torrenttunes.app.ui.components.ThumbnailFill
import com.torrenttunes.app.ui.components.TrackActionsSheet
import com.torrenttunes.app.ui.components.TrackRow
import com.torrenttunes.app.ui.components.TrackRowSkeleton
import com.torrenttunes.app.ui.theme.PineGutter
import com.torrenttunes.app.ui.theme.PineSpace
import com.torrenttunes.app.ui.theme.PineTextPrimary
import com.torrenttunes.app.ui.theme.PineTextSecondary

/**
 * One artist's catalogue: header art, name, audience line, Top songs.
 *
 * Rendered in place of the tab content rather than as an overlay, so the nav
 * bar, mini-player and search field keep working around it — the page sits
 * *inside* the app instead of on top of it. System back closes the page before
 * it unwinds tabs, exactly the order the stack already used for the player.
 *
 * The header is drawn from [ArtistUiState.seed] first and only refined by the
 * fetched [com.torrenttunes.app.search.ArtistDetail]: the row the user tapped
 * already carries a name, an audience line and an avatar, so the page is
 * truthful while `/browse` is still in flight instead of showing a spinner
 * where a title should be.
 */
@Composable
fun ArtistScreen(
    vm: MainViewModel,
    library: LibraryViewModel,
    state: ArtistUiState,
    onClose: () -> Unit,
) {
    val vmState by vm.state.collectAsState()
    val currentTrack by vm.currentTrack.collectAsState()
    val libraryState by library.state.collectAsState()

    // Same contract as search results: tap plays, long-press or overflow
    // opens the actions sheet.
    var sheetTrack by remember { mutableStateOf<SearchResult?>(null) }

    val seed = state.seed
    val detail = state.detail
    val name = detail?.name?.takeIf { it.isNotEmpty() } ?: seed.title
    val audience = detail?.audienceLabel?.takeIf { it.isNotEmpty() } ?: buildString {
        if (seed.channel.isNotEmpty()) append(seed.channel)
        if (seed.viewsLabel.isNotEmpty()) {
            if (isNotEmpty()) append("  ·  ")
            append(seed.viewsLabel)
        }
    }
    val banner = detail?.bannerUrl?.takeIf { it.isNotEmpty() } ?: seed.thumbUrl

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = PineSpace.Xl),
        ) {
            item(key = "header") {
                Box(Modifier.fillMaxWidth().height(176.dp)) {
                    ThumbnailFill(
                        url = banner,
                        seed = name,
                        corner = 0.dp,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // The page's own way out, matching the player's close
                    // control. Scrim behind it because banner art is
                    // unpredictable and a chevron has to stay legible on it.
                    PineIconButton(
                        onClick = onClose,
                        modifier = Modifier.align(Alignment.TopStart).padding(PineSpace.Xs),
                        background = Color.Black.copy(alpha = 0.35f),
                        glyph = { BackGlyph(24.dp, PineTextPrimary) },
                    )
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PineGutter, vertical = PineSpace.Md),
                ) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleLarge,
                        color = PineTextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (audience.isNotEmpty()) {
                        Spacer(Modifier.height(PineSpace.Xs))
                        Text(
                            audience,
                            style = MaterialTheme.typography.bodyMedium,
                            color = PineTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            when {
                state.error != null && detail == null -> item(key = "error") {
                    StateMessage(
                        title = "Could not load this artist",
                        body = state.error,
                        actionLabel = "Try again",
                        onAction = { vm.retryArtist() },
                        glyph = { size, color -> SearchGlyph(size, color, 3.dp) },
                    )
                }

                // No detail yet: either the request is in flight or it has not
                // been answered. Row-shaped skeletons keep the header steady.
                detail == null -> items(4) { TrackRowSkeleton() }

                detail.topSongs.isEmpty() -> item(key = "no-songs") {
                    StateMessage(title = "No songs here yet")
                }

                else -> {
                    item(key = "top-songs") {
                        SectionHeader("Top songs")
                    }
                    items(detail.topSongs, key = { it.dedupeKey }) { song ->
                        TrackRow(
                            track = song,
                            onClick = { vm.playResult(song) },
                            isLoading = vmState.loadingVideoId == song.videoId,
                            isCurrent = currentTrack?.videoId == song.videoId,
                            onLongClick = { sheetTrack = song },
                            onOverflow = { sheetTrack = song },
                        )
                    }
                }
            }
        }
    }

    sheetTrack?.let { track ->
        TrackActionsSheet(
            track = track,
            library = libraryState,
            libraryVm = library,
            onPlay = { vm.playResult(track) },
            onAddToQueue = { vm.addToQueue(track) },
            onDismiss = { sheetTrack = null },
            // Nothing on this page belongs to a playlist, so offering
            // "Remove from this playlist" would be a lie.
            sourcePlaylistId = null,
        )
    }
}

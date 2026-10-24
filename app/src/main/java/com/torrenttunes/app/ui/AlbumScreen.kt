package com.torrenttunes.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.torrenttunes.app.ui.components.StateMessage
import com.torrenttunes.app.ui.components.ThumbnailFill
import com.torrenttunes.app.ui.components.TrackActionsSheet
import com.torrenttunes.app.ui.components.TrackRow
import com.torrenttunes.app.ui.components.TrackRowSkeleton
import com.torrenttunes.app.ui.theme.PineGutter
import com.torrenttunes.app.ui.theme.PineSpace
import com.torrenttunes.app.ui.theme.PineRadius
import com.torrenttunes.app.ui.theme.PineAccent
import com.torrenttunes.app.ui.theme.PineTextPrimary
import com.torrenttunes.app.ui.theme.PineTextSecondary

/**
 * One album: square cover art, title, an `artist · year` line, a
 * `kind · songs · duration` fact line, and the ordered track list.
 *
 * Mirrors [ArtistScreen] — rendered in place of the tab content so the nav bar,
 * mini-player and search field keep working around it, and system back closes
 * the album before unwinding tabs.
 *
 * The header is drawn from [AlbumUiState.seed] first and only refined by the
 * fetched [com.torrenttunes.app.search.AlbumDetail]: the tapped row already
 * carries a title, an artist and a cover, so the page is truthful while
 * `/browse` is in flight instead of showing a spinner where a title should be.
 *
 * Tapping the artist line opens that artist's page when the album header
 * linked one — the one piece of cross-catalogue navigation an album owes.
 */
@Composable
fun AlbumScreen(
    vm: MainViewModel,
    library: LibraryViewModel,
    state: AlbumUiState,
    onClose: () -> Unit,
    onOpenArtist: (SearchResult) -> Unit,
) {
    val vmState by vm.state.collectAsState()
    val currentTrack by vm.currentTrack.collectAsState()
    val libraryState by library.state.collectAsState()

    // Same contract as search results: tap plays, long-press or overflow
    // opens the actions sheet.
    var sheetTrack by remember { mutableStateOf<SearchResult?>(null) }

    val seed = state.seed
    val detail = state.detail
    val title = detail?.title?.takeIf { it.isNotEmpty() } ?: seed.title
    val cover = detail?.artUrl?.takeIf { it.isNotEmpty() } ?: seed.thumbUrl
    val artistName = detail?.artistName?.takeIf { it.isNotEmpty() } ?: seed.channel
    val year = detail?.yearLabel?.takeIf { it.isNotEmpty() } ?: seed.viewsLabel
    val artistId = detail?.artistBrowseId.orEmpty()

    val meta = buildString {
        if (artistName.isNotEmpty()) append(artistName)
        if (year.isNotEmpty()) {
            if (isNotEmpty()) append("  ·  ")
            append(year)
        }
    }
    // The fact line always leads with the kind, then whatever the header knew.
    val facts = buildString {
        append(detail?.kindLabel?.takeIf { it.isNotEmpty() } ?: "Album")
        detail?.trackCountLabel?.takeIf { it.isNotEmpty() }?.let {
            append("  ·  ")
            append(it)
        }
        detail?.durationLabel?.takeIf { it.isNotEmpty() }?.let {
            append("  ·  ")
            append(it)
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = PineSpace.Xl),
        ) {
            item(key = "header") {
                Box(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PineGutter, vertical = PineSpace.Md),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // A square cover, sized like a record sleeve rather than
                        // stretched the full width (which would be absurdly tall).
                        Box(Modifier.size(240.dp)) {
                            ThumbnailFill(
                                url = cover,
                                seed = title,
                                corner = PineRadius.Lg,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Spacer(Modifier.height(PineSpace.Md))
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            color = PineTextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (meta.isNotEmpty()) {
                            Spacer(Modifier.height(PineSpace.Xs))
                            // The artist line is a door when the header linked one.
                            if (artistId.isNotEmpty()) {
                                Text(
                                    meta,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PineAccent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onOpenArtist(
                                                SearchResult(
                                                    videoId = "",
                                                    title = artistName,
                                                    channel = "Artist",
                                                    durationLabel = "",
                                                    durationSec = 0,
                                                    viewsLabel = "",
                                                    views = -1,
                                                    // Fall back to the cover for an avatar.
                                                    thumbUrl = cover,
                                                    browseId = artistId,
                                                ),
                                            )
                                        },
                                )
                            } else {
                                Text(
                                    meta,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PineTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (facts.isNotEmpty()) {
                            Spacer(Modifier.height(PineSpace.Xxs))
                            Text(
                                facts,
                                style = MaterialTheme.typography.labelMedium,
                                color = PineTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // The page's own way out, matching the artist page's control.
                    // Scrim behind it because cover art is unpredictable and a
                    // chevron has to stay legible on it.
                    PineIconButton(
                        onClick = onClose,
                        modifier = Modifier.align(Alignment.TopStart).padding(PineSpace.Xs),
                        background = Color.Black.copy(alpha = 0.35f),
                        glyph = { BackGlyph(24.dp, PineTextPrimary) },
                    )
                }
            }

            when {
                state.error != null && detail == null -> item(key = "error") {
                    StateMessage(
                        title = "Could not load this album",
                        body = state.error,
                        actionLabel = "Try again",
                        onAction = { vm.retryAlbum() },
                        glyph = { size, color -> SearchGlyph(size, color, 3.dp) },
                    )
                }

                // No detail yet: either the request is in flight or it has not
                // been answered. Row-shaped skeletons keep the header steady.
                detail == null -> items(4) { TrackRowSkeleton() }

                detail.tracks.isEmpty() -> item(key = "no-tracks") {
                    StateMessage(title = "No tracks here yet")
                }

                else -> items(detail.tracks, key = { it.dedupeKey }) { track ->
                    TrackRow(
                        track = track,
                        onClick = { vm.playResult(track) },
                        isLoading = vmState.loadingVideoId == track.videoId,
                        isCurrent = currentTrack?.videoId == track.videoId,
                        onLongClick = { sheetTrack = track },
                        onOverflow = { sheetTrack = track },
                    )
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

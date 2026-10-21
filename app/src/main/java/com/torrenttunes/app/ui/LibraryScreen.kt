package com.torrenttunes.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrenttunes.app.library.LibraryViewModel
import com.torrenttunes.app.library.Playlist
import com.torrenttunes.app.search.SearchResult
import com.torrenttunes.app.ui.components.CloseGlyph
import com.torrenttunes.app.ui.components.MoreGlyph
import com.torrenttunes.app.ui.components.PineWindowWidth
import com.torrenttunes.app.ui.components.PlusGlyph
import com.torrenttunes.app.ui.components.SectionHeader
import com.torrenttunes.app.ui.components.StateMessage
import com.torrenttunes.app.ui.components.Thumbnail
import com.torrenttunes.app.ui.components.TrackActionsSheet
import com.torrenttunes.app.ui.components.TrackRow
import com.torrenttunes.app.ui.components.TrackRowSkeleton
import com.torrenttunes.app.ui.components.pineWindowWidth
import com.torrenttunes.app.ui.theme.PineAccent
import com.torrenttunes.app.ui.theme.PineDivider
import com.torrenttunes.app.ui.theme.PineGutter
import com.torrenttunes.app.ui.theme.PineRadius
import com.torrenttunes.app.ui.theme.PineSpace
import com.torrenttunes.app.ui.theme.PineSurface
import com.torrenttunes.app.ui.theme.PineSurfaceHigh
import com.torrenttunes.app.ui.theme.PineTextPrimary
import com.torrenttunes.app.ui.theme.PineTextSecondary

/**
 * Everything the user chose to keep: liked songs, what they played, and the
 * playlists they made.
 *
 * The screen is one scrolling list of sections rather than a set of cards
 * inside cards. A playlist opens a detail panel in place, so the back button
 * returns to the Library instead of leaving it — the same page stack the tabs
 * already use.
 */
@Composable
fun LibraryScreen(
    library: LibraryViewModel,
    onPlay: (SearchResult) -> Unit,
    onAddToQueue: (SearchResult) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val state by library.state.collectAsState()

    // Which track's sheet is open, and from which playlist (null = from the
    // Library directly, so "Remove from this playlist" is not offered).
    var sheetTrack by remember { mutableStateOf<SheetRequest?>(null) }
    var openPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    // Back returns from a playlist to the Library rather than to the last tab.
    androidx.activity.compose.BackHandler(enabled = openPlaylist != null) {
        openPlaylist = null
    }

    openPlaylist?.let { playlist ->
        PlaylistDetail(
            playlist = playlist,
            library = library,
            onPlay = { track ->
                onPlay(track)
                onOpenPlayer()
            },
            onBack = { openPlaylist = null },
            onTrackLongClick = { track -> sheetTrack = SheetRequest(track, playlist.id) },
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = PineSpace.Xxl),
    ) {
        item(key = "head") { LibraryHeader() }

        // ------------------------------------------------------------- pinned
        if (state.liked.isEmpty()) {
            item(key = "liked-empty") { EmptyLiked() }
        } else {
            item(key = "liked-head") { SectionHeader("Liked songs") }
            item(key = "liked-row") { LikedTile(state.liked.size, onClick = {
                openPlaylist = Playlist("liked", "Liked songs", state.liked)
            }) }
            items(state.liked.take(3), key = { "liked-${it.videoId}" }) { track ->
                TrackRow(
                    track = track,
                    onClick = { onPlay(track); onOpenPlayer() },
                    onLongClick = { sheetTrack = SheetRequest(track, null) },
                    onOverflow = { sheetTrack = SheetRequest(track, null) },
                )
            }
        }

        // ------------------------------------------------------------ recents
        if (state.recent.isNotEmpty()) {
            item(key = "recent-head") { SectionHeader("Recently played") }
            items(state.recent.take(5), key = { "recent-${it.videoId}" }) { track ->
                TrackRow(
                    track = track,
                    onClick = { onPlay(track); onOpenPlayer() },
                    onLongClick = { sheetTrack = SheetRequest(track, null) },
                    onOverflow = { sheetTrack = SheetRequest(track, null) },
                )
            }
        }

        // ---------------------------------------------------------- playlists
        item(key = "pl-head") {
            SectionHeader("Your playlists") {
                NewPlaylistButton { showCreate = true }
            }
        }
        if (state.playlists.isEmpty()) {
            item(key = "pl-empty") { EmptyPlaylists() }
        } else {
            items(state.playlists, key = { "pl-${it.id}" }) { pl ->
                PlaylistRow(
                    playlist = pl,
                    onClick = { openPlaylist = pl },
                    onOverflow = {
                        // An empty playlist has no track to act on, so the
                        // overflow does nothing rather than opening a sheet
                        // for a track that does not exist.
                        pl.tracks.firstOrNull()?.let { sheetTrack = SheetRequest(it, pl.id) }
                    },
                )
            }
        }
    }

    sheetTrack?.let { req ->
        TrackActionsSheet(
            track = req.track,
            library = state,
            libraryVm = library,
            onPlay = { onPlay(req.track); onOpenPlayer() },
            onAddToQueue = { onAddToQueue(req.track) },
            onDismiss = { sheetTrack = null },
            sourcePlaylistId = req.sourceId,
        )
    }

    if (showCreate) {
        CreatePlaylistDialog(
            onCreate = { name ->
                library.createPlaylist(name)
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }
}

/**
 * A pending actions-sheet request.
 *
 * Held as a named type rather than a `Pair` so `null` is unambiguous: Kotlin
 * infers `Pair<SearchResult, Nothing?>` from `track to null`, which then fails
 * to match a `Pair<SearchResult, String?>?` parameter.
 */
private data class SheetRequest(val track: SearchResult, val sourceId: String?)

// ---------------------------------------------------------------- header

@Composable
private fun LibraryHeader() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = PineGutter, end = PineGutter, top = PineSpace.Lg, bottom = PineSpace.Xs),
    ) {
        Text(
            "Your Library",
            style = MaterialTheme.typography.headlineMedium,
            color = PineTextPrimary,
        )
    }
}

// ------------------------------------------------------------- liked tile

/**
 * The Liked Songs tile.
 *
 * A gradient with a drawn heart rather than a fetched image — it is a
 * collection the user made, not an album, so synthesised art is the honest
 * answer and it always matches the app's accent.
 */
@Composable
private fun LikedTile(count: Int, onClick: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = PineGutter)
            .clip(RoundedCornerShape(PineRadius.Md))
            .clickable(onClick = onClick)
            .padding(vertical = PineSpace.Sm),
    ) {
        Box(
            Modifier
                .size(112.dp)
                .clip(RoundedCornerShape(PineRadius.Md))
                .background(Brush.linearGradient(listOf(PineAccent, Color(0xFFB04A00)))),
            contentAlignment = Alignment.Center,
        ) {
            // The same filled heart the player and the actions sheet use, so
            // "liked" is one shape across the whole app.
            com.torrenttunes.app.ui.components.HeartGlyph(
                glyphSize = 48.dp,
                color = Color.White,
                filled = true,
            )
        }
        Spacer(Modifier.height(PineSpace.Sm))
        Text(
            "Liked songs",
            style = MaterialTheme.typography.titleSmall,
            color = PineTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(PineSpace.Xxs))
        Text(
            "$count song${if (count == 1) "" else "s"}",
            style = MaterialTheme.typography.labelMedium,
            color = PineTextSecondary,
        )
    }
}

// ---------------------------------------------------------- playlist row

/** A playlist row: generated gradient art, name, and real track count. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(playlist: Playlist, onClick: () -> Unit, onOverflow: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onOverflow)
            .padding(horizontal = PineGutter, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistArtwork(playlist, size = 56.dp)
        Spacer(Modifier.width(PineSpace.Md))
        Column(Modifier.weight(1f)) {
            Text(
                playlist.name,
                style = MaterialTheme.typography.titleMedium,
                color = PineTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(PineSpace.Xxs))
            Text(
                "Playlist · ${playlist.tracks.size} song${if (playlist.tracks.size == 1) "" else "s"}",
                style = MaterialTheme.typography.labelMedium,
                color = PineTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onOverflow),
            contentAlignment = Alignment.Center,
        ) { MoreGlyph(20.dp, PineTextSecondary) }
    }
    RowDivider()
}

/**
 * Generated playlist art — a gradient from the name, with the first letter
 * centred. Fallback rather than a fetched image: a playlist has no cover.
 */
@Composable
private fun PlaylistArtwork(playlist: Playlist, size: Dp) {
    val palette = remember(playlist.name) {
        val paletteIndex = (playlist.name.hashCode() and Int.MAX_VALUE) % 10
        ArtworkGradients[paletteIndex]
    }
    val letterSize = with(LocalDensity.current) { (size * 0.36f).toSp() }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(PineRadius.Sm))
            .background(Brush.linearGradient(palette)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            playlist.name.firstOrNull()?.uppercaseChar()?.toString() ?: "#",
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = letterSize,
        )
    }
}

private val ArtworkGradients = listOf(
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

// --------------------------------------------------------- playlist detail

/**
 * An opened playlist.
 *
 * A playlist you cannot look inside is not much of a playlist, so this view
 * lists the real tracks and lets each one be played, queued or filed — all
 * driven by existing state, with nothing fabricated.
 */
@Composable
private fun PlaylistDetail(
    playlist: Playlist,
    library: LibraryViewModel,
    onPlay: (SearchResult) -> Unit,
    onBack: () -> Unit,
    onTrackLongClick: (SearchResult) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PineSpace.Md, vertical = PineSpace.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                com.torrenttunes.app.ui.components.BackGlyph(22.dp, PineTextPrimary)
            }
            Spacer(Modifier.width(PineSpace.Sm))
            Text(
                playlist.name,
                style = MaterialTheme.typography.titleMedium,
                color = PineTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (playlist.tracks.isEmpty()) {
            StateMessage(
                title = "This playlist is empty",
                body = "Long-press any song in Search or your Library and choose " +
                    "\"Add to playlist\".",
                glyph = { size, color -> PlusGlyph(size, color) },
            )
            return
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = PineSpace.Xxl),
        ) {
            item(key = "art") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(PineGutter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlaylistArtwork(playlist, size = 96.dp)
                    Spacer(Modifier.width(PineSpace.Lg))
                    Column {
                        Text(
                            playlist.name,
                            style = MaterialTheme.typography.headlineSmall,
                            color = PineTextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(PineSpace.Xs))
                        Text(
                            "${playlist.tracks.size} song${if (playlist.tracks.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = PineTextSecondary,
                        )
                    }
                }
            }
            items(playlist.tracks, key = { "pd-${playlist.id}-${it.videoId}" }) { track ->
                TrackRow(
                    track = track,
                    onClick = { onPlay(track) },
                    onLongClick = { onTrackLongClick(track) },
                    onOverflow = { onTrackLongClick(track) },
                )
            }
        }
    }
}

// -------------------------------------------------------------- buttons

@Composable
private fun NewPlaylistButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { PlusGlyph(20.dp, PineAccent) }
}

/**
 * Names a new playlist.
 *
 * A dialog rather than a sheet: the user is naming one thing, not picking
 * from a list, and a single focused field with a keyboard is the shortest
 * path to a name. An empty name cannot be submitted — `createPlaylist`
 * refuses it, and enabling the button anyway would just look broken.
 */
@Composable
private fun CreatePlaylistDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    // Empty rather than "New playlist": pre-filling forces a select-all before
    // the user can type a single character of their own.
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PineSurface,
        title = { Text("New playlist", color = PineTextPrimary, style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Playlist name", color = PineTextSecondary) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = PineTextPrimary,
                    unfocusedTextColor = PineTextPrimary,
                    focusedBorderColor = PineAccent,
                    unfocusedBorderColor = PineDivider,
                    cursorColor = PineAccent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name.trim()) },
            ) {
                Text("Create", color = if (name.isNotBlank()) PineAccent else PineTextSecondary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = PineTextSecondary) }
        },
    )
}

// ---------------------------------------------------------- empty states

@Composable
private fun EmptyLiked() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PineGutter, vertical = PineSpace.Lg),
    ) {
        Text("No liked songs yet", style = MaterialTheme.typography.titleMedium, color = PineTextPrimary)
        Spacer(Modifier.height(PineSpace.Xs))
        Text(
            "Tap the heart on the full-screen player, or long-press a song and " +
                "choose \"Add to Liked\".",
            style = MaterialTheme.typography.bodyMedium,
            color = PineTextSecondary,
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun EmptyPlaylists() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PineGutter, vertical = PineSpace.Md),
    ) {
        Text("No playlists yet", style = MaterialTheme.typography.titleMedium, color = PineTextPrimary)
        Spacer(Modifier.height(PineSpace.Xs))
        Text(
            "Group songs you always want together. Nothing is downloaded — a " +
                "playlist is a list of what to play.",
            style = MaterialTheme.typography.bodyMedium,
            color = PineTextSecondary,
            lineHeight = 20.sp,
        )
    }
}

// ------------------------------------------------------------- divider

/** A divider indented under the row's text rather than its artwork. */
@Composable
private fun RowDivider(startIndent: Dp = 84.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(1.dp)
            .background(PineDivider),
    )
}

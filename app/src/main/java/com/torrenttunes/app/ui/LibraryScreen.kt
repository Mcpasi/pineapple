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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrenttunes.app.library.LibraryState
import com.torrenttunes.app.library.LibraryViewModel
import com.torrenttunes.app.library.Playlist
import com.torrenttunes.app.search.SearchResult
import com.torrenttunes.app.ui.components.Thumbnail
import com.torrenttunes.app.ui.theme.ScCard
import com.torrenttunes.app.ui.theme.ScSurface
import com.torrenttunes.app.ui.theme.ScText
import com.torrenttunes.app.ui.theme.ScOrange
import com.torrenttunes.app.ui.theme.ScTextDim

/**
 * What the user kept.
 *
 * Read top to bottom as a story: what they just played, what they pinned, then
 * the collections they built deliberately. Nothing here is downloaded to disk
 * by the app — a playlist is a list of references, not a folder of files.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    library: LibraryViewModel,
    onPlay: (SearchResult) -> Unit,
    onAddToQueue: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by library.state.collectAsState()

    /** Track whose long-press menu is open, or null. */
    var sheetTrack by remember { mutableStateOf<SearchResult?>(null) }
    /** Playlist being edited through the sheet, when the sheet was opened from one. */
    var sourcePlaylistId by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp),
    ) {
        item(key = "head") {
            Text(
                "Your Library",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = ScText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // ------------------------------------------------------------ recent
        if (state.recent.isNotEmpty()) {
            item(key = "recent-head") { SectionHeader("Recently played") { } }
            item(key = "recent-row") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(state.recent, key = { "r_${it.dedupeKey}" }) { t ->
                        Column(
                            Modifier
                                .width(96.dp)
                                .combinedClickable(
                                    onClick = { onPlay(t) },
                                    onLongClick = {
                                        sourcePlaylistId = null
                                        sheetTrack = t
                                    },
                                ),
                        ) {
                            Thumbnail(t.thumbUrl, t.title, size = 96.dp, corner = 6.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                t.title,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = ScText,
                            )
                        }
                    }
                }
            }
        }

        // ----------------------------------------------------------- pinned
        item(key = "pinned-head") { SectionHeader("Pinned") { } }
        item(key = "pinned-liked") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clickable {
                        state.liked.firstOrNull()?.let(onPlay)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LikedTile(size = 72.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Liked Songs",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = ScText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (state.liked.isEmpty()) "Tap a heart to save songs"
                        else "${state.liked.size} song${if (state.liked.size == 1) "" else "s"}",
                        fontSize = 13.sp,
                        color = ScTextDim,
                    )
                }
                PinGlyph(Modifier.size(18.dp))
            }

            if (state.liked.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    items(state.liked, key = { "l_${it.dedupeKey}" }) { t ->
                        Column(
                            Modifier
                                .width(96.dp)
                                .combinedClickable(
                                    onClick = { onPlay(t) },
                                    onLongClick = {
                                        sourcePlaylistId = null
                                        sheetTrack = t
                                    },
                                ),
                        ) {
                            Thumbnail(t.thumbUrl, t.title, size = 96.dp, corner = 6.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                t.title,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = ScText,
                            )
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------- playlists
        item(key = "pl-head") { SectionHeader("Playlists") { } }

        if (state.playlists.isEmpty()) {
            item(key = "pl-empty") {
                Text(
                    "No playlists yet — long-press any song to create one.",
                    fontSize = 13.sp,
                    color = ScTextDim,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        } else {
            items(state.playlists, key = { it.id }) { pl ->
                PlaylistRow(
                    playlist = pl,
                    onClick = { pl.tracks.firstOrNull()?.let(onPlay) },
                    onLongClick = {
                        pl.tracks.firstOrNull()?.let {
                            sourcePlaylistId = pl.id
                            sheetTrack = it
                        }
                    },
                )
            }
        }

        item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
    }

    sheetTrack?.let { track ->
        TrackActionsSheet(
            track = track,
            library = state,
            sourcePlaylistId = sourcePlaylistId,
            libraryVm = library,
            onPlay = { onPlay(track) },
            onAddToQueue = { onAddToQueue(track) },
            onDismiss = { sheetTrack = null },
        )
    }
}

// ------------------------------------------------------------------ pieces

@Composable
private fun SectionHeader(title: String, trailing: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = ScText,
            letterSpacing = 0.2.sp,
        )
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** The pinned "Liked Songs" tile: gradient with a heart, Spotify-style. */
@Composable
private fun LikedTile(size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(7.dp))
            .background(
                Brush.linearGradient(
                    listOf(ScOrange, Color(0xFFFF8A3D)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.42f)) {
            val w = this.size.width
            val h = this.size.height
            val k = 0.08f
            val r = w * 0.30f
            // Two lobes plus a V — a heart read at a glance.
            drawCircle(Color.White, r, center = androidx.compose.ui.geometry.Offset(w * (0.5f - k), h * 0.36f))
            drawCircle(Color.White, r, center = androidx.compose.ui.geometry.Offset(w * (0.5f + k), h * 0.36f))
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * (0.5f - r), h * 0.40f)
                lineTo(w * 0.5f, h * 0.86f)
                lineTo(w * (0.5f + r), h * 0.40f)
                close()
            }
            drawPath(path, Color.White)
        }
    }
}

@Composable
private fun PinGlyph(modifier: Modifier) = Canvas(modifier) {
    val s = size.minDimension
    val stroke = s * 0.14f
    // Simple pin: head + stem.
    drawCircle(ScTextDim, radius = s * 0.20f, center = androidx.compose.ui.geometry.Offset(s * 0.5f, s * 0.28f))
    drawLine(
        ScTextDim,
        start = androidx.compose.ui.geometry.Offset(s * 0.5f, s * 0.46f),
        end = androidx.compose.ui.geometry.Offset(s * 0.5f, s * 0.90f),
        strokeWidth = stroke,
        cap = androidx.compose.ui.graphics.StrokeCap.Round,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(playlist: Playlist, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // No artwork of its own — a deterministic gradient keyed to the name.
        val hue = (playlist.name.hashCode() % 360 + 360) % 360
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color.hsv(hue.toFloat(), 0.55f, 0.95f),
                            Color.hsv((hue + 40f) % 360f, 0.65f, 0.85f),
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                playlist.name.take(1).uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 20.sp,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                playlist.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = ScText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Playlist · ${playlist.tracks.size} song${if (playlist.tracks.size == 1) "" else "s"}",
                fontSize = 13.sp,
                color = ScTextDim,
            )
        }
    }
}

// -------------------------------------------------------------------- sheet

/**
 * The long-press menu. Explicitly additive: every action here only mutates the
 * library's lists — nothing gets written to disk as a media file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackActionsSheet(
    track: SearchResult,
    library: LibraryState,
    sourcePlaylistId: String?,
    libraryVm: LibraryViewModel,
    onPlay: () -> Unit,
    onAddToQueue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val liked = libraryVm.isLiked(track.videoId)
    var showNewPlaylist by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var showPlaylistPicker by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Not white: the sheet's text is ScText, which is near-white on the
        // dark ground and would vanish against a white sheet.
        containerColor = ScCard,
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumbnail(track.thumbUrl, track.title, size = 46.dp, corner = 5.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = ScText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track.channel,
                        fontSize = 13.sp,
                        color = ScTextDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            HorizontalDivider(color = ScSurface)

            SheetAction(if (liked) "Remove from Liked" else "Add to Liked") {
                libraryVm.toggleLike(track)
                onDismiss()
            }
            SheetAction("Add to queue") {
                onAddToQueue()
                onDismiss()
            }
            SheetAction("Play next") {
                onPlay()
                onDismiss()
            }

            if (sourcePlaylistId != null) {
                SheetAction("Remove from this playlist") {
                    libraryVm.removeFromPlaylist(sourcePlaylistId, track.videoId)
                    onDismiss()
                }
            }

            HorizontalDivider(color = ScSurface)

            if (!showPlaylistPicker && !showNewPlaylist) {
                SheetAction("Add to playlist") { showPlaylistPicker = true }
                SheetAction("Create new playlist") { showNewPlaylist = true }
            }

            if (showPlaylistPicker) {
                Text(
                    "Add to playlist",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = ScTextDim,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                if (library.playlists.isEmpty()) {
                    Text(
                        "No playlists yet.",
                        fontSize = 13.sp,
                        color = ScTextDim,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                library.playlists.forEach { pl ->
                    SheetAction("${pl.name}  ·  ${pl.tracks.size}") {
                        libraryVm.addToPlaylist(pl.id, track)
                        onDismiss()
                    }
                }
                SheetAction("New playlist…") {
                    showPlaylistPicker = false
                    showNewPlaylist = true
                }
            }

            if (showNewPlaylist) {
                Text(
                    "New playlist",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = ScTextDim,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("Playlist name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(12.dp))
                val canSave = newName.isNotBlank()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .height(46.dp)
                        .clip(RoundedCornerShape(23.dp))
                        .background(if (canSave) ScOrange else ScSurface)
                        .clickable(enabled = canSave) {
                            libraryVm.createPlaylist(newName)?.let { id ->
                                libraryVm.addToPlaylist(id, track)
                            }
                            onDismiss()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Create and add",
                        color = if (canSave) Color.White else ScTextDim,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = ScText,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 15.dp),
    )
}

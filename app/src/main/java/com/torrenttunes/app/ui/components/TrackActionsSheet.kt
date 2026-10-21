package com.torrenttunes.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.torrenttunes.app.R
import com.torrenttunes.app.library.LibraryState
import com.torrenttunes.app.library.LibraryViewModel
import com.torrenttunes.app.search.SearchResult
import com.torrenttunes.app.ui.theme.PineAccent
import com.torrenttunes.app.ui.theme.PineDivider
import com.torrenttunes.app.ui.theme.PineRadius
import com.torrenttunes.app.ui.theme.PineSpace
import com.torrenttunes.app.ui.theme.PineSurfaceHigh
import com.torrenttunes.app.ui.theme.PineTextPrimary
import com.torrenttunes.app.ui.theme.PineTextSecondary

/**
 * The long-press / overflow menu.
 *
 * Shared by Search and the Library so both present exactly the same actions in
 * the same order — the previous design had this sheet private to the Library,
 * which meant a search result had no way to be liked or filed at all.
 *
 * Explicitly additive: every action here only mutates the library's lists.
 * Nothing is written to disk as a media file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionsSheet(
    track: SearchResult,
    library: LibraryState,
    libraryVm: LibraryViewModel,
    onPlay: () -> Unit,
    onAddToQueue: () -> Unit,
    onDismiss: () -> Unit,
    sourcePlaylistId: String? = null,
) {
    // From the passed-in state rather than libraryVm.isLiked(), which reads the
    // store's raw .value. The playlists below already come from `library`, so the
    // liked flag should share its subscription.
    val liked = library.liked.any { it.videoId == track.videoId }
    var mode by remember { mutableStateOf(SheetMode.Actions) }
    var newName by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Explicitly dark: a Material sheet defaults to a light container, and
        // this content is near-white type.
        containerColor = PineSurfaceHigh,
        dragHandle = { SheetHandle() },
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = PineSpace.Lg)) {
            // ---------------------------------------------------------- header
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PineSpace.Xl, vertical = PineSpace.Md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumbnail(track.thumbUrl, track.title, size = 52.dp, corner = PineRadius.Sm)
                Spacer(Modifier.width(PineSpace.Md))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = PineTextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
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

            Spacer(Modifier.height(PineSpace.Xs))
            HorizontalDivider(color = PineDivider)

            when (mode) {
                SheetMode.Actions -> {
                    SheetAction(
                        label = if (liked) "Remove from Liked" else "Add to Liked",
                        glyph = { s, c -> HeartGlyph(s, c, filled = liked) },
                    ) {
                        libraryVm.toggleLike(track)
                        onDismiss()
                    }
                    SheetAction("Play next", glyph = { s, c -> PlayGlyph(s, c) }) {
                        onPlay()
                        onDismiss()
                    }
                    SheetAction("Add to queue", glyph = { s, c -> QueueGlyph(s, c) }) {
                        onAddToQueue()
                        onDismiss()
                    }
                    SheetAction("Add to playlist", glyph = { s, c -> PlusGlyph(s, c) }) {
                        mode = SheetMode.Pick
                    }

                    if (sourcePlaylistId != null) {
                        Spacer(Modifier.height(PineSpace.Xs))
                        HorizontalDivider(color = PineDivider)
                        SheetAction(
                            "Remove from this playlist",
                            glyph = { s, c -> CloseGlyph(s, c) },
                            tint = PineErrorTint,
                        ) {
                            libraryVm.removeFromPlaylist(sourcePlaylistId, track.videoId)
                            onDismiss()
                        }
                    }
                }

                SheetMode.Pick -> {
                    Text(
                        "Add to playlist",
                        style = MaterialTheme.typography.labelLarge,
                        color = PineTextSecondary,
                        modifier = Modifier.padding(horizontal = PineSpace.Xl, vertical = PineSpace.Md),
                    )
                    if (library.playlists.isEmpty()) {
                        Text(
                            "No playlists yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PineTextSecondary,
                            modifier = Modifier.padding(horizontal = PineSpace.Xl),
                        )
                    }
                    library.playlists.forEach { pl ->
                        SheetAction(
                            label = pl.name,
                            subtitle = "${pl.tracks.size} song${if (pl.tracks.size == 1) "" else "s"}",
                            glyph = { s, c -> PlaylistGlyph(s, c) },
                        ) {
                            libraryVm.addToPlaylist(pl.id, track)
                            onDismiss()
                        }
                    }
                    SheetAction("New playlist…", glyph = { s, c -> PlusGlyph(s, c) }) {
                        mode = SheetMode.Create
                    }
                }

                SheetMode.Create -> {
                    Text(
                        "New playlist",
                        style = MaterialTheme.typography.labelLarge,
                        color = PineTextSecondary,
                        modifier = Modifier.padding(horizontal = PineSpace.Xl, vertical = PineSpace.Md),
                    )
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = {
                            Text("Playlist name", color = PineTextSecondary)
                        },
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PineSpace.Xl),
                    )
                    Spacer(Modifier.height(PineSpace.Lg))
                    val canSave = newName.isNotBlank()
                    PillButton(
                        label = "Create and add",
                        onClick = {
                            libraryVm.createPlaylist(newName)?.let { id ->
                                libraryVm.addToPlaylist(id, track)
                            }
                            onDismiss()
                        },
                        enabled = canSave,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PineSpace.Xl),
                    )
                }
            }
        }
    }
}

private enum class SheetMode { Actions, Pick, Create }

/** A subtle drag affordance at the top of the sheet. */
@Composable
private fun SheetHandle() {
    Box(
        Modifier
            .padding(vertical = PineSpace.Md)
            .size(width = 36.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(PineDivider),
    )
}

private val PineErrorTint = Color(0xFFFF6B61)

/** One row in the sheet: glyph, label, optional sub-label. */
@Composable
private fun SheetAction(
    label: String,
    // `@Composable` because every glyph here is drawn by a composable Canvas —
    // a plain function type cannot invoke one.
    glyph: (@Composable (Dp, Color) -> Unit)? = null,
    subtitle: String? = null,
    tint: Color = PineTextPrimary,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PineSpace.Xl, vertical = PineSpace.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                glyph(21.dp, if (tint == PineTextPrimary) PineTextSecondary else tint)
            }
            Spacer(Modifier.width(PineSpace.Lg))
        }
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(PineSpace.Xxs))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = PineTextSecondary,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- glyphs
//
// Both delegate to the shared drawable-backed glyphs in PineappleUi.kt, so
// the sheet's heart is the *same* silhouette the player and the Liked tile
// use rather than a third approximation of one. No local wrappers: a private
// `HeartGlyph` next to the imported one is an overload ambiguity, not an
// alias.

/** A playlist mark. */
@Composable
private fun PlaylistGlyph(size: Dp, color: Color) = PineIcon(R.drawable.ic_playlist_add, size, color)

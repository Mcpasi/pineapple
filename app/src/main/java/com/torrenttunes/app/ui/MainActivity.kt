package com.torrenttunes.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrenttunes.app.search.SearchResult
import com.torrenttunes.app.ui.components.*
import com.torrenttunes.app.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TorrentTunesTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TorrentTunesScreen(vm)
                }
            }
        }
    }
}

// --------------------------------------------------------------------- screen

@Composable
fun TorrentTunesScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val crash by vm.lastCrash.collectAsState()

    Scaffold(
        topBar = { SoundCloudTopBar(vm) },
        bottomBar = { PlayerBar(vm) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp)) {

            crash?.let { trace ->
                Spacer(Modifier.height(8.dp))
                CrashCard(trace = trace, onDismiss = vm::dismissCrash)
            }

            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(10.dp))
            StatusLine(state)

            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {

                if (state.results.isEmpty() && !state.searching && state.error == null) {
                    item(key = "empty") { EmptyState() }
                }

                items(state.results, key = { it.dedupeKey }) { result ->
                    ResultRow(
                        result = result,
                        isLoading = state.loadingVideoId == result.videoId,
                        onClick = { vm.playResult(result) },
                    )
                }

                item(key = "bottom-spacer") { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

// ------------------------------------------------------------------ top bar

/**
 * SoundCloud's header: the wordmark in brand orange, a filled grey pill for
 * search, and a round orange submit button. Pinned above the list so results
 * scroll underneath it, the way the real app behaves.
 */
@Composable
private fun SoundCloudTopBar(vm: MainViewModel) {
    val state by vm.state.collectAsState()

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Pineapple",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = (-0.5).sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "STREAMS INSTANTLY",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = state.query,
                    onValueChange = vm::onQueryChange,
                    modifier = Modifier.weight(1f).height(50.dp),
                    placeholder = { Text("Search songs, artists, albums") },
                    singleLine = true,
                    shape = RoundedCornerShape(25.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = ScFill,
                        unfocusedContainerColor = ScFill,
                        disabledContainerColor = ScFill,
                        errorContainerColor = ScFill,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                )

                Spacer(Modifier.width(10.dp))

                val canSearch = !state.searching && state.query.isNotBlank()
                Box(
                    Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(if (canSearch) MaterialTheme.colorScheme.primary else ScFill)
                        .clickable(enabled = canSearch) { vm.search() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.searching) {
                        CircularProgressIndicator(
                            Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = if (canSearch) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    } else {
                        SearchGlyph(
                            24.dp,
                            if (canSearch) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------- status line

@Composable
private fun StatusLine(state: UiState) {
    when {
        state.searching -> {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Searching…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.status != null -> {
            Text(
                buildString {
                    append(state.status)
                    if (state.queueSize > 1) append(" · ${state.queueSize} in queue")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ----------------------------------------------------------------- result row

@Composable
private fun ResultRow(
    result: SearchResult,
    isLoading: Boolean,
    onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !isLoading, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Thumbnail(result.thumbUrl, result.title, size = 52.dp, corner = 6.dp)
                if (isLoading) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (result.isTopPick) {
                        Text(
                            "BEST",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.6.sp,
                            color = Color.White,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(3.dp),
                                )
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        result.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(3.dp))

                Text(
                    buildString {
                        if (result.channel.isNotEmpty()) append(result.channel)
                        if (result.durationLabel.isNotEmpty()) {
                            if (isNotEmpty()) append(" · ")
                            append(result.durationLabel)
                        }
                        if (result.viewsLabel.isNotEmpty()) {
                            if (isNotEmpty()) append(" · ")
                            append(result.viewsLabel)
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(10.dp))

            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    PlayGlyph(18.dp, Color.White)
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 80.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

// ---------------------------------------------------------------- empty state

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            SearchGlyph(36.dp, MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Search to start listening",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Type a song, artist or album. Tap a result and it plays — " +
                "then a continuous mix builds up behind it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

// ----------------------------------------------------------------- crash card

/** Shows the last uncaught exception so the trace can be read off the screen. */
@Composable
private fun CrashCard(trace: String, onDismiss: () -> Unit) {
    val lines = trace.lines().take(24).joinToString("\n")
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "⚠ Last crash",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                lines,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 24,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ----------------------------------------------------------------- player bar

/**
 * SoundCloud's persistent mini-player: a full-width waveform you can tap to
 * scrub, then real album art, title, artist, and transport controls.
 */
@Composable
fun PlayerBar(vm: MainViewModel) {
    val isPlaying by vm.isPlaying.collectAsState()
    val nowPlaying by vm.nowPlaying.collectAsState()
    val artist by vm.artist.collectAsState()
    val artworkUrl by vm.artworkUrl.collectAsState()
    val positionMs by vm.positionMs.collectAsState()
    val durationMs by vm.durationMs.collectAsState()
    val state by vm.state.collectAsState()

    // Optimistic view: while a tap is still fetching its stream, the bar shows
    // the track the user chose rather than the one that happens to be loaded.
    val pending = state.pending
    val starting = nowPlaying.isEmpty() && pending != null
    val shownTitle = nowPlaying.ifEmpty { pending?.title.orEmpty() }
    val shownArtwork = artworkUrl.ifEmpty { pending?.thumbUrl.orEmpty() }

    val progress = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 12.dp) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Waveform(
                seed = nowPlaying.ifEmpty { "pineapple-idle" },
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .padding(horizontal = 14.dp, vertical = 7.dp)
                    .pointerInput(durationMs) {
                        detectTapGestures { offset ->
                            val total = durationMs
                            if (total > 0 && size.width > 0) {
                                val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                                vm.playerController.seekTo((fraction * total).toLong())
                            }
                        }
                    },
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumbnail(shownArtwork, shownTitle.ifEmpty { "Pineapple" }, size = 46.dp, corner = 5.dp)
                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        shownTitle.ifEmpty { "Nothing playing" },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        when {
                            starting -> "Starting…"
                            nowPlaying.isEmpty() -> "Search for a song to start"
                            else -> artist.ifEmpty {
                                if (state.queueSize > 1) {
                                    "${state.queueSize} tracks queued"
                                } else {
                                    "Streaming"
                                }
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (nowPlaying.isNotEmpty()) {
                    Text(
                        "${fmtMs(positionMs)} / ${fmtMs(durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.width(10.dp))

                TransportButton(
                    enabled = state.queueSize > 1,
                    background = MaterialTheme.colorScheme.secondaryContainer,
                    glyph = { PreviousGlyph(16.dp, MaterialTheme.colorScheme.onSurface) },
                    onClick = { vm.playerController.previous() },
                )

                Spacer(Modifier.width(8.dp))

                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { vm.playerController.togglePlayPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        starting -> CircularProgressIndicator(
                            Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                        isPlaying -> PauseGlyph(20.dp, Color.White)
                        else -> PlayGlyph(20.dp, Color.White)
                    }
                }

                Spacer(Modifier.width(8.dp))

                TransportButton(
                    enabled = state.queueSize > 1,
                    background = MaterialTheme.colorScheme.secondaryContainer,
                    glyph = { NextGlyph(16.dp, MaterialTheme.colorScheme.onSurface) },
                    onClick = { vm.next() },
                )
            }
        }
    }
}

@Composable
private fun TransportButton(
    enabled: Boolean,
    background: Color,
    glyph: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (enabled) background else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { glyph() }
}

// -------------------------------------------------------------------- helpers

internal fun fmtMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    return "%d:%02d".format(Locale.US, total / 60, total % 60)
}

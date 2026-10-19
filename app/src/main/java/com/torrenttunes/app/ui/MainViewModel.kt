package com.torrenttunes.app.ui

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.torrenttunes.app.player.PlayerController
import com.torrenttunes.app.search.SearchResult
import com.torrenttunes.app.util.CrashLogger
import com.torrenttunes.app.youtube.InnerTube
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

data class UiState(
    val query: String = "",
    val searching: Boolean = false,
    val results: List<SearchResult> = emptyList(),
    /** videoId whose stream URL is being resolved right now. */
    val loadingVideoId: String? = null,
    /**
     * Track the user just tapped, shown in the player bar immediately while its
     * stream is still being fetched — so the tap *feels* answered before audio
     * can possibly start.
     */
    val pending: SearchResult? = null,
    val error: String? = null,
    /** Short human status, e.g. "19 results · 0.4s". */
    val status: String? = null,
    val queueSize: Int = 0,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val androidContext: android.content.Context,
    val playerController: PlayerController,
    private val scope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _nowPlaying = MutableStateFlow("")
    val nowPlaying: StateFlow<String> = _nowPlaying.asStateFlow()

    private val _artist = MutableStateFlow("")
    val artist: StateFlow<String> = _artist.asStateFlow()

    /** Real album art from YouTube; empty when nothing is loaded. */
    private val _artworkUrl = MutableStateFlow("")
    val artworkUrl: StateFlow<String> = _artworkUrl.asStateFlow()

    /** Playhead position in ms — drives the mini-player waveform. */
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    /** Track duration in ms, or 0 while it is still unknown. */
    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    /** Stack trace from a previous process, surfaced so it can be read off-screen. */
    private val _lastCrash = MutableStateFlow(CrashLogger.readPersisted(androidContext))
    val lastCrash: StateFlow<String?> = _lastCrash.asStateFlow()

    private var searchJob: Job? = null
    private var playJob: Job? = null
    private var prefetchJob: Job? = null

    /**
     * videoId -> already-resolved stream URL.
     *
     * This is what makes a tap start instantly. Resolving costs one round trip
     * to YouTube (~0.5s on mobile), so we do it for the top results the moment
     * a search returns, in the background. By the time the user has read the
     * list and tapped, the URL is waiting and the tap costs nothing.
     *
     * ConcurrentHashMap because the prefetcher writes from Dispatchers.IO while
     * playResult reads from its own IO coroutine.
     */
    private val streamCache = ConcurrentHashMap<String, InnerTube.Resolved>()

    fun dismissCrash() {
        CrashLogger.clearPersisted(androidContext)
        _lastCrash.value = null
    }

    init {
        // Lightweight transport poll. Compose can't observe Player callbacks
        // directly without a MediaController, so we sample a few times a second.
        viewModelScope.launch {
            while (true) {
                try {
                    val p = playerController.player
                    val item = p.currentMediaItem
                    val title = item?.mediaMetadata?.title?.toString().orEmpty()
                    _isPlaying.value = p.isPlaying
                    _nowPlaying.value = title
                    _artist.value = item?.mediaMetadata?.artist?.toString().orEmpty()
                    _artworkUrl.value = item?.mediaMetadata?.artworkUri?.toString().orEmpty()
                    // The optimistic placeholder stands in until the player has
                    // genuinely taken over the very track we optimistically
                    // showed — not merely *some* track, which could be the
                    // previous one still loaded.
                    if (title.isNotEmpty() && _state.value.pending?.title == title) {
                        _state.update { it.copy(pending = null) }
                    }
                    _positionMs.value = p.currentPosition.coerceAtLeast(0L)
                    // ExoPlayer reports C.TIME_UNSET (a huge negative) when the
                    // duration is not known yet; clamp that to 0 so the waveform
                    // never divides by a negative length.
                    _durationMs.value = p.duration.coerceAtLeast(0L)
                    _state.update { it.copy(queueSize = p.mediaItemCount) }
                } catch (t: Throwable) {
                    _isPlaying.value = false
                }
                // 4x a second keeps the waveform moving without feeling like a
                // separate thread of work.
                delay(250)
            }
        }
    }

    // ------------------------------------------------------------- search

    fun onQueryChange(value: String) = _state.update { it.copy(query = value) }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(searching = true, error = null, results = emptyList(), status = null) }
            val startedAt = System.currentTimeMillis()
            try {
                val found = InnerTube.search(query)
                // YouTube already returns relevance order, which for music
                // queries surfaces the real recording first — so we only flag
                // the head as the best match rather than re-sorting by views
                // (which would promote a louder cover over the actual track).
                val ranked = found.mapIndexed { i, r ->
                    if (i == 0) r.copy(isTopPick = true) else r
                }
                val ms = System.currentTimeMillis() - startedAt
                Log.i(
                    "Search",
                    "query='$query' results=${ranked.size} in ${ms}ms " +
                        "first=${ranked.firstOrNull()?.title}",
                )
                // Resolve the head of the list right now, while the user is
                // still reading it. This is the whole trick behind instant
                // taps: the round trip happens *before* the tap, not after.
                startPrefetch(ranked)
                _state.update {
                    it.copy(
                        searching = false,
                        results = ranked,
                        status = "${ranked.size} results · ${"%.2f".format(ms / 1000.0)}s",
                    )
                }
            } catch (t: Throwable) {
                Log.e("Search", "search failed", t)
                _state.update { it.copy(searching = false, error = t.message ?: "Search failed") }
            }
        }
    }

    // --------------------------------------------------------------- play

    /**
     * Warm [streamCache] for the results a user is most likely to tap.
     *
     * Capped and cancellable: a new search abandons the old one, and resolving
     * stops at the first gap so a single unplayable video doesn't stall the
     * rest. Failures are fine — a miss just falls back to resolving on tap.
     */
    private fun startPrefetch(results: List<SearchResult>) {
        prefetchJob?.cancel()
        streamCache.clear()
        val targets = results.take(PREFETCH_COUNT)
        if (targets.isEmpty()) return
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            for (r in targets) {
                if (!isActive) return@launch
                runCatching { InnerTube.resolve(r.videoId) }
                    .onSuccess { streamCache[r.videoId] = it }
                    .onFailure { Log.w("Search", "prefetch miss for ${r.videoId}: ${it.message}") }
            }
            Log.i("Search", "prefetched ${streamCache.size}/${targets.size} stream URLs")
        }
    }

    /**
     * The core flow: get a stream URL and start playing.
     *
     * A prefetched hit costs no network at all; a miss falls back to a normal
     * resolve. Only the tapped track is resolved before playback — everything
     * else happens behind it, so the wait to hear music stays at one round
     * trip in the worst case and zero in the usual one.
     */
    fun playResult(result: SearchResult) {
        if (_state.value.loadingVideoId != null) return

        playJob?.cancel()
        // Optimistic: put the track in the player bar *now*, so the tap is
        // visibly answered before audio can possibly be ready.
        _state.update { it.copy(pending = result, loadingVideoId = result.videoId, error = null) }

        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val cached = streamCache.remove(result.videoId)
                val resolved = cached ?: InnerTube.resolve(result.videoId)
                playerController.play(listOf(mediaItem(result, resolved)))
                Log.i(
                    "Play",
                    "started '${result.title}' " +
                        "${resolved.stream.bitrate / 1000}kbps ${resolved.stream.container} " +
                        if (cached != null) "(prefetched)" else "(resolved on tap)",
                )
                // Build the continuous mix behind the first track. Failures here
                // are not worth telling the user about — they just get a shorter
                // queue than intended.
                fillQueue(result)
            } catch (t: Throwable) {
                Log.e("Play", "could not play '${result.title}'", t)
                // Drop the optimistic placeholder too — the bar must not keep
                // advertising a track that is never going to start.
                _state.update {
                    it.copy(error = t.message ?: "Could not play this track", pending = null)
                }
            } finally {
                // Only clear if nothing newer has taken our slot — a cancelled
                // job's finally runs after its successor has already started.
                _state.update {
                    if (it.loadingVideoId == result.videoId) it.copy(loadingVideoId = null) else it
                }
            }
        }
        playJob = job
    }

    /**
     * Resolve the related tracks and append them, so tapping one song yields a
     * continuous mix instead of a single track that stops dead at the end.
     */
    private suspend fun fillQueue(seed: SearchResult) {
        val related = runCatching { InnerTube.related(seed.videoId) }
            .getOrDefault(emptyList())
            .filter { it.videoId != seed.videoId }
            .take(RELATED_QUEUE_SIZE)

        val items = mutableListOf<MediaItem>()
        for (r in related) {
            // Cancellation check: the user may have tapped something else.
            if (!coroutineContext.isActive) return
            val resolved = runCatching { InnerTube.resolve(r.videoId) }.getOrNull() ?: continue
            items += mediaItem(r, resolved)
        }
        if (items.isNotEmpty()) playerController.enqueue(items)
    }

    private fun mediaItem(result: SearchResult, resolved: InnerTube.Resolved): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(result.title.ifEmpty { resolved.title })
            .setArtist(result.channel)
        if (result.thumbUrl.isNotEmpty()) {
            meta.setArtworkUri(Uri.parse(result.thumbUrl))
        }
        return MediaItem.Builder()
            .setUri(resolved.stream.url)
            .setMimeType(resolved.stream.container)
            .setMediaMetadata(meta.build())
            .build()
    }

    fun next() = playerController.next()
    fun previous() = playerController.previous()
    fun togglePlayPause() = playerController.togglePlayPause()
    fun seekTo(positionMs: Long) = playerController.seekTo(positionMs)

    private companion object {
        /** Enough for a solid mix, few enough that the queue fills while track 1 plays. */
        const val RELATED_QUEUE_SIZE = 6

        /**
         * How many top results get their stream URL resolved ahead of the tap.
         * Five covers a realistic reading distance down the list without
         * hammering YouTube.
         */
        const val PREFETCH_COUNT = 5
    }
}

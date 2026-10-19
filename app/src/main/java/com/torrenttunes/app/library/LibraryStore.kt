package com.torrenttunes.app.library

import android.content.Context
import android.content.SharedPreferences
import com.torrenttunes.app.search.SearchResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** A user-created collection of tracks. */
data class Playlist(
    val id: String,
    val name: String,
    val tracks: List<SearchResult>,
)

/**
 * Everything the user has chosen to keep.
 *
 * Ordered the way the Library screen reads: recently played first, then the
 * pinned/liked tracks, then the playlists they made.
 */
data class LibraryState(
    val recent: List<SearchResult> = emptyList(),
    val liked: List<SearchResult> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
)

/**
 * Persistent home for likes, playlists and play history.
 *
 * Backed by [SharedPreferences] with a JSON document rather than a database —
 * the whole library is a few hundred rows at most, and this keeps the app
 * dependency-free. **Nothing is written to disk permanently unless the user
 * acts**: there is no automatic download, and the store only ever records what
 * they explicitly liked, queued-into-a-playlist, or played.
 */
@Singleton
class LibraryStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("torrenttunes.library", Context.MODE_PRIVATE)

    /** Guards read-modify-write so a rapid double-tap cannot lose an entry. */
    private val lock = Any()

    private val _state = MutableStateFlow(load())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    // ---------------------------------------------------------------- likes

    fun isLiked(videoId: String): Boolean =
        _state.value.liked.any { it.videoId == videoId }

    /** True when the track is now liked. */
    fun toggleLike(track: SearchResult): Boolean = mutate { s ->
        val has = s.liked.any { it.videoId == track.videoId }
        s.copy(
            liked = if (has) s.liked.filterNot { it.videoId == track.videoId }
                    else listOf(track) + s.liked,
        ) to !has
    } as Boolean

    // --------------------------------------------------------------- recent

    /** Records a play. Moves an existing entry to the top rather than duplicating. */
    fun recordPlay(track: SearchResult) = mutate { s ->
        val rest = s.recent.filterNot { it.videoId == track.videoId }
        s.copy(recent = (listOf(track) + rest).take(RECENT_CAP)) to Unit
    }

    fun clearRecent() = mutate { s -> s.copy(recent = emptyList()) to Unit }

    // ----------------------------------------------------------- playlists

    /** Creates a playlist and returns its id. Blank names are rejected as null. */
    fun createPlaylist(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val id = "pl_${System.currentTimeMillis()}_${(0..9999).random()}"
        mutate { s ->
            s.copy(playlists = s.playlists + Playlist(id, trimmed, emptyList())) to Unit
        }
        return id
    }

    fun deletePlaylist(id: String) = mutate { s ->
        s.copy(playlists = s.playlists.filterNot { it.id == id }) to Unit
    }

    fun renamePlaylist(id: String, name: String) = mutate { s ->
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@mutate s to Unit
        s.copy(
            playlists = s.playlists.map { if (it.id == id) it.copy(name = trimmed) else it },
        ) to Unit
    }

    /** True when the track was added; false if it was already there. */
    fun addToPlaylist(playlistId: String, track: SearchResult): Boolean = mutate { s ->
        val target = s.playlists.find { it.id == playlistId } ?: return@mutate s to false
        if (target.tracks.any { it.videoId == track.videoId }) return@mutate s to false
        s.copy(
            playlists = s.playlists.map {
                if (it.id == playlistId) it.copy(tracks = it.tracks + track) else it
            },
        ) to true
    } as Boolean

    fun removeFromPlaylist(playlistId: String, videoId: String) = mutate { s ->
        s.copy(
            playlists = s.playlists.map {
                if (it.id == playlistId) it.copy(tracks = it.tracks.filterNot { t -> t.videoId == videoId })
                else it
            },
        ) to Unit
    }

    // ------------------------------------------------------------- storage

    private fun mutate(block: (LibraryState) -> Pair<LibraryState, Any?>): Any? =
        synchronized(lock) {
            val (next, result) = block(_state.value)
            _state.value = next
            save(next)
            result
        }

    private fun load(): LibraryState = synchronized(lock) {
        val raw = prefs.getString(KEY_DOC, null) ?: return LibraryState()
        val doc = runCatching { JSONObject(raw) }.getOrNull() ?: return LibraryState()
        LibraryState(
            recent = readTracks(doc.optJSONArray(RECENT)),
            liked = readTracks(doc.optJSONArray(LIKED)),
            playlists = readPlaylists(doc.optJSONArray(PLAYLISTS)),
        )
    }

    private fun save(s: LibraryState) {
        val doc = JSONObject()
            .put(RECENT, writeTracks(s.recent))
            .put(LIKED, writeTracks(s.liked))
            .put(PLAYLISTS, writePlaylists(s.playlists))
        prefs.edit().putString(KEY_DOC, doc.toString()).apply()
    }

    // ------------------------------------------------------------ json codec

    private fun writeTracks(tracks: List<SearchResult>): JSONArray {
        val arr = JSONArray()
        tracks.forEach { t ->
            arr.put(
                JSONObject()
                    .put("videoId", t.videoId)
                    .put("title", t.title)
                    .put("channel", t.channel)
                    .put("durationLabel", t.durationLabel)
                    .put("durationSec", t.durationSec)
                    .put("viewsLabel", t.viewsLabel)
                    .put("views", t.views)
                    .put("thumbUrl", t.thumbUrl),
            )
        }
        return arr
    }

    private fun readTracks(arr: JSONArray?): List<SearchResult> {
        if (arr == null) return emptyList()
        val out = ArrayList<SearchResult>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("videoId")
            if (id.isNullOrEmpty()) continue
            out += SearchResult(
                videoId = id,
                title = o.optString("title"),
                channel = o.optString("channel"),
                durationLabel = o.optString("durationLabel"),
                durationSec = o.optLong("durationSec", 0L),
                viewsLabel = o.optString("viewsLabel"),
                views = o.optLong("views", -1L),
                thumbUrl = o.optString("thumbUrl"),
            )
        }
        return out
    }

    private fun writePlaylists(pls: List<Playlist>): JSONArray {
        val arr = JSONArray()
        pls.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("tracks", writeTracks(p.tracks)),
            )
        }
        return arr
    }

    private fun readPlaylists(arr: JSONArray?): List<Playlist> {
        if (arr == null) return emptyList()
        val out = ArrayList<Playlist>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isNullOrEmpty()) continue
            out += Playlist(id = id, name = o.optString("name"), tracks = readTracks(o.optJSONArray("tracks")))
        }
        return out
    }

    private companion object {
        const val KEY_DOC = "doc"
        const val RECENT = "recent"
        const val LIKED = "liked"
        const val PLAYLISTS = "playlists"

        /** Long enough to feel like history, short enough to stay a glance. */
        const val RECENT_CAP = 50
    }
}

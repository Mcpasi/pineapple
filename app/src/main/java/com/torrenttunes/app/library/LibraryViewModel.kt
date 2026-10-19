package com.torrenttunes.app.library

import androidx.lifecycle.ViewModel
import com.torrenttunes.app.search.SearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Exposes [LibraryStore] to Compose. Kept separate from [MainViewModel] so
 * playback and persistence never share a blast radius — a bad edit to a
 * playlist cannot disturb the player.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val store: LibraryStore,
) : ViewModel() {

    val state: StateFlow<LibraryState> = store.state

    fun isLiked(videoId: String): Boolean = store.isLiked(videoId)

    fun toggleLike(track: SearchResult): Boolean = store.toggleLike(track)

    fun recordPlay(track: SearchResult) = store.recordPlay(track)

    fun createPlaylist(name: String): String? = store.createPlaylist(name)

    fun deletePlaylist(id: String) = store.deletePlaylist(id)

    fun renamePlaylist(id: String, name: String) = store.renamePlaylist(id, name)

    fun addToPlaylist(playlistId: String, track: SearchResult): Boolean =
        store.addToPlaylist(playlistId, track)

    fun removeFromPlaylist(playlistId: String, videoId: String) =
        store.removeFromPlaylist(playlistId, videoId)

    fun clearRecent() = store.clearRecent()
}

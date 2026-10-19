package com.torrenttunes.app.player

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.torrenttunes.app.playback.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin glue between resolved stream URLs and ExoPlayer.
 *
 * There is no torrent engine and no local HTTP server any more: a track is
 * already a plain HTTPS URL by the time it reaches this class, so ExoPlayer
 * reads it directly and owns buffering, seeking and gapless transitions itself.
 */
@Singleton
class PlayerController @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    /** Built on the main thread by [com.torrenttunes.app.di.AppModule]. */
    val player: Player,
) {

    private val _queue = MutableStateFlow<List<MediaItem>>(emptyList())
    val queue: StateFlow<List<MediaItem>> = _queue.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Route every player call onto the thread that created the player.
     *
     * ExoPlayer is not thread-safe: it must only be touched from the thread
     * that built it, and it fails loudly —
     * "Player is accessed on the wrong thread" — when you don't.
     *
     * Callers genuinely *should* run elsewhere: resolving a stream URL is a
     * network call and belongs on Dispatchers.IO. So rather than trusting every
     * call site to remember to hop back, all player access is funnelled through
     * here. Handler.post preserves ordering, so a play() followed by an
     * enqueue() still lands in that order even when both originate off-main.
     */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** Replace the queue and start at [startIndex]. */
    fun play(items: List<MediaItem>, startIndex: Int = 0) {
        if (items.isEmpty()) return

        // Start the MediaSessionService so playback survives backgrounding and
        // gets a real notification, lockscreen and Bluetooth controls.
        //
        // Plain startService, not startForegroundService: the latter demands
        // startForeground() within 5s or the process is killed with
        // ForegroundServiceDidNotStartInTimeException. Media3 promotes itself
        // to foreground once playback is genuinely active.
        try {
            context.startService(Intent(context, PlaybackService::class.java))
        } catch (t: Throwable) {
            // Android 8+ blocks this from the background; playback still works
            // in-process, we just lose the notification. Not worth crashing.
            Log.w("PlayerController", "could not start PlaybackService", t)
        }

        onMain {
            Log.i("PlayerController", "play from ${items.size} item(s) at index $startIndex")
            _queue.value = items
            player.setMediaItems(items, startIndex, 0)
            player.prepare()
            player.play()
        }
    }

    /**
     * Append to the running queue.
     *
     * Called as the related-track lookups land, so the first tap starts music
     * immediately and a continuous mix builds up behind it rather than making
     * the listener wait for a whole queue to resolve first.
     */
    fun enqueue(items: List<MediaItem>) {
        if (items.isEmpty()) return
        onMain {
            _queue.value = _queue.value + items
            player.addMediaItems(items)
            // A bare add while idle would sit invisible if playback already
            // stopped; nudge it back into preparing.
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
                player.play()
            }
        }
    }

    fun togglePlayPause() = onMain {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(positionMs: Long) = onMain { player.seekTo(positionMs) }

    fun next() = onMain { player.seekToNextMediaItem() }

    fun previous() = onMain { player.seekToPreviousMediaItem() }
}

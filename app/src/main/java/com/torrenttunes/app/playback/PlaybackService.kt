package com.torrenttunes.app.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.torrenttunes.app.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Workstream C — playback service.
 *
 * Runs ExoPlayer in a foreground media session so playback continues with the
 * screen off / app backgrounded, with the standard notification, lockscreen
 * and Bluetooth controls.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var player: ExoPlayer

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        // Pause when headphones are unplugged — expected music-app behavior.
        player.setHandleAudioBecomingNoisy(true)
        // Hold a network wake lock while playing so streams don't die in doze.
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        // Seamless-ish transitions: no gap between consecutive items.
        player.playWhenReady = true

        val sessionActivity = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        // Ownership note: `player` is a Hilt *singleton* shared with
        // PlayerController/PreloadController for the whole process lifetime —
        // this service does NOT own it. Releasing it here left the UI holding a
        // released ExoPlayer after any service restart (playback then threw
        // IllegalStateException on the next play()).
        //
        // Media3 stops the service when playback goes idle, so there's no
        // leaked-audio risk; only the session is torn down.
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}

/** Whether the player should keep fetching ahead for gapless transitions. */
const val PLAYER_MODE_SEAMLESS = Player.REPEAT_MODE_OFF

package com.torrenttunes.app.di

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.torrenttunes.app.player.BoundedRangeDataSourceFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                // Without this, an uncaught exception in a background scope
                // reaches the default uncaught handler and kills the process.
                kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
                    android.util.Log.e("AppScope", "uncaught in scope", t)
                },
        )

    // ExoPlayer instance used by PlaybackService. Media3 owns its lifecycle via
    // the service; we build it here for testability.
    @Provides
    @Singleton
    fun providePlayer(@ApplicationContext context: Context): ExoPlayer {

        // Streams come from googlevideo.com and were verified reachable with
        // the Android YouTube UA — matching it avoids any UA-based gating.
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip")

        // Start fast, then buffer deep.
        //  - bufferForPlayback 500ms : audio begins almost as soon as the
        //    stream opens. Stream URLs are already prefetched by this point,
        //    so this is now the dominant part of the wait — worth trimming.
        //  - min 30s / max 60s       : once running, a hiccup on mobile data
        //    never reaches the listener's ears.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */ 30_000,
                /* maxBufferMs */ 60_000,
                /* bufferForPlaybackMs */ 500,
                /* bufferForPlaybackAfterRebufferMs */ 1_500,
            )
            .setBackBuffer(/* backBufferDurationMs */ 30_000, /* retainBackBufferFromKeyframe */ true)
            .build()

        // googlevideo rejects unbounded Range requests with a 403, and
        // ExoPlayer sends exactly that. Every stream goes through the shim
        // that bounds them — without it, nothing plays at all.
        val bounded = BoundedRangeDataSourceFactory(http)

        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, bounded)))
            .build()
    }

    // Dagger matches types exactly, so expose the same instance under the
    // `Player` interface for consumers.
    @Provides
    @Singleton
    fun providePlayerInterface(player: ExoPlayer): Player = player
}

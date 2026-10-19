package com.torrenttunes.app.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource

/**
 * Streams a googlevideo URL by issuing a series of **bounded** range requests,
 * presenting them to ExoPlayer as one continuous file.
 *
 * Why this exists
 * ---------------
 * YouTube's ANDROID client hands out plain, unciphered stream URLs — the only
 * reason this app can play anything without shipping a JavaScript engine. The
 * price is that googlevideo refuses to serve them the way a normal progressive
 * player asks:
 *
 *     bare GET             -> 403
 *     Range: bytes=0-      -> 403
 *     Range: bytes=0-N     -> 206   (N bounded, and not past the file's end)
 *
 * ExoPlayer's first request carries no Range at all, and every request after a
 * seek is open-ended. Both 403, which surfaced as an instant "Source error" on
 * tap and a player bar frozen at 0:00.
 *
 * A fixed-size window is not safe either: asking for more bytes than the file
 * contains is rejected too. So each chunk is clamped twice — to [CHUNK_BYTES]
 * and to whatever `clen` (content length) says is actually left.
 *
 * Reading past a chunk opens the next one transparently, so ExoPlayer sees a
 * single uninterrupted stream and never knows about the chunking.
 */
@UnstableApi
class BoundedRangeDataSource(
    private val delegate: DefaultHttpDataSource,
) : BaseDataSource(/* isNetworkDataSource = */ true) {

    /** The request being served; chunks are re-opened from it. */
    private var baseSpec: DataSpec? = null

    /** Absolute offset of the next byte to fetch. */
    private var nextPos = 0L

    /** Total file size from the URL's `clen` param, or -1 if absent. */
    private var totalBytes = UNKNOWN

    private var transferring = false

    override fun open(dataSpec: DataSpec): Long {
        baseSpec = dataSpec
        nextPos = dataSpec.position
        totalBytes = contentLengthOf(dataSpec.uri) ?: UNKNOWN

        openChunk()
        transferring = true
        transferStarted(dataSpec)

        // Report the real remaining size, not the first chunk's, so ExoPlayer
        // seeks and progress are correct across chunk boundaries.
        return if (totalBytes == UNKNOWN) C.LENGTH_UNSET.toLong() else totalBytes - dataSpec.position
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            val read = delegate.read(target, offset, length)
            if (read != C.RESULT_END_OF_INPUT) {
                nextPos += read
                bytesTransferred(read)
                return read
            }
            // Chunk exhausted. Without a known total we must not loop — an
            // open-ended Range is precisely what 403s, so end the stream here.
            if (totalBytes == UNKNOWN || nextPos >= totalBytes) {
                endTransfer()
                return C.RESULT_END_OF_INPUT
            }
            openChunk()
        }
    }

    /** Open the next bounded window starting at [nextPos]. */
    private fun openChunk() {
        val spec = baseSpec ?: return
        val remaining =
            if (totalBytes == UNKNOWN) CHUNK_BYTES else (totalBytes - nextPos).coerceAtLeast(1L)
        val window = minOf(CHUNK_BYTES, remaining)
        delegate.open(spec.buildUpon().setPosition(nextPos).setLength(window).build())
    }

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    override fun close() {
        endTransfer()
        baseSpec = null
        delegate.close()
    }

    private fun endTransfer() {
        if (transferring) {
            transferEnded()
            transferring = false
        }
    }

    private companion object {
        const val UNKNOWN = -1L

        /**
         * Bytes per request. Comfortably below any observed rejection and large
         * enough that a whole track is only a handful of requests — a 2.5 MB
         * song is three round trips.
         */
        const val CHUNK_BYTES = 1024L * 1024

        /** `clen` is the content length YouTube embeds in every stream URL. */
        fun contentLengthOf(uri: Uri): Long? =
            uri.getQueryParameter("clen")?.toLongOrNull()?.takeIf { it > 0 }
    }
}

/** Factory so this can be handed to `DefaultDataSource` in place of the plain one. */
@UnstableApi
class BoundedRangeDataSourceFactory(
    private val delegateFactory: DefaultHttpDataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        BoundedRangeDataSource(delegateFactory.createDataSource())
}

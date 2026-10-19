package com.torrenttunes.app.search

/**
 * One playable track. Everything here is what YouTube's own search returns —
 * there is no swarm, no infohash and no piece map any more.
 *
 * [durationLabel] is kept as the raw text ("4:19") because that is what the
 * UI shows and what lets us spot live streams (which have no length).
 */
data class SearchResult(
    val videoId: String,
    val title: String,
    val channel: String,
    val durationLabel: String,
    val durationSec: Long,     // 0 when unknown or live
    val viewsLabel: String,
    val views: Long,           // -1 when unparsable
    val thumbUrl: String,      // album art; may be empty
    val isTopPick: Boolean = false,
) {
    /** Stable key — YouTube's video id is already unique per track. */
    val dedupeKey: String get() = videoId
}

/** A stream URL resolved for a video, ready to hand to ExoPlayer. */
data class AudioStream(
    val url: String,
    val mimeType: String,      // e.g. `audio/mp4; codecs="mp4a.40.2"`
    val bitrate: Int,          // bits per second
    val contentLength: Long,   // 0 when YouTube omits it
) {
    val container: String
        get() = mimeType.substringBefore(";").trim()
}

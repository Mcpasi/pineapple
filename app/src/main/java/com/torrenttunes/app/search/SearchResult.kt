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
    /**
     * Catalogue page id when this row is a door to a collection rather than a
     * playable track: an artist (`UC…`) or an album (`MPRE…`). Empty for every
     * track, so nothing that keys off playback sees a difference.
     */
    val browseId: String = "",
) {
    /**
     * True when this row opens an album page instead of starting audio. Album
     * pages carry YouTube's `MPRE` id prefix, which is what separates them from
     * an artist door at a glance.
     */
    val isAlbum: Boolean get() = videoId.isEmpty() && browseId.startsWith("MPRE")

    /** True when this row opens an artist page instead of starting audio. */
    val isArtist: Boolean get() = videoId.isEmpty() && browseId.isNotEmpty() && !isAlbum

    /**
     * Stable key — YouTube's video id is already unique per track, and a
     * catalogue door has none, so it keys off the page id instead. Two door rows
     * both keyed "" would be a LazyColumn duplicate-key crash, not a wrinkle.
     */
    val dedupeKey: String get() = if (videoId.isNotEmpty()) videoId else "browse:$browseId"
}

/**
 * What `/browse` gives for an artist page: the header plus its Top songs.
 *
 * Header fields are empty rather than nullable when a payload omits them —
 * the caller keeps the seed row's name and avatar in that case, so the page
 * never renders blank because one field went missing.
 */
data class ArtistDetail(
    val name: String,
    val audienceLabel: String,   // "7.83M monthly audience"
    val bannerUrl: String,       // wide header art; may be empty
    val topSongs: List<SearchResult>,
)

/**
 * What `/browse` gives for an album page (`MPRE…`): the header plus its
 * ordered track list.
 *
 * The album's own detail is authoritative here — the year, track count and
 * duration come from the header rather than being guessed from the seed row,
 * which only carried a title, an artist and a thumbnail.
 */
data class AlbumDetail(
    val title: String,
    val artistName: String,
    val artistBrowseId: String,   // `UC…`; empty when the header omits the link
    val kindLabel: String,        // "Album" / "Single"
    val yearLabel: String,        // "1994"; empty when unstated
    val trackCountLabel: String,  // "11 songs"; empty when unstated
    val durationLabel: String,    // "49 minutes"; empty when unstated
    val artUrl: String,           // square cover art; may be empty
    val tracks: List<SearchResult>,
)

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

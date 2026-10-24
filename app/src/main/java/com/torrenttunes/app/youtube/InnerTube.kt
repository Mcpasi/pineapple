package com.torrenttunes.app.youtube

import com.torrenttunes.app.search.AudioStream
import com.torrenttunes.app.search.Http
import com.torrenttunes.app.search.ArtistDetail
import com.torrenttunes.app.search.AlbumDetail
import com.torrenttunes.app.search.SearchResult
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Direct client for YouTube's private InnerTube API — the same endpoints the
 * official apps call. No API key, no quota, and critically: **no signature
 * cipher**.
 *
 * Why the client dance matters
 * ----------------------------
 * `/player` returns very different payloads depending on which client claims
 * to be calling. The WEB client encrypts stream URLs behind `signatureCipher`,
 * which needs JavaScript evaluation to decode — we cannot do that on-device
 * without shipping a JS engine. The **VISIONOS** client hands back **plain
 * URLs** *and* is the one client whose streams googlevideo serves in full
 * (see [PLAYER_CLIENTS] for the measurements), so it leads the list.
 *
 * This is the fragile seam of the whole app: YouTube rotates client
 * requirements periodically. All of that churn is confined to [PlayerClient]
 * below — when it breaks, bump a version string, don't rewrite the app.
 * Fallbacks are tried in order so a single dead client degrades instead of
 * failing the tap.
 */
object InnerTube {

    private const val KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val WEB_VERSION = "2.20241001.00.00"

    // YouTube Music's web client (id 67). Used for search only — it is the one
    // endpoint that answers with songs instead of everything on YouTube.
    // Playback deliberately stays on VISIONOS.
    private const val MUSIC_VERSION = "1.20250310.01.00"
    private const val MUSIC_CLIENT_NAME = "67"

    /**
     * The only two `musicVideoType`s worth showing an artist search: the
     * label's own audio, and its official music video. Everything else —
     * user renditions, podcast episodes, videos-shelf rows with no type at
     * all — is a blacklist waiting to leak a new category we never named.
     */
    private val OFFICIAL_MUSIC_TYPES = setOf(
        "MUSIC_VIDEO_TYPE_ATV",
        "MUSIC_VIDEO_TYPE_OMV",
    )

    /** The explore page — its shelves are where Discovery gets its content. */
    private const val EXPLORE_BROWSE_ID = "FEmusic_explore"
    private const val ANDROID_UA =
        "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /**
     * Identity of the VISIONOS player client.
     *
     * Shared with the media fetch in `AppModule` so the request for the bytes
     * carries the same identity as the URL that was issued for it. (Verified:
     * a VISIONOS URL serves the whole file under this UA; no other field of
     * the media request is checked.)
     */
    internal const val VISIONOS_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/26.0 Safari/605.1.15"

    private const val ENDPOINT = "https://www.youtube.com/youtubei/v1/"

    // VISIONOS answers LOGIN_REQUIRED unless the player request carries a
    // visitor id. Every InnerTube response hands one back — including the
    // rejected player response — so the first failure is what teaches us ours.
    // Wrote it as a plain field on purpose: search and playback both run on the
    // same app, so a search that already happened pays nothing here.
    @Volatile
    private var visitorId: String? = null

    /** Remember the visitor id from any InnerTube response. */
    private fun captureVisitor(root: JSONObject) {
        val vd = runCatching {
            root.optJSONObject("responseContext")?.optString("visitorData").orEmpty()
        }.getOrDefault("")
        if (vd.isNotEmpty()) visitorId = vd
    }

    // ------------------------------------------------------------- clients

    /** A client identity for `/player`. Each has its own UA and body extras. */
    private data class PlayerClient(
        val name: String,
        val version: String,
        val clientNameId: String,
        val userAgent: String,
        /** Extra top-level body fields, e.g. the embed context. */
        val extras: Map<String, Any?> = emptyMap(),
        /** Extra fields merged into `context.client`. */
        val clientExtras: Map<String, Any?> = emptyMap(),
    )

    // Verified 2026-10-20: VISIONOS is the only client that returns the WHOLE
    // audio file. Everything below it resolves a URL but googlevideo then
    // serves at most a 1 MiB preview of it:
    //
    //   range                ANDROID 20.10.38   VISIONOS 1.02
    //   no Range (whole)     403                200  (read the full 4.7 MB)
    //   0-65535              206                206
    //   1048576-2097151      403                206
    //   3000000-4194303      403                206
    //
    // The gate is the client, not the region: the VISIONOS URL still carries
    // `gcr=in` and plays fine, and no proof-of-origin token is involved
    // (`pot` is absent). It needs `X-Goog-Visitor-Id` on the player request or
    // it answers LOGIN_REQUIRED — see [captureVisitor].
    private val PLAYER_CLIENTS = listOf(
        PlayerClient(
            name = "VISIONOS", version = "1.02", clientNameId = "101",
            userAgent = VISIONOS_UA,
            clientExtras = mapOf(
                "deviceMake" to "Apple",
                "deviceModel" to "RealityDevice17,1",
                "osName" to "visionOS",
                "osVersion" to "26.5.23O471",
            ),
            extras = mapOf("contentCheckOk" to true, "racyCheckOk" to true),
        ),
        PlayerClient(
            name = "ANDROID", version = "20.10.38", clientNameId = "3",
            userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip",
            clientExtras = mapOf("androidSdkVersion" to 30),
            extras = mapOf("contentCheckOk" to true, "racyCheckOk" to true),
        ),
        // Verified 2026-10-20: ANDROID_VR 1.43.32 (Oculus Quest build) returns
        // `status: OK` with plain URLs, needs neither a PoToken nor a
        // signatureTimestamp, and serves whole byte ranges. It is the primary
        // client the Convx player ships with — the strongest fallback we have,
        // so it goes ahead of the ANDROID build that is known to return only
        // ciphered URLs.
        //
        // Re-checked 2026-10-21 with `tools/probe_clients.py`: ANDROID_VR now
        // answers LOGIN_REQUIRED ("Sign in to confirm you're not a bot"), so
        // it no longer serves as a fallback. Left in place because a dead
        // client costs one wasted request and costs nothing to keep, but do
        // not rely on it — and if a real second client is ever needed, that
        // probe is how to find one.
        PlayerClient(
            name = "ANDROID_VR", version = "1.43.32", clientNameId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.43.32 " +
                "(Linux; U; Android 12; en_US; Quest 3; " +
                "Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)",
            clientExtras = mapOf(
                "androidSdkVersion" to 32,
                "osName" to "Android",
                "osVersion" to "12",
                "deviceMake" to "Oculus",
                "deviceModel" to "Quest 3",
            ),
            extras = mapOf("contentCheckOk" to true, "racyCheckOk" to true),
        ),
        PlayerClient(
            name = "ANDROID", version = "21.37.36", clientNameId = "3",
            userAgent = "com.google.android.youtube/21.37.36 (Linux; U; Android 11) gzip",
            clientExtras = mapOf("androidSdkVersion" to 30),
            extras = mapOf("contentCheckOk" to true, "racyCheckOk" to true),
        ),
        PlayerClient(
            name = "IOS", version = "19.45.4", clientNameId = "5",
            userAgent = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X)",
            clientExtras = mapOf("deviceModel" to "iPhone16,2"),
            extras = mapOf("contentCheckOk" to true, "racyCheckOk" to true),
        ),
    )

    // -------------------------------------------------------------- search

    /**
     * Search for tracks. Results arrive in YouTube's own relevance order,
     * which for music queries already puts the real recording first.
     */
    /**
     * Search YouTube Music first; fall back to a plain web search only when
     * Music gives us nothing.
     *
     * Music-first is the whole point. The WEB endpoint is a literal
     * youtube.com search, so vlogs, podcasts, reactions and dance covers come
     * back beside the track you meant. See [searchMusic].
     */
    suspend fun search(query: String): List<SearchResult> {
        val music = try {
            searchMusic(query)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        }
        if (!music.isNullOrEmpty()) return music

        val body = JSONObject()
            .put("context", webContext())
            .put("query", query)
        val raw = Http.postJson(
            url = "${ENDPOINT}search?key=$KEY",
            jsonBody = body.toString(),
            headers = webHeaders(),
        )
        val root = JSONObject(raw)
        // Search is what usually runs first, so this is where we usually learn
        // the visitor id the player request needs.
        captureVisitor(root)
        return parseSearch(root)
    }

    /**
     * YouTube Music's trending chart — what the Discovery tab shows.
     *
     * `/browse` with [EXPLORE_BROWSE_ID] returns the explore page, whose
     * carousels are New albums & singles, Moods & genres, Popular episodes and
     * Trending. Only the Trending shelf is wanted, and its rows are parsed by
     * the same routine search uses, so a track looks identical wherever it was
     * found.
     */
    suspend fun trending(): List<SearchResult> {
        val body = JSONObject()
            .put("context", musicContext())
            .put("browseId", EXPLORE_BROWSE_ID)
        val raw = Http.postJson(
            url = "${ENDPOINT}browse?key=$KEY",
            jsonBody = body.toString(),
            headers = musicHeaders(),
        )
        val root = JSONObject(raw)
        captureVisitor(root)
        return parseTrending(root)
    }

    /**
     * The artist page for [browseId] (`UC…`), or null when the request failed.
     *
     * Same `/browse` shape as [trending] — context plus an id — so nothing new
     * enters the networking layer to open a catalogue.
     */
    suspend fun artistDetail(browseId: String): ArtistDetail? {
        if (browseId.isEmpty()) return null
        val body = JSONObject()
            .put("context", musicContext())
            .put("browseId", browseId)
        val raw = try {
            Http.postJson(
                url = "${ENDPOINT}browse?key=$KEY",
                jsonBody = body.toString(),
                headers = musicHeaders(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Same contract as searchMusic: a failed request is null, not a
            // crash and not an exception the screen has to know about.
            return null
        }
        val root = JSONObject(raw)
        captureVisitor(root)
        return parseArtistDetail(root)
    }

    /** Videos related to [videoId] — used to build a continuous queue. */
    suspend fun related(videoId: String): List<SearchResult> {
        val body = JSONObject()
            .put("context", webContext())
            .put("videoId", videoId)
        val raw = Http.postJson(
            url = "${ENDPOINT}next?key=$KEY",
            jsonBody = body.toString(),
            headers = webHeaders(),
        )
        val root = JSONObject(raw)
        captureVisitor(root)
        return parseRelated(root)
    }

    private fun webContext(): JSONObject = JSONObject().put(
        "client", JSONObject()
            .put("clientName", "WEB")
            .put("clientVersion", WEB_VERSION)
            .put("hl", "en")
            .put("gl", "US"),
    )

    private fun webHeaders(): Map<String, String> = mapOf(
        "X-Youtube-Client-Name" to "1",
        "X-Youtube-Client-Version" to WEB_VERSION,
    )

    // --------------------------------------------------------- music search

    /**
     * YouTube Music's `/search` — the only request here that answers with
     * **songs**.
     *
     * `WEB_REMIX` (client id 67) *is* YouTube Music, so every row is music by
     * construction. Playback is untouched: streams still come from VISIONOS,
     * the only client googlevideo serves in full.
     *
     * Two filters sit on top of that:
     *
     *  1. rows that are not tracks at all — Artist, Album, Single, Playlist,
     *     podcast — carry no `videoId` and are dropped;
     *  2. results are ranked against [OFFICIAL_MUSIC_TYPES]: `ATV` (the
     *     label's own audio) and `OMV` (its official video) are what an
     *     artist search is for. Everything else — UGC renditions, live
     *     sessions, "reaction to X's music" uploads, and podcast episodes,
     *     which arrive typed `MUSIC_VIDEO_TYPE_PODCAST_EPISODE` — sits
     *     behind them. Preferred, never enforced: a query with no official
     *     upload still gets music rather than a blank list.
     *
     * Returns `null` only when the request itself failed, which is what tells
     * the caller to fall back to a web search.
     */
    private suspend fun searchMusic(query: String): List<SearchResult>? {
        val body = JSONObject()
            .put("context", musicContext())
            .put("query", query)
        val raw = try {
            Http.postJson(
                url = "${ENDPOINT}search?key=$KEY",
                jsonBody = body.toString(),
                headers = musicHeaders(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return null
        }
        val root = JSONObject(raw)
        captureVisitor(root)
        return parseMusicSearch(root)
    }

    private fun musicContext(): JSONObject = JSONObject().put(
        "client", JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", MUSIC_VERSION)
            .put("hl", "en")
            .put("gl", "US"),
    )

    private fun musicHeaders(): Map<String, String> = mapOf(
        "X-Youtube-Client-Name" to MUSIC_CLIENT_NAME,
        "X-Youtube-Client-Version" to MUSIC_VERSION,
    )

    /**
     * Depth-first collection of every node named [kind].
     *
     * Music rows do not live under `twoColumnSearchResultsRenderer` — they are
     * handed out inside `musicShelfRenderer` / `musicCarouselShelfRenderer` /
     * `musicCardShelfRenderer` nodes at varying depths, and a card is not
     * shaped like a row. Walking by name sidesteps the layout question
     * entirely and survives YouTube reshuffling its sections.
     */
    private fun collectNodes(node: Any?, kind: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject(kind)?.let { out += it }
                val keys = node.keys()
                while (keys.hasNext()) collectNodes(node.opt(keys.next()), kind, out)
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collectNodes(node.opt(i), kind, out)
            }
        }
    }

    internal fun parseMusicSearch(root: JSONObject): List<SearchResult> {
        // The top-result card is a different renderer from an ordinary row,
        // and it is where an artist answer to the query lives.
        val cards = mutableListOf<JSONObject>()
        collectNodes(root, "musicCardShelfRenderer", cards)
        val rows = mutableListOf<JSONObject>()
        collectNodes(root, "musicResponsiveListItemRenderer", rows)

        val official = mutableListOf<SearchResult>()
        val userUploaded = mutableListOf<SearchResult>()
        // The walk covers the entire payload, so one song can surface twice —
        // as a shelf row and again behind its top-result card. LazyColumn keys
        // off videoId, where a repeat is a crash rather than a wrinkle.
        val seen = mutableSetOf<String>()

        // YouTube's own answer to the query goes first. An artist card used to
        // fall out of this function entirely — no video id, so parseMusicRow
        // returned null at its guard — which is how search "lost" artists it
        // had already fetched.
        val topArtists = mutableListOf<SearchResult>()
        for (card in cards) {
            val artist = parseArtistCard(card) ?: continue
            if (seen.add(artist.dedupeKey)) topArtists += artist
        }

        // Related artists arrive as ordinary rows inside their own sections.
        // They sit behind the songs on purpose: a query for music shows music
        // first, and the catalogue is one tap further down.
        val relatedArtists = mutableListOf<SearchResult>()
        // Album doors (an `MPRE` row) sit with the other catalogue doors —
        // behind the songs, ahead of the looser "related artists" tail.
        val albums = mutableListOf<SearchResult>()
        for (row in rows) {
            val artist = parseArtistRow(row)
            if (artist != null) {
                if (seen.add(artist.dedupeKey)) relatedArtists += artist
                continue
            }
            val album = parseAlbumRow(row)
            if (album != null) {
                if (seen.add(album.dedupeKey)) albums += album
                continue
            }
            val (result, isUserUpload) = parseMusicRow(row) ?: continue
            if (!seen.add(result.videoId)) continue
            if (isUserUpload) userUploaded += result else official += result
        }

        return topArtists + official.ifEmpty { userUploaded } + albums + relatedArtists
    }

    /**
     * The Trending carousel out of the explore page.
     *
     * Ordered by YouTube, not by us — that ordering *is* the chart, so
     * re-ranking it here would throw away the only thing worth fetching.
     * Every playable row is kept, official or not: the chart is already
     * curated, and demoting a trending entry the user came here to see would
     * be the filter misfiring outside search.
     */
    internal fun parseTrending(root: JSONObject): List<SearchResult> {
        val shelves = mutableListOf<JSONObject>()
        collectNodes(root, "musicCarouselShelfRenderer", shelves)

        // Prefer the shelf YouTube calls Trending. If it ever renames it, fall
        // back to the first carousel that holds playable rows rather than
        // presenting an empty Discovery tab.
        val target = shelves.firstOrNull { carouselTitle(it).equals("Trending", ignoreCase = true) }
            ?: shelves.firstOrNull { carouselTitle(it).isNotEmpty() }
            ?: return emptyList()

        val rows = mutableListOf<JSONObject>()
        collectNodes(target, "musicResponsiveListItemRenderer", rows)

        val out = mutableListOf<SearchResult>()
        val seen = mutableSetOf<String>()
        for (row in rows) {
            val (result, _) = parseMusicRow(row) ?: continue
            if (!seen.add(result.videoId)) continue
            out += result
        }
        return out
    }

    /**
     * A carousel's heading, whichever of its shapes YouTube gave it.
     *
     * The runs are read straight off the title object rather than through
     * [allRunsText], which looks for a nested `text` wrapper. A header's title
     * carries its runs directly, so routing it through that helper returns an
     * empty string — and an empty title makes the whole shelf look untitled,
     * which silently dropped it from Discovery.
     */
    private fun carouselTitle(carousel: JSONObject): String {
        val header = carousel.optJSONObject("header")
            ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer") ?: return ""

        header.optJSONObject("title")?.let { title ->
            title.optJSONArray("runs")?.let { runs ->
                val sb = StringBuilder()
                for (i in 0 until runs.length()) {
                    sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
                }
                if (sb.isNotEmpty()) return sb.toString()
            }
            // The newer card shape wraps the same text under `content`.
            title.optString("content").takeIf { it.isNotEmpty() }?.let { return it }
        }
        return header.optString("simpleText")
    }

    /**
     * One `musicResponsiveListItemRenderer`:
     *
     *  - column 0 is the title, whose run carries the `watchEndpoint` — and
     *    with it the video id and how YouTube classified it;
     *  - column 1 is `"Song • Artist"` or `"Video • Channel"`;
     *  - later columns hold view counts, and *only* non-official rows carry a
     *    duration, because YT Music shows "9.5B plays" for songs instead.
     *
     * Returns the row plus whether it was a user upload, or `null` when this
     * row was not a track at all.
     */
    private fun parseMusicRow(v: JSONObject): Pair<SearchResult, Boolean>? {
        val flex = v.optJSONArray("flexColumns") ?: return null
        val columns = mutableListOf<JSONObject>()
        for (i in 0 until flex.length()) {
            val col = flex.optJSONObject(i)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer") ?: continue
            columns += col
        }
        if (columns.isEmpty()) return null

        val title = allRunsText(columns[0])
        if (title.isEmpty()) return null

        var endpoint = runsWatchEndpoint(columns[0])
        if (endpoint?.optString("videoId").isNullOrEmpty()) {
            endpoint = v.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
        }
        val videoId = endpoint?.optString("videoId").orEmpty()
        // An Artist, Album, Single, Playlist or podcast header: nothing here
        // can be played, so drop the row rather than show a dead entry.
        if (videoId.isEmpty()) return null

        val musicType = endpoint
            ?.optJSONObject("watchEndpointMusicSupportedConfigs")
            ?.optJSONObject("watchEndpointMusicConfig")
            ?.optString("musicVideoType").orEmpty()

        var channel = ""
        var durationLabel = ""
        var viewsLabel = ""
        for (i in 1 until columns.size) {
            val runs = columns[i].optJSONObject("text")?.optJSONArray("runs") ?: continue
            for (j in 0 until runs.length()) {
                val run = runs.optJSONObject(j) ?: continue
                if (channel.isEmpty() && isArtistRun(run)) {
                    run.optString("text").takeIf { it.isNotBlank() }?.let { channel = it }
                }
            }
            // A column can hold several facts separated by " • " — check each
            // piece rather than the whole string, or "Video • 4:24" never
            // parses as a clock.
            for (piece in allRunsText(columns[i]).split('•')) {
                val text = piece.trim()
                if (text.isEmpty()) continue
                if (durationLabel.isEmpty() && text.contains(':') && parseClock(text) > 0) {
                    durationLabel = text
                } else if (viewsLabel.isEmpty() && text.contains("plays", ignoreCase = true)) {
                    viewsLabel = text
                }
            }
        }
        // Some rows give the artist as plain text rather than a browse link.
        // Skip the pieces YouTube has already labelled a view count or a
        // length, and the "Song"/"Video" kind tag — otherwise a user upload
        // with no linked artist gets credited to "7.6M views".
        if (channel.isEmpty() && columns.size > 1) {
            for (piece in allRunsText(columns[1]).split('•')) {
                val text = piece.trim()
                if (text.isEmpty()) continue
                if (text.contains("plays", ignoreCase = true)) continue
                if (text.contains("views", ignoreCase = true)) continue
                if (text.contains(':') && parseClock(text) > 0) continue
                if (text.equals("Song", ignoreCase = true) ||
                    text.equals("Video", ignoreCase = true) ||
                    text.equals("Episode", ignoreCase = true)
                ) {
                    continue
                }
                channel = text
                break
            }
        }

        // A whitelist, not a blacklist. ATV is the label's own audio and OMV
        // its official video; nothing else belongs in an artist search. A
        // blacklist leaks — UGC was easy to name, but podcast episodes arrive
        // as MUSIC_VIDEO_TYPE_PODCAST_EPISODE labelled "Episode", and
        // videos-shelf rows often carry no type at all.
        val isUserUpload = musicType !in OFFICIAL_MUSIC_TYPES

        return SearchResult(
            videoId = videoId,
            title = title,
            channel = channel,
            durationLabel = durationLabel,
            durationSec = parseClock(durationLabel),
            viewsLabel = viewsLabel,
            views = if (viewsLabel.isEmpty()) -1L else parseCount(viewsLabel),
            thumbUrl = thumbnailUrl(videoId),
        ) to isUserUpload
    }

    // ---------------------------------------------------------- artist pages
    //
    // An artist is not a track: no video id to play, no musicVideoType to
    // rank. `parseMusicRow` drops such a row at its `videoId.isEmpty()` guard,
    // so the catalogue door sat in payloads the app already fetched and threw
    // away. These functions keep it instead.
    //
    // Captured 2026-10-23 (WEB_REMIX): the row shape from a filter=artists
    // search, the card shape from a mixed search's top result.

    /** Page id from a row/card navigation endpoint, or "" when not an artist. */
    private fun artistBrowseId(endpoint: JSONObject?): String {
        val browse = endpoint?.optJSONObject("browseEndpoint") ?: return ""
        val browseId = browse.optString("browseId")
        if (browseId.isEmpty()) return ""
        val pageType = browse
            .optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")
            ?.optString("pageType").orEmpty()
        // `UC` is YouTube's channel-id prefix — the same prefix ytmusicapi
        // maps to "artist" — and when pageType is present it must say artist,
        // so an album (MPRE) or playlist row can never pass as one.
        return when {
            pageType.isNotEmpty() && pageType != "MUSIC_PAGE_TYPE_ARTIST" -> ""
            pageType.isEmpty() && !browseId.startsWith("UC") -> ""
            else -> browseId
        }
    }

    /** `"Artist"` and `"51.9M monthly audience"` out of a subtitle's runs. */
    private fun kindAndCount(subtitle: JSONObject?): Pair<String, String> {
        // A card's subtitle holds `runs` directly; a row's flex column wraps
        // them under `text`. Accept either — the runs themselves are the same
        // shape. (JSONArray.optString on a run *object* would stringify the
        // whole object back as JSON, so each run is unwrapped explicitly.)
        val runs = subtitle?.optJSONArray("runs")
            ?: subtitle?.optJSONObject("text")?.optJSONArray("runs")
            ?: return "" to ""
        fun runText(i: Int): String = runs.optJSONObject(i)?.optString("text").orEmpty()
        if (runs.length() == 0) return "" to ""
        val kind = runText(0)
        return if (runs.length() == 1) kind to "" else kind to runText(runs.length() - 1)
    }

    /** Largest thumbnail on a row or card — an avatar, not the i.ytimg art. */
    private fun avatarUrl(container: JSONObject?): String {
        val thumbs = container
            ?.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails") ?: return ""
        // YouTube lists these smallest first; keep the last one that parsed.
        var url = ""
        for (i in 0 until thumbs.length()) {
            url = thumbs.optJSONObject(i)?.optString("url").orEmpty().ifEmpty { url }
        }
        return url
    }

    /** Runs joined from a node holding `runs` directly (headers, subtitles). */
    private fun runsText(node: JSONObject?): String {
        val runs = node?.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return sb.toString()
    }

    /**
     * An artist row: `navigationEndpoint` sits on the *row*, not on a title
     * run — the whole row is the link to the page.
     */
    private fun parseArtistRow(v: JSONObject): SearchResult? {
        val browseId = artistBrowseId(v.optJSONObject("navigationEndpoint"))
        if (browseId.isEmpty()) return null

        val flex = v.optJSONArray("flexColumns") ?: return null
        val name = flex.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.let { allRunsText(it) }.orEmpty()
        if (name.isEmpty()) return null

        val (kind, count) = kindAndCount(
            flex.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer"),
        )
        return SearchResult(
            videoId = "",
            title = name,
            channel = kind.ifEmpty { "Artist" },
            durationLabel = "",
            durationSec = 0,
            viewsLabel = count,
            views = -1,
            thumbUrl = avatarUrl(v),
            browseId = browseId,
        )
    }

    /** The top-result card when it *is* an artist — the link is in `title.runs[0]`. */
    private fun parseArtistCard(card: JSONObject): SearchResult? {
        val titleRun = card.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)
            ?: return null
        val browseId = artistBrowseId(titleRun.optJSONObject("navigationEndpoint"))
        if (browseId.isEmpty()) return null
        val name = titleRun.optString("text")
        if (name.isEmpty()) return null

        val (kind, count) = kindAndCount(card.optJSONObject("subtitle"))
        return SearchResult(
            videoId = "",
            title = name,
            channel = kind.ifEmpty { "Artist" },
            durationLabel = "",
            durationSec = 0,
            viewsLabel = count,
            views = -1,
            thumbUrl = avatarUrl(card),
            browseId = browseId,
        )
    }

    // ----------------------------------------------------------- album pages
    //
    // An album is a catalogue door like an artist, but its rows carry an
    // `MPRE` browse id and a `MUSIC_PAGE_TYPE_ALBUM` pageType. They sit in the
    // same search payload the app already fetches and used to drop, so keeping
    // them costs no new request shape — only a parser that recognises them.
    //
    // Captured 2026-10-24 (WEB_REMIX): the row shape from a mixed search, the
    // header shape from a `/browse` of `MPREb_G6JQUdInW98` (Portishead, Dummy).

    /** Album page id from a row's navigation endpoint, or "" when not an album. */
    private fun albumBrowseId(endpoint: JSONObject?): String {
        val browse = endpoint?.optJSONObject("browseEndpoint") ?: return ""
        val browseId = browse.optString("browseId")
        // `MPRE` is the album-id prefix; when a pageType is present it must be
        // an album or single, so an artist (UC) or playlist row never passes.
        if (browseId.isEmpty() || !browseId.startsWith("MPRE")) return ""
        val pageType = browse
            .optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")
            ?.optString("pageType").orEmpty()
        return when {
            pageType.isEmpty() -> browseId
            pageType == "MUSIC_PAGE_TYPE_ALBUM" || pageType == "MUSIC_PAGE_TYPE_SINGLE" -> browseId
            else -> ""
        }
    }

    /**
     * An album row: `navigationEndpoint` sits on the *row*, exactly like an
     * artist door. The subtitle runs read `Album • Artist • 1994`, so the
     * artist is the run that links to a `UC…` page and the year trails it.
     */
    private fun parseAlbumRow(v: JSONObject): SearchResult? {
        val browseId = albumBrowseId(v.optJSONObject("navigationEndpoint"))
        if (browseId.isEmpty()) return null

        val flex = v.optJSONArray("flexColumns") ?: return null
        val name = flex.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.let { allRunsText(it) }.orEmpty()
        if (name.isEmpty()) return null

        val runs = flex.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")?.optJSONArray("runs")
        var artist = ""
        var year = ""
        if (runs != null) {
            for (i in 0 until runs.length()) {
                val run = runs.optJSONObject(i) ?: continue
                val text = run.optString("text").trim()
                if (text.isEmpty() || text == "•") continue
                if (artist.isEmpty() && isArtistRun(run)) {
                    artist = text
                    continue
                }
                if (text.matches(Regex("^\\d{4}$"))) year = text
            }
        }
        return SearchResult(
            videoId = "",
            title = name,
            channel = artist,
            durationLabel = "",
            durationSec = 0,
            viewsLabel = year,
            views = -1,
            thumbUrl = avatarUrl(v),
            browseId = browseId,
        )
    }

    /**
     * The album page for [browseId] (`MPRE…`), or null when the request failed.
     *
     * Same `/browse` shape as [artistDetail] — context plus an id — so opening
     * a catalogue reuses the networking path that already exists.
     */
    suspend fun albumDetail(browseId: String): AlbumDetail? {
        if (browseId.isEmpty()) return null
        val body = JSONObject()
            .put("context", musicContext())
            .put("browseId", browseId)
        val raw = try {
            Http.postJson(
                url = "${ENDPOINT}browse?key=$KEY",
                jsonBody = body.toString(),
                headers = musicHeaders(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Same contract as artistDetail: a failed request is null, not a
            // crash and not an exception the screen has to know about.
            return null
        }
        val root = JSONObject(raw)
        captureVisitor(root)
        return parseAlbumDetail(root)
    }

    /**
     * The album page itself: header (title, artist, kind, year, cover art) and
     * its ordered track list.
     *
     * An album's header is a `musicResponsiveHeaderRenderer`, not the immersive
     * one an artist uses. Tracks are the responsive rows beneath it — the same
     * shape search already parses, so each is filtered by the official-music
     * whitelist and kept in YouTube's own order.
     */
    internal fun parseAlbumDetail(root: JSONObject): AlbumDetail? {
        val headers = mutableListOf<JSONObject>()
        collectNodes(root, "musicResponsiveHeaderRenderer", headers)
        val header = headers.firstOrNull()

        val items = mutableListOf<JSONObject>()
        collectNodes(root, "musicResponsiveListItemRenderer", items)
        val tracks = mutableListOf<SearchResult>()
        val seen = mutableSetOf<String>()
        for (item in items) {
            val (track, isUserUpload) = parseMusicRow(item) ?: continue
            if (isUserUpload) continue
            if (!seen.add(track.videoId)) continue
            tracks += track
        }

        if (header == null && tracks.isEmpty()) return null

        // Subtitle reads `Album • 1994`: the kind leads, a four-digit year trails.
        var kind = ""
        var year = ""
        val subRuns = header?.optJSONObject("subtitle")?.optJSONArray("runs")
        if (subRuns != null) {
            for (i in 0 until subRuns.length()) {
                val t = subRuns.optJSONObject(i)?.optString("text").orEmpty().trim()
                if (t.isEmpty() || t == "•") continue
                if (kind.isEmpty()) kind = t
                else if (t.matches(Regex("^\\d{4}$"))) year = t
            }
        }

        // The strapline is the artist, linked to its `UC…` page.
        val strapRun = header?.optJSONObject("straplineTextOne")
            ?.optJSONArray("runs")?.optJSONObject(0)
        val artistName = strapRun?.optString("text").orEmpty()
        val artistId = artistBrowseId(strapRun?.optJSONObject("navigationEndpoint"))

        // Second subtitle reads `11 songs • 49 minutes`.
        var trackCount = ""
        var duration = ""
        val secRuns = header?.optJSONObject("secondSubtitle")?.optJSONArray("runs")
        if (secRuns != null) {
            for (i in 0 until secRuns.length()) {
                val t = secRuns.optJSONObject(i)?.optString("text").orEmpty().trim()
                if (t.isEmpty() || t == "•") continue
                when {
                    t.contains("song", ignoreCase = true) -> trackCount = t
                    t.contains("minute", ignoreCase = true) ||
                        t.contains("hour", ignoreCase = true) -> duration = t
                }
            }
        }

        return AlbumDetail(
            title = runsText(header?.optJSONObject("title")),
            artistName = artistName,
            artistBrowseId = artistId,
            kindLabel = kind,
            yearLabel = year,
            trackCountLabel = trackCount,
            durationLabel = duration,
            artUrl = avatarUrl(header),
            tracks = tracks,
        )
    }

    /**
     * The artist page itself: header (name, audience, banner) and Top songs.
     *
     * Null only when the payload has neither a header nor a Top songs shelf —
     * that means the schema moved, and the caller reports it as a failure
     * rather than rendering an empty page that looks like a quiet artist.
     */
    internal fun parseArtistDetail(root: JSONObject): ArtistDetail? {
        val headers = mutableListOf<JSONObject>()
        collectNodes(root, "musicImmersiveHeaderRenderer", headers)
        val header = headers.firstOrNull()

        val shelves = mutableListOf<JSONObject>()
        collectNodes(root, "musicShelfRenderer", shelves)
        val top = shelves.firstOrNull { runsText(it.optJSONObject("title")) == "Top songs" }

        if (header == null && top == null) return null

        val songs = mutableListOf<SearchResult>()
        val seen = mutableSetOf<String>()
        val contents = top?.optJSONArray("contents")
        if (contents != null) {
            for (i in 0 until contents.length()) {
                val row = contents.optJSONObject(i)
                    ?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                val (track, isUserUpload) = parseMusicRow(row) ?: continue
                // Top songs are the label's own; a row the whitelist calls a
                // user upload here means the schema drifted, and playing it
                // would break the music-only promise.
                if (isUserUpload) continue
                if (!seen.add(track.videoId)) continue
                songs += track
            }
        }

        return ArtistDetail(
            name = runsText(header?.optJSONObject("title")),
            audienceLabel = runsText(header?.optJSONObject("monthlyListenerCount")),
            bannerUrl = avatarUrl(header),
            topSongs = songs,
        )
    }

    /** Every run in `text`, joined. Titles are normally one run, not always. */
    private fun allRunsText(node: JSONObject): String {
        val runs = node.optJSONObject("text")?.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return sb.toString()
    }

    /** The `watchEndpoint` on a column's first run, when there is one. */
    private fun runsWatchEndpoint(node: JSONObject): JSONObject? =
        node.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)
            ?.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")

    /** True when a run points at an artist page rather than a plain label. */
    private fun isArtistRun(run: JSONObject): Boolean =
        run.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")
            ?.optString("pageType") == "MUSIC_PAGE_TYPE_ARTIST"

    // -------------------------------------------------------------- player

    /** Thrown when no client could produce a playable audio URL. */
    class StreamUnavailable(message: String) : RuntimeException(message)

    /** A resolved stream plus what YouTube told us about the video itself. */
    data class Resolved(
        val stream: AudioStream,
        val title: String,
        val durationSec: Long,
    )

    /**
     * Resolve the best audio-only stream for [videoId].
     *
     * Tries each [PlayerClient] in order; the first that yields a direct audio
     * URL wins. Streams carrying `signatureCipher` instead of `url` are skipped
     * — we cannot decode those without a JavaScript engine.
     *
     * @throws StreamUnavailable when every client fails.
     */
    suspend fun resolve(videoId: String): Resolved {
        val failures = mutableListOf<String>()
        for (client in PLAYER_CLIENTS) {
            tryResolve(videoId, client)?.let { return it }
            failures += "${client.name} ${client.version}"
        }
        throw StreamUnavailable(
            "YouTube returned no playable audio (tried ${failures.joinToString(", ")}). " +
                "The client version likely needs bumping.",
        )
    }

    private suspend fun tryResolve(videoId: String, client: PlayerClient): Resolved? {
        val clientObj = JSONObject()
            .put("clientName", client.name)
            .put("clientVersion", client.version)
            .put("hl", "en")
            .put("gl", "US")
        client.clientExtras.forEach { (k, v) -> clientObj.put(k, v) }

        val body = JSONObject()
            .put("context", JSONObject().put("client", clientObj))
            .put("videoId", videoId)
        client.extras.forEach { (k, v) -> body.put(k, v) }

        // Adds `X-Goog-Visitor-Id` when we know it — without it VISIONOS
        // answers LOGIN_REQUIRED and every other client simply fares worse.
        suspend fun postPlayer(): String? = try {
            Http.postJson(
                url = "${ENDPOINT}player?key=$KEY",
                jsonBody = body.toString(),
                headers = buildMap {
                    put("X-Youtube-Client-Name", client.clientNameId)
                    put("X-Youtube-Client-Version", client.version)
                    put("User-Agent", client.userAgent)
                    visitorId?.let { put("X-Goog-Visitor-Id", it) }
                },
            )
        } catch (t: Throwable) {
            null
        }

        // Spend the second request only when this call is the one that first
        // learned a visitor id; otherwise every client would retry twice.
        val hadVisitor = visitorId != null
        val first = postPlayer() ?: return null
        var root = runCatching { JSONObject(first) }.getOrNull() ?: return null
        captureVisitor(root)

        fun status(): String =
            root.optJSONObject("playabilityStatus")?.optString("status").orEmpty()

        if (!hadVisitor && visitorId != null && status() != "OK") {
            val second = postPlayer() ?: return null
            root = runCatching { JSONObject(second) }.getOrNull() ?: return null
            captureVisitor(root)
        }

        if (status() != "OK") return null

        val best = bestAudio(root.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats"))
            ?: return null

        val details = root.optJSONObject("videoDetails")
        logStream(best.url, client)
        return Resolved(
            stream = best,
            title = details?.optString("title").orEmpty(),
            durationSec = details?.optLong("lengthSeconds", 0L) ?: 0L,
        )
    }

    /**
     * Enough of the stream URL to diagnose a 403 after the fact.
     *
     * `boundIp` is the one that matters: googlevideo checks the address the
     * media request leaves from against `ip=` in the URL. On a dual-stack
     * device the resolve and the playback can leave over different address
     * families, and the only symptom is a bare 403 with nothing else wrong.
     */
    private fun logStream(url: String, client: PlayerClient) {
        fun param(name: String): String =
            Regex("(?:[?&])$name=([^&]*)").find(url)?.groupValues?.get(1) ?: "-"
        val host = runCatching { java.net.URL(url).host }.getOrDefault("?")
        android.util.Log.i(
            "Stream",
            "client=${client.name} ${client.version} host=$host " +
                "boundIp=${param("ip")} expire=${param("expire")} c=${param("c")}",
        )
        // Full URL: only way to replay the exact request the phone made when
        // it comes back with a bare 403.
        android.util.Log.i("StreamUrl", url)
        // Which addresses Java will consider for the media request. The URL is
        // bound to whichever address the *resolve* left from; if the media
        // fetch picks a different family, googlevideo answers 403 and nothing
        // else about the request looks wrong.
        val dns = runCatching {
            java.net.InetAddress.getAllByName(host).joinToString { it.hostAddress }
        }.getOrDefault("dns-failed")
        android.util.Log.i("Stream", "dns $host -> $dns")

        // The source address the kernel actually picks for that destination.
        // If it disagrees with boundIp above, googlevideo refuses the stream
        // with a bare 403 and nothing else about the request looks wrong.
        val src = runCatching {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(host, 443), 6_000)
                s.localAddress.hostAddress
            }
        }.getOrDefault("connect-failed")
        val bound = runCatching {
            java.net.URLDecoder.decode(param("ip"), "UTF-8")
        }.getOrDefault("-")
        android.util.Log.i("Stream", "src=$src vs boundIp=$bound")
    }

    /**
     * Highest-bitrate audio format that carries a direct URL.
     *
     * `audio/mp4` (AAC) and `audio/webm` (Opus) both play natively in ExoPlayer,
     * so bitrate is the only thing worth ranking by.
     */
    private fun bestAudio(formats: JSONArray?): AudioStream? {
        if (formats == null) return null
        var best: AudioStream? = null
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val mime = f.optString("mimeType")
            if (!mime.startsWith("audio/")) continue
            // Ciphered formats need JS to decode; skip rather than half-play.
            val url = f.optString("url")
            if (url.isNullOrEmpty()) continue
            val candidate = AudioStream(
                url = url,
                mimeType = mime,
                bitrate = f.optInt("bitrate", 0),
                contentLength = f.optLong("contentLength", 0L),
            )
            if (best == null || candidate.bitrate > best.bitrate) best = candidate
        }
        return best
    }

    // ----------------------------------------------------------- parsing

    /**
     * Parse `/search`. Kept separate and pure so the unit tests can pin the
     * exact schema — this is the code that breaks when YouTube changes things,
     * and a red test tells us before a user does.
     */
    internal fun parseSearch(root: JSONObject): List<SearchResult> {
        val sections = root.optJSONObject("contents")
            ?.optJSONObject("twoColumnSearchResultsRenderer")
            ?.optJSONObject("primaryContents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
            ?: return emptyList()

        val out = mutableListOf<SearchResult>()
        for (s in 0 until sections.length()) {
            val items = sections.optJSONObject(s)
                ?.optJSONObject("itemSectionRenderer")
                ?.optJSONArray("contents") ?: continue
            for (i in 0 until items.length()) {
                val v = items.optJSONObject(i)?.optJSONObject("videoRenderer") ?: continue
                parseVideo(v)?.let { out += it }
            }
        }
        return out
    }

    /**
     * Parse `/next`. YouTube moved related videos into `lockupViewModel`, whose
     * metadata is nested one level deeper than it used to be
     * (`metadata.lockupMetadataViewModel`, not `lockupMetadataViewModel`).
     */
    internal fun parseRelated(root: JSONObject): List<SearchResult> {
        val results = root.optJSONObject("contents")
            ?.optJSONObject("twoColumnWatchNextResults")
            ?.optJSONObject("secondaryResults")
            ?.optJSONObject("secondaryResults")
            ?.optJSONArray("results")
            ?: return emptyList()

        val out = mutableListOf<SearchResult>()
        for (i in 0 until results.length()) {
            val lockup = results.optJSONObject(i)?.optJSONObject("lockupViewModel") ?: continue
            val videoId = lockup.optString("contentId")
            if (videoId.isNullOrEmpty()) continue
            // Playlist/channel lockups share this schema; only videos are playable.
            val contentType = lockup.optString("contentType")
            if (contentType.isNotEmpty() && contentType != "LOCKUP_CONTENT_TYPE_VIDEO") continue

            val meta = lockup.optJSONObject("metadata")
                ?.optJSONObject("lockupMetadataViewModel") ?: continue
            val title = meta.optJSONObject("title")?.optString("content").orEmpty()
            if (title.isEmpty()) continue

            out += SearchResult(
                videoId = videoId,
                title = title,
                channel = "",
                durationLabel = "",
                durationSec = 0L,
                viewsLabel = "",
                views = -1L,
                thumbUrl = thumbnailUrl(videoId),
            )
        }
        return out
    }

    private fun parseVideo(v: JSONObject): SearchResult? {
        val videoId = v.optString("videoId")
        if (videoId.isNullOrEmpty()) return null

        val title = runsText(v, "title").orEmpty()
        if (title.isEmpty()) return null

        val durationLabel = runsText(v, "lengthText").orEmpty()
        // Live streams have no length; they cannot be resolved as audio.
        if (durationLabel.isEmpty() && hasLiveBadge(v)) return null

        val viewsLabel = runsText(v, "viewCountText")
            ?: v.optJSONObject("shortViewCountText")?.optString("simpleText").orEmpty()

        return SearchResult(
            videoId = videoId,
            title = title,
            channel = runsText(v, "ownerText") ?: runsText(v, "longBylineText").orEmpty(),
            durationLabel = durationLabel,
            durationSec = parseClock(durationLabel),
            viewsLabel = viewsLabel,
            views = parseCount(viewsLabel),
            thumbUrl = thumbnailUrl(videoId),
        )
    }

    private fun hasLiveBadge(v: JSONObject): Boolean {
        val badges = v.optJSONArray("badges") ?: return false
        for (i in 0 until badges.length()) {
            val b = badges.optJSONObject(i)
                ?.optJSONObject("metadataBadgeRenderer")
                ?.optString("label").orEmpty()
            if (b.contains("live", ignoreCase = true)) return true
        }
        return false
    }

    /** YouTube's fields are either `{runs:[{text}]}` or `{simpleText}`. */
    private fun runsText(o: JSONObject?, key: String): String? {
        val node = o?.optJSONObject(key) ?: return null
        val runs = node.optJSONArray("runs")
        if (runs != null && runs.length() > 0) {
            return runs.optJSONObject(0)?.optString("text").orEmpty()
        }
        return node.optString("simpleText").orEmpty().ifEmpty { null }
    }

    private fun thumbnailUrl(videoId: String): String =
        "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

    /** "4:19" -> 259. Returns 0 for anything unparsable. */
    internal fun parseClock(text: String): Long {
        val parts = text.trim().split(':')
        if (parts.isEmpty() || parts.size > 3) return 0L
        var seconds = 0L
        for (p in parts) {
            val n = p.trim().toLongOrNull() ?: return 0L
            seconds = seconds * 60 + n
        }
        return seconds
    }

    /**
     * "1.2B views" -> 1_200_000_000. YouTube truncates past 1000, so this is
     * deliberately approximate — it only ever drives sort order.
     */
    internal fun parseCount(text: String): Long {
        val cleaned = text.lowercase()
            .replace(",", "")
            .replace("views", "")
            .replace("watching", "")
            // YT Music reports "9.5B plays" where the web client says "views".
            .replace("plays", "")
            .trim()
        if (cleaned.isEmpty()) return -1L
        val suffix = cleaned.lastOrNull()
        val mult = when (suffix) {
            'k' -> 1_000L
            'm' -> 1_000_000L
            'b' -> 1_000_000_000L
            't' -> 1_000_000_000_000L
            else -> 1L
        }
        val digits = if (mult == 1L) cleaned else cleaned.dropLast(1)
        val value = digits.trim().toDoubleOrNull() ?: return -1L
        return (value * mult).toLong()
    }
}

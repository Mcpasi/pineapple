package com.torrenttunes.app.youtube

import com.torrenttunes.app.search.AudioStream
import com.torrenttunes.app.search.Http
import com.torrenttunes.app.search.SearchResult
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
 * without shipping a JS engine. The ANDROID client hands back **plain URLs**,
 * so we use it and skip the cipher entirely.
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
    private const val ANDROID_UA =
        "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private const val ENDPOINT = "https://www.youtube.com/youtubei/v1/"

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

    // Verified 2026-10-19: ANDROID returns `status: OK` with 6 direct audio
    // URLs. The rest are ordered fallbacks for when YouTube retires that build.
    private val PLAYER_CLIENTS = listOf(
        PlayerClient(
            name = "ANDROID", version = "20.10.38", clientNameId = "3",
            userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip",
            clientExtras = mapOf("androidSdkVersion" to 30),
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
    suspend fun search(query: String): List<SearchResult> {
        val body = JSONObject()
            .put("context", webContext())
            .put("query", query)
        val raw = Http.postJson(
            url = "${ENDPOINT}search?key=$KEY",
            jsonBody = body.toString(),
            headers = webHeaders(),
        )
        return parseSearch(JSONObject(raw))
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
        return parseRelated(JSONObject(raw))
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

        val raw = try {
            Http.postJson(
                url = "${ENDPOINT}player?key=$KEY",
                jsonBody = body.toString(),
                headers = mapOf(
                    "X-Youtube-Client-Name" to client.clientNameId,
                    "X-Youtube-Client-Version" to client.version,
                    "User-Agent" to client.userAgent,
                ),
            )
        } catch (t: Throwable) {
            return null
        }

        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val status = root.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        if (status != "OK") return null

        val best = bestAudio(root.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats"))
            ?: return null

        val details = root.optJSONObject("videoDetails")
        return Resolved(
            stream = best,
            title = details?.optString("title").orEmpty(),
            durationSec = details?.optLong("lengthSeconds", 0L) ?: 0L,
        )
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

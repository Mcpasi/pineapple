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
    suspend fun search(query: String): List<SearchResult> {
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

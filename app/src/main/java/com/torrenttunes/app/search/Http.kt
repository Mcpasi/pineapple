package com.torrenttunes.app.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal blocking HTTP on Dispatchers.IO.
 *
 * Deliberately HttpURLConnection — zero new dependencies.
 *
 * A browser User-Agent is the default, but every caller can override it: the
 * InnerTube endpoints must be addressed as the specific client we claim to be
 * (YouTube answers a browser UA on /player with an unplayable response).
 */
object Http {

    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000

    private fun open(url: String, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", BROWSER_UA)
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        return conn
    }

    /** Read [conn]'s body fully, throwing [HttpException] on a non-2xx code. */
    private fun readAll(conn: HttpURLConnection): ByteArray {
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                // InnerTube reports most failures (unplayable, geo-blocked, bad
                // client) in the response body rather than the status line, so
                // keep a slice of it — otherwise every error looks identical.
                val detail = runCatching {
                    conn.errorStream?.use { it.readBytes() }?.toString(Charsets.UTF_8)
                }.getOrNull().orEmpty().take(400)
                throw HttpException(code, conn.url.toString(), detail)
            }
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        readAll(open(url, "GET"))
    }

    suspend fun getText(url: String): String = getBytes(url).toString(Charsets.UTF_8)

    /**
     * POST a JSON body and return the response text.
     *
     * [headers] is applied last so a caller can replace User-Agent and add the
     * `X-Youtube-Client-*` headers InnerTube uses to pick its response schema.
     */
    suspend fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val conn = open(url, "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
        readAll(conn).toString(Charsets.UTF_8)
    }

    fun enc(value: String): String =
        java.net.URLEncoder.encode(value.trim(), "UTF-8")
}

class HttpException(
    val code: Int,
    url: String,
    val detail: String = "",
) : RuntimeException("HTTP $code for $url" + if (detail.isEmpty()) "" else " — $detail")

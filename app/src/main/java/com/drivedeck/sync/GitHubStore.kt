package com.drivedeck.sync

import android.util.Base64
import com.drivedeck.data.DeckJson
import com.drivedeck.data.DeckState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads and writes `deck.json` in a private GitHub repo through the GitHub REST "contents" API.
 * GitHub gives us free, private, versioned storage (every sync is a commit, so nothing is ever
 * lost) that the laptop dashboard and the chat CLI can use too.
 */
class GitHubStore(
    private val owner: String,
    private val repo: String,
    private val token: String,
    private val path: String = "deck.json",
) {
    data class Remote(val state: DeckState?, val sha: String?)

    class AuthException(message: String) : IOException(message)
    class ConflictException : IOException("Changed on GitHub while saving")

    private val url get() = "https://api.github.com/repos/$owner/$repo/contents/$path"

    suspend fun fetch(): Remote = withContext(Dispatchers.IO) {
        val conn = open(url, "GET")
        try {
            when (val code = conn.responseCode) {
                200 -> {
                    val body = JSONObject(conn.inputStream.bufferedReader().readText())
                    val text = String(Base64.decode(body.getString("content"), Base64.DEFAULT), Charsets.UTF_8)
                    Remote(DeckJson.decode(text), body.getString("sha"))
                }
                404 -> Remote(null, null) // first sync: file doesn't exist yet
                401, 403 -> throw AuthException(errorText(conn, code))
                else -> throw IOException(errorText(conn, code))
            }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun put(state: DeckState, sha: String?, message: String) = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("message", message)
            put("content", Base64.encodeToString(DeckJson.encode(state).toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            sha?.let { put("sha", it) }
        }.toString()
        val conn = open(url, "PUT")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            when (val code = conn.responseCode) {
                200, 201 -> Unit
                409, 422 -> throw ConflictException()
                401, 403, 404 -> throw AuthException(errorText(conn, code))
                else -> throw IOException(errorText(conn, code))
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(u: String, method: String): HttpURLConnection =
        (URL(u).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            useCaches = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "DRIVEDECK-Android")
        }

    private fun errorText(conn: HttpURLConnection, code: Int): String {
        val msg = runCatching { JSONObject(conn.errorStream.bufferedReader().readText()).optString("message") }.getOrNull()
        return "GitHub $code${msg?.let { ": $it" } ?: ""}"
    }
}
